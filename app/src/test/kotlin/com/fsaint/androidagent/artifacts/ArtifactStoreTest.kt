package com.fsaint.androidagent.artifacts

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactStoreTest {
    @Test fun corruptMetadataCannotLoadAnArtifactOrCrashStartup() {
        val dir = Files.createTempDirectory("artifact-corrupt").toFile()
        val info = ArtifactStore(dir).store(byteArrayOf(1), "image/jpeg")
        java.io.File(dir, "${info.id}.meta").writeText("size=not-a-number")
        assertEquals(null, ArtifactStore(dir).read(info.id))
    }
    @Test fun startupReconciliationReleasesUncommittedChatPinsButKeepsCommittedAndOtherPins() {
        val dir = Files.createTempDirectory("artifact-reconcile").toFile()
        var now = 0L
        val store = ArtifactStore(dir, ttlMillis = 10, clock = { now })
        val orphan = store.store(byteArrayOf(1), "image/jpeg")
        val kept = store.store(byteArrayOf(2), "image/jpeg")
        val other = store.store(byteArrayOf(3), "image/jpeg")
        store.retain(orphan.id, "chat:uncommitted")
        store.retain(kept.id, "chat:committed")
        store.retain(other.id, "other-consumer")
        now = 100
        val reopened = ArtifactStore(dir, ttlMillis = 10, clock = { now })
        reopened.reconcileChatReferences(mapOf(kept.id to setOf("chat:committed")))
        assertEquals(null, reopened.read(orphan.id))
        assertContentEquals(byteArrayOf(2), reopened.read(kept.id)?.second)
        assertContentEquals(byteArrayOf(3), reopened.read(other.id)?.second)
    }
    @Test fun retainedPhotoOutlivesExpiryAndReleaseRestoresCleanup() {
        val dir = Files.createTempDirectory("artifact-retain").toFile()
        var now = 0L
        val first = ArtifactStore(dir, ttlMillis = 10, clock = { now })
        val photo = first.store(byteArrayOf(7), "image/jpeg")
        first.retain(photo.id, "chat:r1")
        now = 100
        val reopened = ArtifactStore(dir, ttlMillis = 10, clock = { now })
        assertContentEquals(byteArrayOf(7), reopened.read(photo.id)?.second)
        reopened.release(photo.id, "chat:r1")
        reopened.cleanup()
        assertEquals(null, reopened.read(photo.id))
    }
    @Test fun storageFullDoesNotEvictRetainedPhoto() {
        val dir = Files.createTempDirectory("artifact-quota").toFile()
        val store = ArtifactStore(dir, maxTotalBytes = 3)
        val photo = store.store(byteArrayOf(1,2,3), "image/jpeg")
        store.retain(photo.id, "chat:r1")
        assertFailsWith<IllegalArgumentException> { store.store(byteArrayOf(4), "image/jpeg") }
        assertContentEquals(byteArrayOf(1,2,3), store.read(photo.id)?.second)
    }
    @Test fun metadataAndBytesSurviveRecreation() {
        val dir = Files.createTempDirectory("artifact-reopen").toFile()
        val info = ArtifactStore(dir).store(byteArrayOf(4, 5), "image/jpeg")
        assertContentEquals(byteArrayOf(4, 5), ArtifactStore(dir).read(info.id)?.second)
    }
    @Test fun storesOpaqueMetadataAndBytes() {
        val store = ArtifactStore(Files.createTempDirectory("artifact-test").toFile())
        val info = store.store(byteArrayOf(1, 2, 3), "image/jpeg")
        assertEquals("image/jpeg", info.mimeType)
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(info.id)!!.second)
        assert(!info.id.contains("/"))
    }

    @Test fun rejectsUnsupportedTypes() {
        assertFailsWith<IllegalArgumentException> { ArtifactStore(Files.createTempDirectory("artifact-test").toFile()).store(byteArrayOf(1), "application/x-sh") }
    }

    @Test fun findsLatestImageWhenModelOmitsArtifactId() {
        val store = ArtifactStore(Files.createTempDirectory("artifact-test").toFile())
        store.store(byteArrayOf(1), "text/plain")
        val image = store.store(byteArrayOf(2), "image/jpeg")

        assertEquals(image.id, store.latest("image/")!!.first.id)
    }
}
