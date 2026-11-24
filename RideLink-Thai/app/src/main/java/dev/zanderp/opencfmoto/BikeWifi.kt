// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.net.Inet4Address

/**
 * Joins the bike's hotspot using [WifiNetworkSpecifier] or adopts an active system Wi-Fi link.
 *
 * Delegates session lifecycle, DHCP readiness gating, BSSID-hint fallback to SSID-only, and retry
 * backoff to [BikeWifiJoinMachine]. The process itself is NEVER bound to the bike [Network] so
 * `127.0.0.1` Android Auto loopback and cellular uplink remain intact.
 */
object BikeWifi {
    private var cm: ConnectivityManager? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var generalCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var specifierNetwork: Network? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val machine = BikeWifiJoinMachine<Network>()
    val currentNetwork: Network? get() = machine.currentNetwork

    @Volatile private var suggestionsCleared = false
    @Volatile private var sessionToken = 0
    @Volatile private var dhcpPollGeneration = 0
    @Volatile private var linkLogged = false
    private var ssid: String = ""
    private var onAvailableCb: ((Network) -> Unit)? = null
    private var onLostCb: (() -> Unit)? = null
    private var logCb: ((String) -> Unit)? = null
    @Volatile private var publishProjectionState: (() -> Unit) -> Unit = { it() }

    private val retryScheduler = ConnectionRetryScheduler(
        post = { task, delay -> handler.postDelayed(task, delay) },
        remove = { task -> handler.removeCallbacks(task) },
        eligible = { machine.active && machine.currentNetwork == null },
        retry = {
            val token = sessionToken
            applyEffects(machine.onRetryTimerFired(), token)
        },
    )

    private const val FRESH_SCAN_US = 30_000_000L // 30s

