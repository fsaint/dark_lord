package com.fsaint.androidagent.skills

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillPackageValidatorTest {
    @Test fun `accepts valid declarative package`() {
        val result = SkillPackageValidator().validate(zipOf(
            "manifest.yaml" to "id: weather\nversion: 1.0.0\ndescription: Weather helper\n",
            "instructions.md" to "Ask for a city."
        ))
        assertIs<SkillValidationResult.Valid>(result)
        assertEquals("weather", result.manifest.id)
    }

    @Test fun `rejects archive hash mismatch`() {
        val result = SkillPackageValidator().validate(zipOf("manifest.yaml" to manifest()), expectedSha256 = "00")
        assertIs<SkillValidationResult.Invalid>(result)
        assertEquals(SkillValidationFailure.HASH_MISMATCH, result.failure)
    }

    @Test fun `rejects traversal path`() {
        val result = SkillPackageValidator().validate(zipOf("manifest.yaml" to manifest(), "../evil" to "x"))
        assertIs<SkillValidationResult.Invalid>(result)
        assertEquals(SkillValidationFailure.UNSAFE_PATH, result.failure)
    }

    @Test fun `rejects executable payload`() {
        val result = SkillPackageValidator().validate(zipOf("manifest.yaml" to manifest(), "run.sh" to "#!/bin/sh"))
        assertIs<SkillValidationResult.Invalid>(result)
        assertEquals(SkillValidationFailure.EXECUTABLE_PAYLOAD, result.failure)
    }

    @Test fun `rejects missing required files`() {
        val result = SkillPackageValidator().validate(zipOf("manifest.yaml" to manifest()))
        assertIs<SkillValidationResult.Invalid>(result)
        assertEquals(SkillValidationFailure.MISSING_REQUIRED_FILE, result.failure)
    }

    @Test fun `rejects oversized file`() {
        val result = SkillPackageValidator(maxFileBytes = 8).validate(zipOf(
            "manifest.yaml" to manifest(), "instructions.md" to "123456789"
        ))
        assertIs<SkillValidationResult.Invalid>(result)
        assertEquals(SkillValidationFailure.FILE_TOO_LARGE, result.failure)
    }

    @Test fun `rejects malformed manifest`() {
        val result = SkillPackageValidator().validate(zipOf(
            "manifest.yaml" to "id: [broken\nversion: nope\ndescription: bad",
            "instructions.md" to "x"
        ))
        assertIs<SkillValidationResult.Invalid>(result)
        assertEquals(SkillValidationFailure.MALFORMED_MANIFEST, result.failure)
    }

    private fun manifest() = "id: weather\nversion: 1.0.0\ndescription: Weather helper\n"
    private fun zipOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, body) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
}
