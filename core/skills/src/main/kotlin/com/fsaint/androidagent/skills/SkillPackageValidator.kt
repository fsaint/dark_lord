package com.fsaint.androidagent.skills

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class SkillPackageValidator(
    private val maxFileBytes: Long = 256 * 1024,
    private val maxArchiveBytes: Long = 2 * 1024 * 1024,
) {
    fun validate(packageData: SkillPackage): SkillValidationResult = validate(packageData.archive.bytes(), packageData.expectedSha256)

    fun validate(archive: ByteArray, expectedSha256: String? = null): SkillValidationResult {
        if (archive.size > maxArchiveBytes) return invalid(SkillValidationFailure.FILE_TOO_LARGE, "archive")
        val actualHash = MessageDigest.getInstance("SHA-256").digest(archive).hex()
        if (expectedSha256 != null && !actualHash.equals(expectedSha256, ignoreCase = true)) {
            return invalid(SkillValidationFailure.HASH_MISMATCH, "sha256")
        }
        val files = linkedMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (entry.isDirectory || path.startsWith("/") || path.split('/').any { it == ".." || it.isEmpty() })
                        return invalid(SkillValidationFailure.UNSAFE_PATH, path)
                    if (!isAllowed(path)) return invalid(if (path.substringAfterLast('.').lowercase() in executableExtensions) SkillValidationFailure.EXECUTABLE_PAYLOAD else SkillValidationFailure.UNSUPPORTED_FILE, path)
                    val data = zip.readBounded(maxFileBytes) ?: return invalid(SkillValidationFailure.FILE_TOO_LARGE, path)
                    files[path] = data
                }
            }
        } catch (_: Exception) { return invalid(SkillValidationFailure.MALFORMED_MANIFEST, "archive") }
        if (!files.containsKey("manifest.yaml") || !files.containsKey("instructions.md"))
            return invalid(SkillValidationFailure.MISSING_REQUIRED_FILE, "manifest.yaml or instructions.md")
        if (files.getValue("instructions.md").isEmpty())
            return invalid(SkillValidationFailure.MALFORMED_MANIFEST, "instructions.md")
        val manifest = parseManifest(files.getValue("manifest.yaml")) ?: return invalid(SkillValidationFailure.MALFORMED_MANIFEST, "manifest.yaml")
        return SkillValidationResult.Valid(manifest, files.toMap(), actualHash)
    }

    private fun isAllowed(path: String) = path == "manifest.yaml" || path == "instructions.md" || path.startsWith("examples/") || path.startsWith("tests/")
    private fun parseManifest(bytes: ByteArray): SkillManifest? {
        val values = linkedMapOf<String, String>()
        for (line in bytes.toString(Charsets.UTF_8).lineSequence()) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val separator = line.indexOf(':')
            if (separator <= 0 || line.take(separator).any { it.isWhitespace() }) return null
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1).trim().removeSurrounding("\"")
            if (value.isEmpty() || value.startsWith("[") || value.startsWith("{")) return null
            values[key] = value
        }
        val id = values["id"] ?: return null
        val version = values["version"] ?: return null
        val description = values["description"] ?: return null
        if (!id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")) || !version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) return null
        return SkillManifest(id, version, description)
    }
    private fun invalid(failure: SkillValidationFailure, detail: String) = SkillValidationResult.Invalid(failure, detail)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun java.util.zip.ZipInputStream.readBounded(max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
        while (true) { val count = read(buffer); if (count < 0) break; total += count; if (total > max) return null; out.write(buffer, 0, count) }
        return out.toByteArray()
    }
    private companion object { val executableExtensions = setOf("sh", "exe", "bin", "apk", "dex", "so", "class", "jar") }
}
