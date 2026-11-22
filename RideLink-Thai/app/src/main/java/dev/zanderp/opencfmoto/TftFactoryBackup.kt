package dev.zanderp.opencfmoto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/** Stores originals and checks restore eligibility before handing bytes to the caller. */
internal class TftFactoryBackup(private val directory: File) {
    data class Entry(val name: String, val sha256: String)
    enum class CopyStatus { READY, NO_ORIGINAL, INVALID_ORIGINAL, FAILED }
    enum class RestoreStatus { READY, NO_BACKUP, TARGET_MISMATCH, INVALID_BACKUP, FAILED }
    enum class WriteOutcome {
        VERIFIED,
        NO_BACKUP,
        TARGET_MISMATCH,
        INVALID_BACKUP,
        STAGE_FAILED,
        COMMIT_FAILED,
        READBACK_MISMATCH,
    }

    interface BikeRestoreTarget {
        fun observeConnected(): BikeLauncher?
        fun stageAndCommit(entry: Entry, source: InputStream): Boolean
        fun openReadback(): InputStream
        fun rollbackStaging() {}
    }

    data class BikeLauncher(val mac: String, val model: String, val firmware: String, val size: Long) {
        init {
            require(macPattern.matches(mac))
            require(model.isNotBlank() && model.length <= 64)
            require(firmware.isNotBlank() && firmware.length <= 128)
            require(size > 0)
        }

        val normalizedMac: String get() = mac.lowercase()
    }

    private val saved = File(directory, "tft-factory-original")

    fun entry(): Entry? = synchronized(lock) {
        if (!saved.exists()) return null
        readMetadata(saved) { readEntry() }
    }

    fun importOriginal(name: String?, openSource: () -> InputStream): Entry = synchronized(lock) {
        saveOriginal(saved, name, openSource = openSource)
    }

    /** The factory hash must come from a trusted reference for this model and firmware. */
    fun captureFactoryOriginal(
        bike: BikeLauncher,
        expectedFactorySha256: String,
        openSource: () -> InputStream,
    ): Entry = synchronized(lock) {
        require(bike.model == "150X")
        require(sha256Pattern.matches(expectedFactorySha256))
        saveOriginal(bikeSlot(bike), "Launcher", bike, expectedFactorySha256, openSource)
    }

    /** The caller supplies a fresh observation of the connected instrument cluster. */
    fun prepareBikeRestore(connected: BikeLauncher, stage: (Entry, InputStream) -> Unit): RestoreStatus =
        synchronized(lock) {
            if (connected.model != "150X") return RestoreStatus.TARGET_MISMATCH
            val slot = bikeSlot(connected)
            val original = File(slot, "original")
            val backup = try {
                if (!slot.exists()) return RestoreStatus.NO_BACKUP
                val (stored, target) = readMetadata(slot) {
                    readEntry() to BikeLauncher(readUTF(), readUTF(), readUTF(), readLong())
                }
                if (target != connected.copy(mac = connected.normalizedMac)) return RestoreStatus.TARGET_MISMATCH
                if (original.length() != target.size || original.sha256() != stored.sha256) {
                    return RestoreStatus.INVALID_BACKUP
                }
                stored
            } catch (_: IOException) {
                return RestoreStatus.INVALID_BACKUP
            } catch (_: IllegalArgumentException) {
                return RestoreStatus.INVALID_BACKUP
            } catch (_: SecurityException) {
                return RestoreStatus.FAILED
            }
            try {
                original.inputStream().use { stage(backup, it) }
                RestoreStatus.READY
            } catch (_: IOException) {
                RestoreStatus.FAILED
            } catch (_: SecurityException) {
                RestoreStatus.FAILED
            }
        }

