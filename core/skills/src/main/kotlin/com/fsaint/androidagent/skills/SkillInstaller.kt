package com.fsaint.androidagent.skills

interface SkillStore {
    fun stage(manifest: SkillManifest, files: Map<String, ByteArray>, sha256: String): Boolean
    fun activate(skillId: String, version: String): Boolean
    fun activeVersion(skillId: String): String?
    fun rollbackVersion(skillId: String): String?
    fun recordAttempt(skillId: String, version: String, outcome: String)
}

sealed interface SkillInstallResult {
    data class Installed(val skillId: String, val version: String) : SkillInstallResult
    data class Rejected(val failure: SkillValidationFailure, val detail: String) : SkillInstallResult
}

class SkillInstaller(private val store: SkillStore, private val validator: SkillPackageValidator) {
    fun install(packageData: SkillPackage): SkillInstallResult = installValidated(packageData)
    fun installArchive(archive: ByteArray, expectedSha256: String? = null): SkillInstallResult =
        installValidated(validator.validate(archive, expectedSha256))
    fun update(packageData: SkillPackage): SkillInstallResult = installValidated(packageData)

    fun rollback(skillId: String): Boolean = store.rollbackVersion(skillId) != null

    private fun installValidated(packageData: SkillPackage): SkillInstallResult = installValidated(validator.validate(packageData))

    private fun installValidated(validation: SkillValidationResult): SkillInstallResult {
        val result = validation
        if (result !is SkillValidationResult.Valid) {
            val invalid = result as SkillValidationResult.Invalid
            return SkillInstallResult.Rejected(invalid.failure, invalid.detail)
        }
        synchronized(store) {
            val staged = store.stage(result.manifest, result.files, result.sha256)
            if (!staged || !store.activate(result.manifest.id, result.manifest.version)) {
                store.recordAttempt(result.manifest.id, result.manifest.version, "FAILED")
                return SkillInstallResult.Rejected(SkillValidationFailure.MALFORMED_MANIFEST, "activation failed")
            }
            store.recordAttempt(result.manifest.id, result.manifest.version, "ACTIVATED")
            return SkillInstallResult.Installed(result.manifest.id, result.manifest.version)
        }
    }
}

class InMemorySkillStore : SkillStore {
    private val versions = mutableMapOf<String, MutableMap<String, Map<String, ByteArray>>>()
    private val active = mutableMapOf<String, String>()
    private val rollback = mutableMapOf<String, String>()
    val attempts = mutableListOf<String>()

    override fun stage(manifest: SkillManifest, files: Map<String, ByteArray>, sha256: String): Boolean {
        versions.getOrPut(manifest.id) { mutableMapOf() }[manifest.version] = files.mapValues { it.value.copyOf() }
        return true
    }
    override fun activate(skillId: String, version: String): Boolean {
        if (versions[skillId]?.containsKey(version) != true) return false
        active[skillId]?.let { rollback[skillId] = it }
        active[skillId] = version
        return true
    }
    override fun activeVersion(skillId: String) = active[skillId]
    override fun rollbackVersion(skillId: String): String? {
        val prior = rollback[skillId] ?: return null
        val current = active[skillId]
        active[skillId] = prior
        if (current != null) rollback[skillId] = current else rollback.remove(skillId)
        return prior
    }
    override fun recordAttempt(skillId: String, version: String, outcome: String) { attempts += "$skillId:$version:$outcome" }
}
