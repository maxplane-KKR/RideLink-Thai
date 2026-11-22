package dev.zanderp.opencfmoto

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import dev.zanderp.opencfmoto.TftFactoryBackup.CopyStatus
import dev.zanderp.opencfmoto.TftFactoryBackup.BikeLauncher
import dev.zanderp.opencfmoto.TftFactoryBackup.RestoreStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TftFactoryBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bike = BikeLauncher("02:00:00:00:00:AB", "150X", "test-fw-1", 3)
    private val originalBytes = "abc".toByteArray()
    private val factorySha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun originalSurvivesReopenAndCannotBeReplaced() {
        val source = "abc".toByteArray()
        val first = TftFactoryBackup(temporary.root)
        val entry = first.importOriginal("original.bin") { source.inputStream() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", entry.sha256)

        val reopened = TftFactoryBackup(temporary.root)
        assertThrows(IOException::class.java) {
            reopened.importOriginal("another.bin") { error("Must not open a replacement") }
        }
        assertEquals(entry, reopened.entry())
        val exported = ByteArrayOutputStream()
        assertEquals(CopyStatus.READY, reopened.prepareCopy { entry, input ->
            assertEquals("original.bin", entry.name)
            input.copyTo(exported)
        })
        assertArrayEquals(source, exported.toByteArray())
        assertTrue(reopened.matchesOriginal { exported.toByteArray().inputStream() })
        assertFalse(reopened.matchesOriginal { byteArrayOf(4, 5, 6).inputStream() })
    }

    @Test fun failedImportDoesNotOccupyTheBackupSlot() {
        val store = TftFactoryBackup(temporary.root)
        assertThrows(IOException::class.java) {
            store.importOriginal("unreadable.bin") {
                object : InputStream() {
                    private var remaining = 1
                    override fun read(): Int {
                        if (remaining-- == 1) return 42
                        throw IOException("Interrupted source")
                    }
                }
            }
        }
        assertThrows(IOException::class.java) {
            store.importOriginal("empty.bin") { byteArrayOf().inputStream() }
        }
        assertNull(store.entry())
        assertEquals(0, temporary.root.listFiles()!!.size)
        assertEquals("original.bin", store.importOriginal("original.bin") { byteArrayOf(1).inputStream() }.name)
    }

    @Test fun corruptedOriginalIsRejectedBeforePublishing() {
        val store = TftFactoryBackup(temporary.root)
        store.importOriginal("original.bin") { byteArrayOf(1, 2, 3).inputStream() }
        File(temporary.root, "tft-factory-original/original").writeBytes(byteArrayOf(4, 5, 6))
        assertEquals(CopyStatus.INVALID_ORIGINAL, store.prepareCopy { _, _ ->
            error("Corrupted bytes must never be published")
        })
    }

    @Test fun missingOriginalAndFailedPublishReturnDifferentStatuses() {
        val store = TftFactoryBackup(temporary.root)
        assertEquals(CopyStatus.NO_ORIGINAL, store.prepareCopy { _, _ ->
            error("A missing backup must never be published")
        })
        val entry = store.importOriginal("original.bin") { byteArrayOf(1, 2, 3).inputStream() }
        assertEquals(CopyStatus.FAILED, store.prepareCopy { _, _ ->
            throw IOException("Destination unavailable")
        })
        assertEquals(entry, store.entry())
        assertEquals(CopyStatus.READY, store.prepareCopy { _, input ->
            assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes())
        })
    }

    @Test fun verifiedBikeBackupSurvivesReopenAndCannotBeReplaced() {
        val store = TftFactoryBackup(temporary.root)
        val saved = store.captureFactoryOriginal(bike, factorySha256) { originalBytes.inputStream() }
        val reopened = TftFactoryBackup(temporary.root)
        val sameBike = bike.copy(mac = bike.mac.lowercase())
        assertThrows(IOException::class.java) {
            reopened.captureFactoryOriginal(sameBike, factorySha256) { error("Must not open the source again") }
        }
        assertEquals(RestoreStatus.READY, reopened.prepareBikeRestore(sameBike) { entry, stream ->
            assertEquals(saved, entry)
            assertArrayEquals(originalBytes, stream.readBytes())
        })
    }

    @Test fun firstCaptureRequiresExactSizeAndTrustedHash() {
        val store = TftFactoryBackup(temporary.root)
        for (bytes in listOf("ab", "abcd", "xyz")) {
            assertThrows(IOException::class.java) {
                store.captureFactoryOriginal(bike, factorySha256) { bytes.byteInputStream() }
            }
        }
        assertEquals(0, temporary.root.listFiles()!!.size)
        assertEquals(factorySha256, store.captureFactoryOriginal(bike, factorySha256) {
            originalBytes.inputStream()
        }.sha256)
    }

    @Test fun legacyFileDoesNotAuthorizeRestoreAndOtherTargetsAreRejected() {
        val store = TftFactoryBackup(temporary.root)
        store.importOriginal("old.bin") { originalBytes.inputStream() }
        val forbidden: (TftFactoryBackup.Entry, InputStream) -> Unit = { _, _ ->
            error("Ineligible backup must never reach staging")
        }
        assertEquals(RestoreStatus.NO_BACKUP, store.prepareBikeRestore(bike, forbidden))
        store.captureFactoryOriginal(bike, factorySha256) { originalBytes.inputStream() }
        assertEquals(RestoreStatus.NO_BACKUP, store.prepareBikeRestore(bike.copy(mac = "02:00:00:00:00:CD"), forbidden))
        assertEquals(RestoreStatus.TARGET_MISMATCH, store.prepareBikeRestore(bike.copy(model = "368G"), forbidden))
        assertEquals(RestoreStatus.TARGET_MISMATCH, store.prepareBikeRestore(bike.copy(firmware = "test-fw-2"), forbidden))
        assertEquals(RestoreStatus.TARGET_MISMATCH, store.prepareBikeRestore(bike.copy(size = 4), forbidden))
    }

    @Test fun stageFailureLeavesBackupIntactButCorruptedFileIsRejected() {
        val store = TftFactoryBackup(temporary.root)
        store.captureFactoryOriginal(bike, factorySha256) { originalBytes.inputStream() }
        assertEquals(RestoreStatus.FAILED, store.prepareBikeRestore(bike) { _, _ ->
            throw IOException("Stage unavailable")
        })
        assertEquals(RestoreStatus.READY, store.prepareBikeRestore(bike) { _, stream ->
            assertArrayEquals(originalBytes, stream.readBytes())
        })
        val slot = temporary.root.listFiles()!!.single()
        File(slot, "original").writeText("xyz")
        assertEquals(RestoreStatus.INVALID_BACKUP, store.prepareBikeRestore(bike) { _, _ ->
            error("Corrupted bytes must never reach staging")
        })
    }

    @Test fun verifiedRestoreChecksTargetReadsBackHashAndRollsBackOnInterruption() {
        val store = TftFactoryBackup(temporary.root)
        store.captureFactoryOriginal(bike, factorySha256) { originalBytes.inputStream() }

        var currentTarget: BikeLauncher? = bike
        var committedBytes: ByteArray? = null
        var rollbackCount = 0
        var failMidStream = false
        var corruptReadback = false

        val target = object : TftFactoryBackup.BikeRestoreTarget {
            override fun observeConnected(): BikeLauncher? = currentTarget

            override fun stageAndCommit(entry: TftFactoryBackup.Entry, source: InputStream): Boolean {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(1)
                var read = source.read(buf)
                while (read != -1) {
                    out.write(buf, 0, read)
                    if (failMidStream) throw IOException("Connection dropped mid-transfer")
                    read = source.read(buf)
                }
                committedBytes = out.toByteArray()
                return true
            }

            override fun openReadback(): InputStream {
                val bytes = committedBytes ?: throw IOException("No committed file")
                return (if (corruptReadback) "abd".toByteArray() else bytes).inputStream()
            }

            override fun rollbackStaging() {
                rollbackCount++
                committedBytes = null
            }
        }

        // 1. Target switched between initial check and staging
        currentTarget = bike.copy(mac = "02:00:00:00:00:EF")
        assertEquals(TftFactoryBackup.WriteOutcome.TARGET_MISMATCH, store.executeVerifiedRestore(bike, target))
        assertNull(committedBytes)
        assertEquals(0, rollbackCount)

        // 2. Connection dropped mid-stream -> rolls back staging and keeps local backup intact
        currentTarget = bike
        failMidStream = true
        assertEquals(TftFactoryBackup.WriteOutcome.COMMIT_FAILED, store.executeVerifiedRestore(bike, target))
        assertEquals(1, rollbackCount)
        assertNull(committedBytes)

        // 3. Readback SHA-256 mismatch -> rolls back and rejects verification
        failMidStream = false
        corruptReadback = true
        assertEquals(TftFactoryBackup.WriteOutcome.READBACK_MISMATCH, store.executeVerifiedRestore(bike, target))
        assertEquals(2, rollbackCount)
        assertNull(committedBytes)

        // 4. Full verified restore -> exact size and SHA-256 readback match
        corruptReadback = false
        assertEquals(TftFactoryBackup.WriteOutcome.VERIFIED, store.executeVerifiedRestore(bike, target))
        assertArrayEquals(originalBytes, committedBytes)
        assertEquals(2, rollbackCount)
    }
}
