package com.fsaint.androidagent.skills

interface SkillToolRunner { fun run(declarativeFile: String, content: ByteArray): Boolean }

sealed interface SkillDryRunResult {
    data class Success(val executedFiles: Int) : SkillDryRunResult
    data class Rejected(val failure: SkillValidationFailure, val detail: String) : SkillDryRunResult
}

class SkillUpdateService(private val installer: SkillInstaller, private val runner: SkillToolRunner) {
    fun dryRun(packageData: SkillPackage): SkillDryRunResult {
        val validation = SkillPackageValidator().validate(packageData)
        if (validation !is SkillValidationResult.Valid) {
            val invalid = validation as SkillValidationResult.Invalid
            return SkillDryRunResult.Rejected(invalid.failure, invalid.detail)
        }
        val tests = validation.files.filterKeys { it.startsWith("tests/") || it.startsWith("examples/") }
        if (tests.any { !runner.run(it.key, it.value) }) return SkillDryRunResult.Rejected(SkillValidationFailure.MALFORMED_MANIFEST, "dry run failed")
        return SkillDryRunResult.Success(tests.size)
    }
    fun update(packageData: SkillPackage): SkillInstallResult = installer.update(packageData)
}

class RecordingSkillToolRunner : SkillToolRunner {
    var calls: Int = 0
        private set
    override fun run(declarativeFile: String, content: ByteArray): Boolean { calls++; return true }
}
