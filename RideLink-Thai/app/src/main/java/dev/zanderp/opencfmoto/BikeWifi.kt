// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
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

/**
 * Joins the bike's hotspot using WifiNetworkSpecifier (no system Wi-Fi config required).
 *
 * The system shows a dialog asking the user to accept the network. After acceptance, the
 * resulting Network object is process-bound so our TCP sockets and mDNS lookups go through
 * the bike's interface (which has no internet — that's fine). Once the SSID has been approved,
 * Android re-satisfies the request without prompting again, so re-joins are silent.
 *
 * ## Auto-rejoin on loss (bike power-cycle)
 * A [WifiNetworkSpecifier] request is *terminal* on loss: when the bike is switched off its hotspot
 * disappears and the framework won't re-satisfy the old request on its own. So if the network drops
 * while a session is still active, we re-issue the request (with backoff) until the bike's AP comes
 * back. The FIRST acquisition drives the normal start-up; every subsequent one is a re-acquire that
 * restarts the bike link on the fresh network via [BikeLink.onWifiReacquired] (fresh IP + rebound
 * server sockets + fresh probe). Without this, stopping and restarting the bike left the app probing
 * a dead interface and needed several manual Stop→Connect cycles to recover.
 */
object BikeWifi {
    private var cm: ConnectivityManager? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    private var request: NetworkRequest? = null
    @Volatile private var generalCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val networkState = BikeWifiNetworkState<Network>()
    val currentNetwork: Network? get() = networkState.current

    @Volatile private var active = false
    @Volatile private var suggestionsCleared = false
    private val firstDelivered: Boolean get() = networkState.everDelivered
    private var rejoinAttempts = 0
    private var ssid: String = ""
    private var onAvailableCb: ((Network) -> Unit)? = null
    private var onLostCb: (() -> Unit)? = null
    private var logCb: ((String) -> Unit)? = null
    @Volatile private var publishProjectionState: (() -> Unit) -> Unit = { it() }
    private val retryScheduler = ConnectionRetryScheduler(
        post = { task, delay -> handler.postDelayed(task, delay) },
        remove = { task -> handler.removeCallbacks(task) },
        eligible = { active && currentNetwork == null },
        retry = {
            logCb?.invoke("re-requesting bike Wi-Fi (attempt $rejoinAttempts) …")
            val publish = publishProjectionState
            publish {
                if (ConnectionState.phase != Phase.WAITING_FOR_BIKE) {
                    ConnectionState.set(Phase.RECONNECTING, R.string.conn_joining_wifi)
                }
            }
            registerCallback()
        },
    )

