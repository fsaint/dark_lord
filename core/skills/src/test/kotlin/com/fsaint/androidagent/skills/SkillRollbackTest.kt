package com.fsaint.androidagent.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkillRollbackTest {
    @Test fun `failed update leaves prior active version unchanged`() {
        val store = InMemorySkillStore()
        val installer = SkillInstaller(store, SkillPackageValidator())
        assertIs<SkillInstallResult.Installed>(installer.install(validPackage("1.0.0")))
        val failed = installer.update(validPackage("2.0.0", instructions = ""))
        assertIs<SkillInstallResult.Rejected>(failed)
        assertEquals("1.0.0", store.activeVersion("weather"))
    }

    @Test fun `successful update atomically switches active version`() {
        val store = InMemorySkillStore()
        val installer = SkillInstaller(store, SkillPackageValidator())
        installer.install(validPackage("1.0.0"))
        assertIs<SkillInstallResult.Installed>(installer.update(validPackage("2.0.0")))
        assertEquals("2.0.0", store.activeVersion("weather"))
        assertEquals("1.0.0", store.rollbackVersion("weather"))
    }

    @Test fun `dry run executes examples through fake tool runner without activation`() {
        val store = InMemorySkillStore()
        val runner = RecordingSkillToolRunner()
        val service = SkillUpdateService(SkillInstaller(store, SkillPackageValidator()), runner)
        val result = service.dryRun(validPackage("1.0.0"))
        assertIs<SkillDryRunResult.Success>(result)
        assertEquals(1, runner.calls)
        assertEquals(null, store.activeVersion("weather"))
    }

    private fun validPackage(version: String, instructions: String = "Use weather tool") = SkillPackage(
        archive = SkillArchive(mapOf(
            "manifest.yaml" to "id: weather\nversion: $version\ndescription: helper\n",
            "instructions.md" to instructions,
            "tests/check.yaml" to "tool: weather"
        )), expectedSha256 = null
    )
}