    /**
     * Restores the bike's own verified factory original without user file selection, re-checks the
     * target before staging/commit, verifies post-write readback SHA-256 and exact length, and rolls
     * back staging on interruption or verification failure.
     */
    fun executeVerifiedRestore(initialObservation: BikeLauncher, target: BikeRestoreTarget): WriteOutcome =
        synchronized(lock) {
            var stagedEntry: Entry? = null
            var preflightPassed = false
            val prepStatus = prepareBikeRestore(initialObservation) { entry, stream ->
                val current = target.observeConnected()
                if (current == null || current.copy(mac = current.normalizedMac) != initialObservation.copy(mac = initialObservation.normalizedMac)) {
                    preflightPassed = false
                    return@prepareBikeRestore
                }
                preflightPassed = true
                if (!target.stageAndCommit(entry, stream)) {
                    throw IOException("Commit failed")
                }
                stagedEntry = entry
            }
            when (prepStatus) {
                RestoreStatus.NO_BACKUP -> return WriteOutcome.NO_BACKUP
                RestoreStatus.TARGET_MISMATCH -> return WriteOutcome.TARGET_MISMATCH
                RestoreStatus.INVALID_BACKUP -> return WriteOutcome.INVALID_BACKUP
                RestoreStatus.FAILED -> {
                    runCatching { target.rollbackStaging() }
                    return if (preflightPassed) WriteOutcome.COMMIT_FAILED else WriteOutcome.STAGE_FAILED
                }
                RestoreStatus.READY -> {
                    if (!preflightPassed) return WriteOutcome.TARGET_MISMATCH
                    val expected = stagedEntry ?: return WriteOutcome.STAGE_FAILED
                    return try {
                        val after = target.observeConnected()
                        if (after == null || after.copy(mac = after.normalizedMac) != initialObservation.copy(mac = initialObservation.normalizedMac)) {
                            runCatching { target.rollbackStaging() }
                            WriteOutcome.TARGET_MISMATCH
                        } else {
                            val digest = MessageDigest.getInstance("SHA-256")
                            val readbackBytes = DigestInputStream(target.openReadback(), digest).use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var total = 0L
                                var read = input.read(buffer)
                                while (read != -1) {
                                    total += read
                                    if (total > initialObservation.size) break
                                    read = input.read(buffer)
                                }
                                total
                            }
                            if (readbackBytes == initialObservation.size && digest.hexDigest().equals(expected.sha256, ignoreCase = true)) {
                                WriteOutcome.VERIFIED
                            } else {
                                runCatching { target.rollbackStaging() }
                                WriteOutcome.READBACK_MISMATCH
                            }
                        }
                    } catch (_: IOException) {
                        runCatching { target.rollbackStaging() }
                        WriteOutcome.READBACK_MISMATCH
                    } catch (_: SecurityException) {
                        runCatching { target.rollbackStaging() }
                        WriteOutcome.READBACK_MISMATCH
                    }
                }
            }
        }

    private fun bikeSlot(bike: BikeLauncher) =
        File(directory, "tft-factory-${bike.normalizedMac.replace(":", "")}")

    private fun <T> readMetadata(slot: File, read: DataInputStream.() -> T): T =
        DataInputStream(File(slot, "metadata").inputStream()).use(read)

    private fun DataInputStream.readEntry() = Entry(readUTF(), readUTF())

    private fun saveOriginal(
        destination: File,
        name: String?,
        bike: BikeLauncher? = null,
        expectedSha256: String? = null,
        openSource: () -> InputStream,
    ): Entry {
        if (destination.exists()) throw IOException("Original backup already exists")
        val staging = Files.createTempDirectory(directory.toPath(), "tft-original-").toFile()
        val original = File(staging, "original")
        val metadata = File(staging, "metadata")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val length = DigestInputStream(openSource(), digest).use { input ->
                original.outputStream().use { output ->
                    val length = if (bike == null) input.copyTo(output) else input.copyExactTo(output, bike.size)
                    output.fd.sync()
                    length
                }
            }
            val sha256 = digest.hexDigest()
            if (length == 0L || (expectedSha256 != null && !sha256.equals(expectedSha256, ignoreCase = true)) ||
                original.sha256() != sha256
            ) throw IOException("Original backup failed verification")
            val fileName = name?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.filterNot(Char::isISOControl)?.take(160)?.takeUnless { it.isBlank() || it == "." || it == ".." }
                ?: "zontes-150x-original.bin"
            metadata.outputStream().use { file ->
                val output = DataOutputStream(file)
                output.writeUTF(fileName)
                output.writeUTF(sha256)
                if (bike != null) {
                    output.writeUTF(bike.normalizedMac)
                    output.writeUTF(bike.model)
                    output.writeUTF(bike.firmware)
                    output.writeLong(bike.size)
                }
                file.fd.sync()
            }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return Entry(fileName, sha256)
        } finally {
            original.delete()
            metadata.delete()
            staging.delete()
        }
    }

    private fun InputStream.copyExactTo(output: FileOutputStream, size: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Launcher is shorter than expected")
            output.write(buffer, 0, count)
            remaining -= count
        }
        if (read() != -1) throw IOException("Launcher is larger than expected")
        return size
    }

    /** Calls the publisher only after the app-private original has passed verification. */
    fun prepareCopy(publish: (Entry, InputStream) -> Unit): CopyStatus = synchronized(lock) {
        val original = File(saved, "original")
        val backup = try {
            val stored = entry() ?: return CopyStatus.NO_ORIGINAL
            if (original.sha256() != stored.sha256) return CopyStatus.INVALID_ORIGINAL
            stored
        } catch (_: IOException) {
            return CopyStatus.INVALID_ORIGINAL
        } catch (_: SecurityException) {
            return CopyStatus.FAILED
        }
        try {
            original.inputStream().use { publish(backup, it) }
            CopyStatus.READY
        } catch (_: Exception) {
            CopyStatus.FAILED
        }
    }

    fun matchesOriginal(openCopy: () -> InputStream): Boolean {
        val backup = entry() ?: return false
        return openCopy().sha256() == backup.sha256
    }

    private fun File.sha256() = inputStream().sha256()

    private fun InputStream.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.hexDigest()
    }

    private fun MessageDigest.hexDigest() = digest().joinToString("") { "%02x".format(it) }

    private companion object {
        val lock = Any()
        val macPattern = Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
        val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    }
}
