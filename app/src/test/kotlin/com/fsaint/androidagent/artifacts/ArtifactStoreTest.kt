package com.fsaint.androidagent.artifacts

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactStoreTest {
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
}