    // Watch for the bike's AP until the rider taps Stop (leave()): a stopped bike may be off for a
    // while, and re-issuing the (approved, silent) request is cheap. Backoff grows then caps so we
    // don't hammer the framework while parked.
    //
    // The FIRST re-request after a drop is near-instant: at home the phone races to re-join a saved
    // network the moment the bike's AP blips, and if we don't re-grab the bike AP fast the dash's
    // EasyConn times out ("device is not on the network") and needs a manual restart. Grabbing it back
    // immediately keeps us on the bike's network long enough for the dash to reconnect on its own.
    private const val REJOIN_FAST_MS = 300L
    private const val REJOIN_BASE_MS = 2500L
    private const val REJOIN_MAX_MS = 15000L
    /** After a failed *first* join (picker canceled / timed out), wait before re-prompting. */
    private const val FIRST_JOIN_RETRY_MS = 4_000L
    /**
     * Timeout for WifiNetworkSpecifier. Reduced from 90s to 15s to prevent long frozen states
     * on Xiaomi HyperOS / Android 14 when peer dialog is dismissed.
     */
    private const val FIRST_JOIN_TIMEOUT_MS = 15_000
    private const val REJOIN_TIMEOUT_MS = 12_000

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        publishProjectionState: (() -> Unit) -> Unit = { it() },
        log: (String) -> Unit,
    ) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Tear down any previous session's callback first.
        active = false
        retryScheduler.cancel()
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        generalCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        generalCallback = null
        networkState.reset()
        linkLogged = false

        this.appContext = context.applicationContext
        this.cm = cm
        this.ssid = ssid
        this.onAvailableCb = onAvailable
        this.onLostCb = onLost
        this.publishProjectionState = publishProjectionState
        this.logCb = log
        this.active = true
        this.rejoinAttempts = 0

        // 1. Ensure no stale WifiNetworkSuggestion entries fight our WifiNetworkSpecifier SoftAP request
        if (!suggestionsCleared && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suggestionsCleared = true
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val status = wm?.removeNetworkSuggestions(emptyList())
                log("WifiNetworkSuggestion cleared for silent SoftAP join (status=$status)")
            } catch (e: Exception) {
                log("WifiNetworkSuggestion cleanup note: $e")
            }
        }

        // 2. Register general Wi-Fi callback to capture background system connection immediately
        val genCb = createNetworkCallback(
            onAvailable = { self, net ->
                if (generalCallback === self) checkAndDeliverBikeNet(net)
            },
            onCapabilitiesChanged = { self, net, caps ->
                if (generalCallback === self) checkAndDeliverBikeNet(net, caps)
            },
            onLost = { self, net ->
                if (generalCallback === self) handleNetworkLost(net)
            },
        )
        generalCallback = genCb
        try {
            val genReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(genReq, genCb)
        } catch (e: Exception) {
            log("general wifi callback registration note: $e")
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()
        request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        log("requesting Wi-Fi join: $ssid …")
        registerCallback()
    }

    private fun checkAndDeliverBikeNet(net: Network, deliveredCaps: NetworkCapabilities? = null) {
        if (!active) return
        val context = appContext ?: return
        val cm = this.cm ?: return
        val caps = deliveredCaps ?: cm.getNetworkCapabilities(net) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

        val targetSsid = ssid.trim('"', ' ')
        val transportSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            wifiInfo?.ssid?.trim('"', ' ')?.takeIf {
                it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
            }
        } else null

        val matches = if (transportSsid != null) {
            transportSsid.equals(targetSsid, ignoreCase = true)
        } else {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val curr = wm?.connectionInfo?.ssid?.trim('"', ' ')?.takeIf {
                it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
            }
            val ssidMatches = curr != null && curr.equals(targetSsid, ignoreCase = true)
            val gatewayMatches = !ssidMatches && currentNetwork == null &&
                cm.getLinkProperties(net)?.routes?.any { r ->
                    val g = r.gateway
                    g is java.net.Inet4Address && (g.hostAddress ?: "").let {
                        it.startsWith("192.168.3.") ||
                            it.startsWith("192.168.43.") ||
                            it.startsWith("192.168.50.") ||
                            it.startsWith("192.168.18.") ||
                            it.startsWith("192.168.123.") ||
                            it.startsWith("192.168.49.")
                    }
                } == true
            ssidMatches || gatewayMatches
        }

        if (matches) {
            deliverNetwork(net, viaSystem = true, deliveredCaps = caps)
        }
    }

    private fun deliverNetwork(
        network: Network,
        viaSystem: Boolean,
        deliveredCaps: NetworkCapabilities? = null,
    ) {
        if (!active) return
        val change = networkState.available(network)
        if (change == BikeWifiNetworkState.Available.UNCHANGED) return
        retryScheduler.cancel()
        rejoinAttempts = 0
        logLinkOnce(network, deliveredCaps)
        when {
            change == BikeWifiNetworkState.Available.REACQUIRED ->
                logCb?.invoke("Wi-Fi re-acquired: $ssid — restarting bike link on fresh network")
            viaSystem -> logCb?.invoke("Wi-Fi joined via system auto-connect: $ssid (network=$network)")
            else -> logCb?.invoke("Wi-Fi joined: $ssid (network=$network, bind deferred until AA video)")
        }
        onAvailableCb?.invoke(network)
    }

    private fun handleNetworkLost(network: Network) {
        if (!active || !networkState.lost(network)) return
        logCb?.invoke("Wi-Fi lost: $network")
        linkLogged = false
        // เครือข่ายที่หลุดต้องไม่ค้างเป็น process binding ของ AA และ PXC
        unbindProcess()
        onLostCb?.invoke()
        if (active) scheduleRejoin()
    }

    /**
     * Mode switches (AA↔Map↔Mirror) must keep bike Wi‑Fi and only rebuild PXC.
     * If we're already process-bound to [ssid], fire [onAvailable] immediately — do not
     * unregister/re-request (that added multi-second stalls and "already running" races).
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
        if (active && net != null && this.ssid.equals(ssid, ignoreCase = true)) {
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

    private fun registerCallback() {
        val cm = cm ?: return
        val req = request ?: return
        val previous = callback
        callback = null
        previous?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        val publish = publishProjectionState

        val cb = createNetworkCallback(
            onAvailable = { self, network ->
                if (callback !== self) return@createNetworkCallback
                // Do not bindProcessToNetwork here. Binding the process to the bike AP removes
                // the 127.0.0.1 route (AaReceiver.connectLoopback). That dropped AA ~500ms after
                // a successful handshake on DIRECT-go-CFMOTO / 800NK before video went steady.
                // [BikeLink] / [EasyConnProber] call [rebindProcessToBike] when PXC actually starts.
                // AppHttp.ensureCellularUplink()
                deliverNetwork(network, viaSystem = false)
            },
            onCapabilitiesChanged = { self, network, caps ->
                if (callback === self) logLinkOnce(network, caps)
            },
            onLost = { self, network ->
                if (callback === self) handleNetworkLost(network)
            },
            onUnavailable = { self ->
                if (callback !== self || !active || currentNetwork != null) return@createNetworkCallback
                val timeoutSec = (if (firstDelivered) REJOIN_TIMEOUT_MS else FIRST_JOIN_TIMEOUT_MS) / 1000
                logCb?.invoke(
                    "Wi-Fi join unavailable after ${timeoutSec}s " +
                        "(bike off, out of range, declined, or system picker timed out) — will retry",
                )
                // First join often means the system Wi‑Fi / Nearby picker was ignored — surface it.
                if (!firstDelivered) {
                    logCb?.invoke(
                        "→ TAP the phone's Wi‑Fi / device picker NOW (SSID of the bike), " +
                            "or join that network in Settings → Wi‑Fi, then Connect again",
                    )
                    try {
                        publish {
                            try { ConnectionState.set(Phase.ERROR, R.string.conn_detail_wifi_picker) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {
                    }
                }
                // Join timed out with no live Network — make sure we aren't still pinned to a
                // previous dead bike AP (resume / auto-connect path).
                if (currentNetwork == null) unbindProcess()
                if (active) scheduleRejoin()
            },
        )
        callback = cb
        // Timeout is mandatory — see FIRST_JOIN_TIMEOUT_MS / REJOIN_TIMEOUT_MS.
        val timeoutMs = if (firstDelivered) REJOIN_TIMEOUT_MS else FIRST_JOIN_TIMEOUT_MS
        if (!firstDelivered) {
            logCb?.invoke(
                "→ approve the phone's Wi‑Fi / device picker if it appears " +
                    "(do not leave it open past ${FIRST_JOIN_TIMEOUT_MS / 1000}s)",
            )
        }
        cm.requestNetwork(req, cb, timeoutMs)
    }

    private fun createNetworkCallback(
        onAvailable: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onCapabilitiesChanged: (ConnectivityManager.NetworkCallback, Network, NetworkCapabilities) -> Unit = { _, _, _ -> },
        onLost: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onUnavailable: (ConnectivityManager.NetworkCallback) -> Unit = {},
    ): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createLocationAwareNetworkCallback(onAvailable, onCapabilitiesChanged, onLost, onUnavailable)
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable(this, network)
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    onCapabilitiesChanged(this, network, networkCapabilities)
                override fun onLost(network: Network) = onLost(this, network)
                override fun onUnavailable() = onUnavailable(this)
            }
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createLocationAwareNetworkCallback(
        onAvailable: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onCapabilitiesChanged: (ConnectivityManager.NetworkCallback, Network, NetworkCapabilities) -> Unit,
        onLost: (ConnectivityManager.NetworkCallback, Network) -> Unit,
        onUnavailable: (ConnectivityManager.NetworkCallback) -> Unit,
    ): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = onAvailable(this, network)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                onCapabilitiesChanged(this, network, networkCapabilities)
            override fun onLost(network: Network) = onLost(this, network)
            override fun onUnavailable() = onUnavailable(this)
        }

    @Volatile private var linkLogged = false

    private fun logLinkOnce(network: Network, deliveredCaps: NetworkCapabilities? = null) {
        if (linkLogged) return
        val cm = this.cm ?: return
        try {
            val caps = deliveredCaps ?: cm.getNetworkCapabilities(network) ?: return
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

    private fun scheduleRejoin() {
        if (!active) return
        rejoinAttempts++
        // Before the first successful join, never use the near-instant retry — that cancels the
        // system device picker mid-tap ("app canceled the request to choose a device").
        // After firstDelivered, first attempt is near-instant to beat home-Wi‑Fi / dash timeout.
        val delay = when {
            !firstDelivered -> FIRST_JOIN_RETRY_MS
            rejoinAttempts <= 1 -> REJOIN_FAST_MS
            else -> minOf(REJOIN_BASE_MS * (rejoinAttempts - 1), REJOIN_MAX_MS)
        }
        retryScheduler.schedule(delay)
    }

    /** กดเริ่มซ้ำแล้วเร่งเฉพาะการลองที่รออยู่ ไม่ยกเลิกตัวเลือก Wi-Fi ที่กำลังเปิด */
    internal fun retryPendingJoinNow(): Boolean = retryScheduler.retryNow()

    /**
     * Best-effort "is the bike's hotspot nearby?" check for auto-connect. Deliberately biased toward
     * `null` ("unknown → try anyway"): a false "not in range" is worse than a wasted connect attempt.
     *  - `true`  : the SSID is in a fresh Wi-Fi scan (or we're already on it) → connect.
     *  - `false` : we have FRESH scan results and the SSID is not among them → the bike really is away.
     *  - `null`  : we can't be sure (Wi-Fi off, no location permission, empty/stale scan cache) →
     *              the caller should attempt anyway.
     *
     * Reads cached scan results and only trusts "absent" when at least one result is recent (Android
     * throttles active scans, so a stale cache must not be read as "bike gone"). Also fires a
     * best-effort [WifiManager.startScan] to refresh the cache for the next check.
     */
    fun isSsidInRange(context: Context, ssid: String): Boolean? {
        if (ssid.isBlank()) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        if (!wm.isWifiEnabled) return null
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return null

        // Already associated with the bike's AP counts as in range.
        val connected = wm.connectionInfo?.ssid?.trim('"')
        if (connected != null && connected.equals(ssid, ignoreCase = true)) return true

        val results = try { wm.scanResults } catch (_: SecurityException) { null } ?: return null
        if (results.isEmpty()) return null

        // ScanResult.timestamp is microseconds since boot when the AP was last seen.
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val fresh = results.filter { it.timestamp > 0 && nowUs - it.timestamp < FRESH_SCAN_US }
        if (fresh.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        if (results.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        // Not found: only a confident "no" if we actually have fresh evidence; else unknown.
        return if (fresh.isNotEmpty()) false else null
    }

    private const val FRESH_SCAN_US = 30_000_000L  // 30s

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
        val cm = this.cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        unbindProcess(cm)
        log("Wi-Fi kept after disconnect (dash clock) — process unbound, SoftAP still associated")
    }

    fun leave(context: Context, log: (String) -> Unit) {
        active = false
        retryScheduler.cancel()
        handler.removeCallbacksAndMessages(null)
        val cm = this.cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        generalCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        generalCallback = null
        networkState.reset()
        linkLogged = false
        unbindProcess(cm)
        log("Wi-Fi released")
    }

    /**
     * Clear process→Network binding so loopback / cellular sockets work again.
     * Safe to call when already unbound. Used after bike Wi‑Fi loss and before AA :5288 bind.
     */
    fun unbindProcess(cmIn: ConnectivityManager? = null, context: Context? = null) {
        val cm = cmIn
            ?: this.cm
            ?: context?.applicationContext
                ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        try {
            cm.bindProcessToNetwork(null)
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
     * interfaces (OEM Secure Wi‑Fi stubs, parked clients) used to trip false "VPN blocking" errors
     * whenever a bike probe timed out for any reason — including while Maps was starting.
     *
     * Kill-switches still surface via [isVpnBindBlocked] / [testBikeSocketBind] (EPERM).
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Short description of VPN networks for Logs (empty when none). */
    fun vpnNetworksSummary(context: Context): String {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.mapNotNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
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
     *
     * Requires a **live internet VPN** ([isVpnActive]): bare EPERM / "Operation not permitted"
     * also shows up when the bike [Network] is stale after Map↔AA toggles or a Wi‑Fi blip, and that
     * must not pop the kill-switch dialog.
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