    /** True when the machine holds an active bike Wi-Fi [Network] with live Wi-Fi transport and IPv4/route. */
    fun isCurrentNetworkUsable(context: Context): Boolean {
        val net = machine.currentNetwork ?: return false
        if (!machine.active) return false
        val connMgr = cm
            ?: (context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return false
        val caps = runCatching { connMgr.getNetworkCapabilities(net) }.getOrNull() ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        return snapshotOf(connMgr, net, caps, targetSsid = ssid, context = context.applicationContext).isReadyForPxc
    }

    /** True while [BikeWifi] is actively waiting on a specifier join or DHCP readiness for an associated link. */
    fun hasActiveSpecifierWait(): Boolean =
        machine.active && (machine.specifierRequestInFlight || machine.associatedNetwork != null)

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        publishProjectionState: (() -> Unit) -> Unit = { it() },
        log: (String) -> Unit,
    ) {
        val appCtx = context.applicationContext
        val connMgr = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Tear down any previous session's callbacks and pending timers cleanly.
        val token = ++sessionToken
        dhcpPollGeneration++
        retryScheduler.cancel()
        handler.removeCallbacksAndMessages(null)
        machine.stop()
        callback?.let { try { connMgr.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        generalCallback?.let { try { connMgr.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        generalCallback = null
        specifierNetwork = null
        linkLogged = false

        this.appContext = appCtx
        this.cm = connMgr
        this.ssid = ssid
        this.onAvailableCb = onAvailable
        this.onLostCb = onLost
        this.publishProjectionState = publishProjectionState
        this.logCb = log

        // 1. Ensure no stale WifiNetworkSuggestion entries fight our WifiNetworkSpecifier SoftAP request.
        if (!suggestionsCleared && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suggestionsCleared = true
            try {
                val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val status = wm?.removeNetworkSuggestions(emptyList())
                log("WifiNetworkSuggestion cleared for silent SoftAP join (status=$status)")
            } catch (e: Exception) {
                log("WifiNetworkSuggestion cleanup note: $e")
            }
        }

        // 2. Adopt a ready system bike Wi-Fi link if already up, else start specifier with observed BSSID hint.
        val readySystem = findReadySystemBikeNetwork(appCtx, connMgr, ssid)
        val hintedBssid = resolveHintedBssid(appCtx, ssid)
        val target = BikeWifiTarget(ssid = ssid, psk = psk, hintedBssid = hintedBssid)
        applyEffects(machine.start(target, readySystem), token)

        // 3. Register general Wi-Fi callback to capture system auto-connects, DHCP completion, and drops.
        registerGeneralCallback(appCtx, connMgr, token)
    }

    /**
     * Mode switches (AA↔Map↔Mirror) must keep bike Wi-Fi and only rebuild PXC.
     * Reuses the existing [Network] ONLY if it is still usable and matches [ssid].
     */
    fun reuseOrJoin(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        publishProjectionState: (() -> Unit) -> Unit = { it() },
        log: (String) -> Unit,
    ) {
        val net = currentNetwork
        if (net != null && isCurrentNetworkUsable(context) && this.ssid.equals(ssid, ignoreCase = true)) {
            this.onAvailableCb = onAvailable
            this.onLostCb = onLost
            this.publishProjectionState = publishProjectionState
            this.logCb = log
            log("Wi-Fi already joined: $ssid — skipping re-join (mode switch)")
            onAvailable(net)
            return
        }
        join(context, ssid, psk, onAvailable, onLost, publishProjectionState, log)
    }

    private fun registerGeneralCallback(
        appCtx: Context,
        connMgr: ConnectivityManager,
        token: Int,
    ) {
        val genCb = createNetworkCallback(
            onAvailable = { self, net ->
                if (generalCallback !== self || token != sessionToken || !machine.active) return@createNetworkCallback
                if (net == specifierNetwork) return@createNetworkCallback
                val caps = runCatching { connMgr.getNetworkCapabilities(net) }.getOrNull() ?: return@createNetworkCallback
                val lp = runCatching { connMgr.getLinkProperties(net) }.getOrNull()
                if (matchesBikeNetwork(appCtx, connMgr, net, caps, lp)) {
                    val snap = snapshotOf(connMgr, net, caps, lp)
                    applyEffects(machine.onNetworkAvailable(net, viaSystem = true, snapshot = snap), token)
                }
            },
            onCapabilitiesChanged = { self, net, caps ->
                if (generalCallback !== self || token != sessionToken || !machine.active) return@createNetworkCallback
                if (net == specifierNetwork) return@createNetworkCallback
                val lp = runCatching { connMgr.getLinkProperties(net) }.getOrNull()
                handleSystemNetworkUpdate(appCtx, connMgr, net, caps, lp, token)
            },
            onLinkPropertiesChanged = { self, net, lp ->
                if (generalCallback !== self || token != sessionToken || !machine.active) return@createNetworkCallback
                if (net == specifierNetwork) return@createNetworkCallback
                val caps = runCatching { connMgr.getNetworkCapabilities(net) }.getOrNull() ?: return@createNetworkCallback
                handleSystemNetworkUpdate(appCtx, connMgr, net, caps, lp, token)
            },
            onLost = { self, net ->
                if (generalCallback !== self || token != sessionToken || !machine.active) return@createNetworkCallback
                if (specifierNetwork == net) specifierNetwork = null
                applyEffects(machine.onNetworkLost(net), token)
            },
        )
        generalCallback = genCb
        try {
            val genReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connMgr.registerNetworkCallback(genReq, genCb)
        } catch (e: Exception) {
            logCb?.invoke("general wifi callback registration note: $e")
        }
    }

    private fun handleSystemNetworkUpdate(
        appCtx: Context,
        connMgr: ConnectivityManager,
        net: Network,
        caps: NetworkCapabilities,
        lp: LinkProperties?,
        token: Int,
    ) {
        if (!matchesBikeNetwork(appCtx, connMgr, net, caps, lp)) return
        val snap = snapshotOf(connMgr, net, caps, lp)
        val effects = if (machine.currentNetwork == null && machine.associatedNetwork == null) {
            machine.onNetworkAvailable(net, viaSystem = true, snapshot = snap)
        } else {
            machine.onLinkPropertiesChanged(net, viaSystem = true, snapshot = snap)
        }
        applyEffects(effects, token)
    }

    private fun applyEffects(effects: List<BikeWifiJoinMachine.Effect<Network>>, expectedToken: Int) {
        for (effect in effects) {
            when (effect) {
                is BikeWifiJoinMachine.Effect.CancelRetry -> retryScheduler.cancel()
                is BikeWifiJoinMachine.Effect.CancelSpecifier -> cancelPendingSpecifier()
                is BikeWifiJoinMachine.Effect.RequestSpecifier -> {
                    if (expectedToken != sessionToken || !machine.active) return
                    handleRequestSpecifier(effect, expectedToken)
                }
                is BikeWifiJoinMachine.Effect.WaitForDhcp -> {
                    if (expectedToken != sessionToken || !machine.active) return
                    logCb?.invoke("Wi-Fi associated — waiting for DHCP IPv4…")
                    scheduleDhcpFallbackChecks(effect.network, expectedToken)
                }
                is BikeWifiJoinMachine.Effect.DeliverReadyNetwork -> {
                    if (expectedToken != sessionToken || !machine.active) return
                    handleDeliverReadyNetwork(effect)
                }
                is BikeWifiJoinMachine.Effect.NotifyNetworkLost -> {
                    if (expectedToken != sessionToken) return
                    dhcpPollGeneration++
                    linkLogged = false
                    logCb?.invoke("Wi-Fi lost: disconnecting bike sockets")
                    unbindProcess()
                    onLostCb?.invoke()
                }
                is BikeWifiJoinMachine.Effect.ScheduleRetry -> {
                    if (expectedToken != sessionToken || !machine.active) return
                    retryScheduler.schedule(effect.delayMs)
                }
                is BikeWifiJoinMachine.Effect.PublishUi -> {
                    if (expectedToken != sessionToken || !machine.active) return
                    handlePublishUi(effect)
                }
            }
        }
    }

    private fun cancelPendingSpecifier() {
        val prev = callback
        callback = null
        specifierNetwork = null
        prev?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
    }

    private fun handleRequestSpecifier(
        effect: BikeWifiJoinMachine.Effect.RequestSpecifier,
        expectedToken: Int,
    ) {
        val connMgr = cm ?: return
        cancelPendingSpecifier()

        val builder = WifiNetworkSpecifier.Builder()
            .setSsid(effect.ssid)
            .setWpa2Passphrase(effect.psk)
        if (effect.bssid != null) {
            runCatching { MacAddress.fromString(effect.bssid) }
                .onSuccess { builder.setBssid(it) }
                .onFailure { logCb?.invoke("ignored invalid BSSID hint; requesting SSID-only") }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()

        val cb = createNetworkCallback(
            onAvailable = { self, net ->
                if (callback !== self || expectedToken != sessionToken) return@createNetworkCallback
                specifierNetwork = net
                val snap = snapshotOf(connMgr, net)
                applyEffects(machine.onNetworkAvailable(net, viaSystem = false, snapshot = snap), expectedToken)
            },
            onCapabilitiesChanged = { self, net, caps ->
                if (callback !== self || expectedToken != sessionToken) return@createNetworkCallback
                specifierNetwork = net
                logLinkOnce(net, caps)
                val snap = snapshotOf(connMgr, net, caps = caps)
                applyEffects(machine.onLinkPropertiesChanged(net, viaSystem = false, snapshot = snap), expectedToken)
            },
            onLinkPropertiesChanged = { self, net, lp ->
                if (callback !== self || expectedToken != sessionToken) return@createNetworkCallback
                specifierNetwork = net
                val snap = snapshotOf(connMgr, net, lp = lp)
                applyEffects(machine.onLinkPropertiesChanged(net, viaSystem = false, snapshot = snap), expectedToken)
            },
            onLost = { self, net ->
                if (callback !== self || expectedToken != sessionToken) return@createNetworkCallback
                if (specifierNetwork == net) specifierNetwork = null
                applyEffects(machine.onNetworkLost(net), expectedToken)
            },
            onUnavailable = { self ->
                if (callback !== self || expectedToken != sessionToken) return@createNetworkCallback
                if (!machine.active || machine.currentNetwork != null || machine.associatedNetwork != null) {
                    return@createNetworkCallback
                }
                callback = null
                specifierNetwork = null
                val timeoutSec = effect.timeoutMs / 1000
                logCb?.invoke(
                    "Wi-Fi join unavailable after ${timeoutSec}s " +
                        "(bike off, out of range, declined, or system picker timed out) — will retry",
                )
                if (machine.currentNetwork == null) unbindProcess()
                applyEffects(machine.onRequestUnavailable(), expectedToken)
            },
        )
        callback = cb

        val modeLabel = if (effect.bssid != null) "bssid-hint" else "ssid-only"
        logCb?.invoke("requesting Wi-Fi join: ${effect.ssid} (attempt=${effect.attempt}, $modeLabel, ${effect.timeoutMs / 1000}s) …")
        if (!machine.everDelivered && effect.attempt == 1) {
            logCb?.invoke(
                "→ approve the phone's Wi-Fi / device picker if it appears " +
                    "(do not leave it open past ${effect.timeoutMs / 1000}s)",
            )
        }
        try {
            connMgr.requestNetwork(req, cb, effect.timeoutMs)
        } catch (e: Exception) {
            if (callback === cb) callback = null
            specifierNetwork = null
            logCb?.invoke("requestNetwork note (${e.javaClass.simpleName}): ${e.message}")
            applyEffects(machine.onRequestUnavailable(), expectedToken)
        }
    }

    private fun scheduleDhcpFallbackChecks(network: Network, expectedToken: Int) {
        val gen = ++dhcpPollGeneration
        val viaSystem = network != specifierNetwork
        for (delayMs in longArrayOf(400L, 1_200L)) {
            handler.postDelayed(
                {
                    if (gen != dhcpPollGeneration || expectedToken != sessionToken || !machine.active) return@postDelayed
                    val connMgr = cm ?: return@postDelayed
                    val snap = snapshotOf(connMgr, network)
                    if (snap.isReadyForPxc) {
                        applyEffects(
                            machine.onLinkPropertiesChanged(network, viaSystem = viaSystem, snapshot = snap),
                            expectedToken,
                        )
                    }
                },
                delayMs,
            )
        }
    }

    private fun handleDeliverReadyNetwork(effect: BikeWifiJoinMachine.Effect.DeliverReadyNetwork<Network>) {
        dhcpPollGeneration++
        retryScheduler.cancel()
        val ctx = appContext
        if (ctx != null && effect.observedBssid != null) {
            BikeMemory.setLastWifiBssid(ctx, ssid, effect.observedBssid)
        }
        logLinkOnce(effect.network)
        when {
            effect.reacquired ->
                logCb?.invoke("Wi-Fi re-acquired: $ssid — restarting bike link on fresh network")
            effect.viaSystem ->
                logCb?.invoke("Wi-Fi joined via system auto-connect: $ssid (network=${effect.network})")
            else ->
                logCb?.invoke("Wi-Fi joined: $ssid (network=${effect.network}, bind deferred until AA video)")
        }
        onAvailableCb?.invoke(effect.network)
    }

    private fun handlePublishUi(effect: BikeWifiJoinMachine.Effect.PublishUi) {
        if (effect.phase == Phase.ERROR && effect.detailRes == R.string.conn_detail_wifi_picker) {
            logCb?.invoke(
                "→ TAP the phone's Wi-Fi / device picker NOW (SSID of the bike), " +
                    "or join that network in Settings → Wi-Fi, then Connect again",
            )
        }
        val publish = publishProjectionState
        publish {
            try {
                if (effect.attempt != null) {
                    ConnectionState.set(effect.phase, effect.detailRes, effect.attempt)
                } else {
                    ConnectionState.set(effect.phase, effect.detailRes)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun findReadySystemBikeNetwork(
        context: Context,
        connMgr: ConnectivityManager,
        targetSsid: String,
    ): Pair<Network, BikeLinkSnapshot>? {
        @Suppress("DEPRECATION")
        val allNets = runCatching { connMgr.allNetworks }.getOrNull() ?: return null
        for (net in allNets) {
            val caps = runCatching { connMgr.getNetworkCapabilities(net) }.getOrNull() ?: continue
            val lp = runCatching { connMgr.getLinkProperties(net) }.getOrNull()
            if (!matchesBikeNetwork(context, connMgr, net, caps, lp, targetSsid)) continue
            val snap = snapshotOf(connMgr, net, caps, lp, targetSsid, context)
            if (snap.isReadyForPxc) return net to snap
        }
        return null
    }

    private fun resolveHintedBssid(context: Context, targetSsid: String): String? {
        val cleanTarget = targetSsid.trim('"', ' ')
        if (cleanTarget.isBlank()) return null

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val fineGranted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (wm != null && wm.isWifiEnabled && fineGranted) {
            val results = try { wm.scanResults } catch (_: SecurityException) { null }
            if (!results.isNullOrEmpty()) {
                val nowUs = SystemClock.elapsedRealtime() * 1000L
                val freshMatch = results
                    .asSequence()
                    .filter { it.timestamp > 0 && nowUs - it.timestamp in 0 until FRESH_SCAN_US }
                    .filter { it.SSID?.trim('"')?.equals(cleanTarget, ignoreCase = true) == true }
                    .sortedByDescending { it.level }
                    .mapNotNull { BikeMemory.normalizeValidUnicastBssid(it.BSSID) }
                    .firstOrNull()
                if (freshMatch != null) return freshMatch
            }
        }
        return BikeMemory.lastWifiBssid(context, targetSsid)
    }

    private fun matchesBikeNetwork(
        context: Context,
        connMgr: ConnectivityManager,
        net: Network,
        caps: NetworkCapabilities,
        lp: LinkProperties?,
        targetSsid: String = this.ssid,
    ): Boolean {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val cleanTarget = targetSsid.trim('"', ' ')
        if (cleanTarget.isBlank()) return false

        val transportSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            wifiInfo?.ssid?.trim('"', ' ')?.takeIf {
                it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
            }
        } else null
        if (transportSsid != null) {
            return transportSsid.equals(cleanTarget, ignoreCase = true)
        }

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wmSsid = wm?.connectionInfo?.ssid?.trim('"', ' ')?.takeIf {
            it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
        }
        if (wmSsid != null) {
            return wmSsid.equals(cleanTarget, ignoreCase = true)
        }

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        val resolvedLp = lp ?: runCatching { connMgr.getLinkProperties(net) }.getOrNull()
        return hasBikeSoftApGateway(resolvedLp)
    }

    private fun hasBikeSoftApGateway(lp: LinkProperties?): Boolean {
        val routes = lp?.routes ?: return false
        return routes.any { r ->
            val g = r.gateway
            g is Inet4Address && (g.hostAddress ?: "").let { host ->
                host.startsWith("192.168.3.") ||
                    host.startsWith("192.168.43.") ||
                    host.startsWith("192.168.50.") ||
                    host.startsWith("192.168.18.") ||
                    host.startsWith("192.168.123.") ||
                    host.startsWith("192.168.49.")
            }
        }
    }

    private fun snapshotOf(
        connMgr: ConnectivityManager,
        network: Network,
        caps: NetworkCapabilities? = null,
        lp: LinkProperties? = null,
        targetSsid: String = this.ssid,
        context: Context? = this.appContext,
    ): BikeLinkSnapshot {
        val resolvedCaps = caps ?: runCatching { connMgr.getNetworkCapabilities(network) }.getOrNull()
        val resolvedLp = lp ?: runCatching { connMgr.getLinkProperties(network) }.getOrNull()

        val ipv4Links = resolvedLp?.linkAddresses
            ?.mapNotNull { la ->
                val addr = la.address as? Inet4Address ?: return@mapNotNull null
                if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress) {
                    null
                } else {
                    addr to la.prefixLength
                }
            }
            .orEmpty()
        val hasIpv4 = ipv4Links.isNotEmpty()

        val hasExplicitGateway = resolvedLp?.routes?.any { route ->
            val gw = route.gateway
            gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress
        } == true
        val hasSubnetPrefix = ipv4Links.any { (_, prefix) -> prefix in 1..31 }
        val hasIpv4Dns = resolvedLp?.dnsServers?.any { dns ->
            dns is Inet4Address && !dns.isAnyLocalAddress && !dns.isLoopbackAddress
        } == true

        return BikeLinkSnapshot(
            hasIpv4 = hasIpv4,
            hasGatewayOrSubnet = hasExplicitGateway || hasSubnetPrefix || hasIpv4Dns,
            observedBssid = extractObservedBssid(context, resolvedCaps, targetSsid),
        )
    }

    private fun extractObservedBssid(
        context: Context?,
        caps: NetworkCapabilities?,
        targetSsid: String,
    ): String? {
        val cleanTarget = targetSsid.trim('"', ' ')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps?.transportInfo as? WifiInfo
            val infoSsid = info?.ssid?.trim('"', ' ')?.takeIf {
                it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
            }
            if (info != null && (infoSsid == null || infoSsid.equals(cleanTarget, ignoreCase = true))) {
                BikeMemory.normalizeValidUnicastBssid(info.bssid)?.let { return it }
            }
        }
        if (context != null && cleanTarget.isNotBlank()) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val conn = wm?.connectionInfo
            val connSsid = conn?.ssid?.trim('"', ' ')?.takeIf {
                it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
            }
            if (connSsid != null && connSsid.equals(cleanTarget, ignoreCase = true)) {
                BikeMemory.normalizeValidUnicastBssid(conn.bssid)?.let { return it }
            }
        }
        return null
    }

    private fun createNetworkCallback(
        onAvailable: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onCapabilitiesChanged: (ConnectivityManager.NetworkCallback, Network, NetworkCapabilities) -> Unit = { _, _, _ -> },
        onLinkPropertiesChanged: (ConnectivityManager.NetworkCallback, Network, LinkProperties) -> Unit = { _, _, _ -> },
        onLost: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onUnavailable: (ConnectivityManager.NetworkCallback) -> Unit = {},
    ): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createLocationAwareNetworkCallback(
                onAvailable,
                onCapabilitiesChanged,
                onLinkPropertiesChanged,
                onLost,
                onUnavailable,
            )
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable(this, network)
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    onCapabilitiesChanged(this, network, networkCapabilities)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    onLinkPropertiesChanged(this, network, linkProperties)
                override fun onLost(network: Network) = onLost(this, network)
                override fun onUnavailable() = onUnavailable(this)
            }
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createLocationAwareNetworkCallback(
        onAvailable: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onCapabilitiesChanged: (ConnectivityManager.NetworkCallback, Network, NetworkCapabilities) -> Unit,
        onLinkPropertiesChanged: (ConnectivityManager.NetworkCallback, Network, LinkProperties) -> Unit,
        onLost: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onUnavailable: (ConnectivityManager.NetworkCallback) -> Unit,
    ): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = onAvailable(this, network)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                onCapabilitiesChanged(this, network, networkCapabilities)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                onLinkPropertiesChanged(this, network, linkProperties)
            override fun onLost(network: Network) = onLost(this, network)
            override fun onUnavailable() = onUnavailable(this)
        }

    private fun logLinkOnce(network: Network, deliveredCaps: NetworkCapabilities? = null) {
        if (linkLogged) return
        val connMgr = this.cm ?: return
        try {
            val caps = deliveredCaps ?: connMgr.getNetworkCapabilities(network) ?: return
            val info = caps.transportInfo as? WifiInfo ?: return
            linkLogged = true
            val mhz = info.frequency
            val band = if (mhz in 2400..2500) "2.4GHz — SHARED WITH BLUETOOTH" else "${mhz / 1000}GHz"
            logCb?.invoke(
                "[wifi] link: ${mhz}MHz ($band), rssi=${info.rssi}dBm, " +
                    "tx=${info.txLinkSpeedMbps}Mbps, rx=${info.rxLinkSpeedMbps}Mbps",
            )
        } catch (_: Exception) {
        }
    }

    /** กดเริ่มซ้ำแล้วเร่งเฉพาะการลองที่รออยู่ ไม่ยกเลิกตัวเลือก Wi-Fi ที่กำลังเปิด */
    internal fun retryPendingJoinNow(): Boolean = retryScheduler.retryNow()

    /**
     * Best-effort "is the bike's hotspot nearby?" check for auto-connect. Deliberately biased toward
     * `null` ("unknown → try anyway"): a false "not in range" is worse than a wasted connect attempt.
     */
    fun isSsidInRange(context: Context, ssid: String): Boolean? {
        if (ssid.isBlank()) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        if (!wm.isWifiEnabled) return null
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return null

        @Suppress("DEPRECATION")
        val connected = wm.connectionInfo?.ssid?.trim('"')
        if (connected != null && connected.equals(ssid, ignoreCase = true)) return true

        @Suppress("DEPRECATION")
        val results = try { wm.scanResults } catch (_: SecurityException) { null } ?: return null
        if (results.isEmpty()) return null

        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val fresh = results.filter { it.timestamp > 0 && nowUs - it.timestamp < FRESH_SCAN_US }
        if (fresh.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        if (results.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        return if (fresh.isNotEmpty()) false else null
    }

    /**
     * End the session Wi-Fi: full teardown, or [park] (stay associated) when the rider
     * enabled Setup → keep bike Wi-Fi after disconnect.
     */
    fun releaseSession(context: Context, log: (String) -> Unit) {
        if (false) {
            park(context, log)
            BikeWifiP2p.park(log)
        } else {
            leave(context, log)
            BikeWifiP2p.stop(log)
        }
    }

    /**
     * Keep the SoftAP association (callback stays registered) but unbind the process so
     * cellular / maps work. Some dashes forget the clock the moment the group drops.
     */
    fun park(context: Context, log: (String) -> Unit) {
        val connMgr = this.cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        unbindProcess(connMgr)
        log("Wi-Fi kept after disconnect (dash clock) — process unbound, SoftAP still associated")
    }

    fun leave(context: Context, log: (String) -> Unit) {
        val token = ++sessionToken
        dhcpPollGeneration++
        val connMgr = this.cm ?: (context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        this.cm = connMgr
        applyEffects(machine.stop(), expectedToken = token)
        handler.removeCallbacksAndMessages(null)
        callback?.let {
            try { connMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        generalCallback?.let {
            try { connMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        generalCallback = null
        specifierNetwork = null
        linkLogged = false
        unbindProcess(connMgr)
        log("Wi-Fi released")
    }

    /**
     * Clear process→Network binding so loopback / cellular sockets work again.
     * Safe to call when already unbound. Used after bike Wi-Fi loss and before AA :5288 bind.
     */
    fun unbindProcess(cmIn: ConnectivityManager? = null, context: Context? = null) {
        val connMgr = cmIn
            ?: this.cm
            ?: context?.applicationContext
                ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        try {
            connMgr.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    /**
     * If we aren't on a live bike [Network], ensure the process isn't stuck on a stale bind
     * (ENONET on ServerSocket / localhost). No-op when [currentNetwork] is set.
     */
    fun unbindIfNoBikeNetwork(context: Context) {
        if (currentNetwork != null) return
        unbindProcess(context = context)
    }

    /**
     * Re-asserts bike socket connectivity without binding the whole process.
     *
     * CRITICAL ARCHITECTURAL RULE (ANDROID_AUTO_ARCHITECTURE.md):
     * Binding the entire process to the internet-less bike AP removes the 127.0.0.1 loopback
     * route and destroys the AAP TLS handshake with Android Auto / Gearhead.
     * All bike communication MUST use Network.socketFactory or Network.bindSocket instead.
     * The process itself MUST remain unbound so 127.0.0.1 and cellular data stay 100% intact.
     */
    fun rebindProcessToBike(context: Context): Boolean {
        unbindProcess(context = context)
        return true
    }

    /**
     * True if a **live internet-capable** VPN network is present. Idle/leftover `TRANSPORT_VPN`
     * interfaces (OEM Secure Wi-Fi stubs, parked clients) used to trip false "VPN blocking" errors.
     */
    fun isVpnActive(context: Context): Boolean {
        val connMgr = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            @Suppress("DEPRECATION")
            connMgr.allNetworks.any { n ->
                val caps = connMgr.getNetworkCapabilities(n) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Short description of VPN networks for Logs (empty when none). */
    fun vpnNetworksSummary(context: Context): String {
        val connMgr = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            @Suppress("DEPRECATION")
            connMgr.allNetworks.mapNotNull { n ->
                val caps = connMgr.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val inet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val vali = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                "vpn(internet=$inet,validated=$vali)"
            }.joinToString(", ")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Always-on VPN with "Block connections without VPN" returns EPERM from
     * [Network.bindSocket] / [Network.getSocketFactory] — the app cannot pin traffic to bike Wi-Fi.
     */
    fun isVpnBindBlocked(context: Context, error: Throwable?): Boolean {
        if (error == null || !isVpnActive(context)) return false
        var t: Throwable? = error
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("EPERM", ignoreCase = true) ||
                m.contains("Operation not permitted", ignoreCase = true)
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    /**
     * Quick check: can we create a socket on the bike [Network]? Returns null if OK, else the error.
     * Call after join when a VPN may be in lockdown mode.
     */
    fun testBikeSocketBind(context: Context): Exception? {
        val n = currentNetwork ?: return null
        rebindProcessToBike(context)
        return try {
            n.socketFactory.createSocket().close()
            null
        } catch (e1: Exception) {
            if (isVpnBindBlocked(context, e1)) return e1
            try {
                val s = java.net.Socket()
                n.bindSocket(s)
                s.close()
                null
            } catch (e2: Exception) {
                e2
            }
        }
    }
}
