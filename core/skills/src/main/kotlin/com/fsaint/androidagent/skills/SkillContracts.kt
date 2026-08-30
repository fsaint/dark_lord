package com.fsaint.androidagent.skills

data class SkillManifest(val id: String, val version: String, val description: String)

data class SkillArchive(val files: Map<String, String>) {
    fun bytes(): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.ZipOutputStream(out).use { zip -> files.toSortedMap().forEach { (name, body) ->
            zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
}

data class SkillPackage(val archive: SkillArchive, val expectedSha256: String? = null)

enum class SkillValidationFailure {
    HASH_MISMATCH, UNSAFE_PATH, EXECUTABLE_PAYLOAD, MISSING_REQUIRED_FILE,
    FILE_TOO_LARGE, MALFORMED_MANIFEST, UNSUPPORTED_FILE
}

sealed interface SkillValidationResult {
    data class Valid(val manifest: SkillManifest, val files: Map<String, ByteArray>, val sha256: String) : SkillValidationResult
    data class Invalid(val failure: SkillValidationFailure, val detail: String) : SkillValidationResult
}
