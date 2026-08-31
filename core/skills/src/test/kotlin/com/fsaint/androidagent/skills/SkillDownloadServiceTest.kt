package com.fsaint.androidagent.skills

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkillDownloadServiceTest {
    private val owner = Principal("owner", null, PrincipalRole.OWNER)
    private val unknown = Principal("unknown", null, PrincipalRole.UNKNOWN)

    @Test
    fun rejectsNonHttpsAndNonOwnerBeforeDownload() {
        var calls = 0
        val service = service(byteArrayOf()) { calls++ }
        assertIs<SkillManagementResult.Rejected>(service.install(unknown, "https://example.com/skill.zip"))
        assertIs<SkillManagementResult.Rejected>(service.install(owner, "http://example.com/skill.zip"))
        assertEquals(0, calls)
    }

    @Test
    fun validatesDryRunBeforeActivation() {
        val archive = SkillArchive(mapOf(
            "manifest.yaml" to "id: weather\nversion: 1.0.0\ndescription: Weather\n",
            "instructions.md" to "Use weather data",
        )).bytes()
        val result = service(archive).install(owner, "https://example.com/weather.zip")
        assertEquals(SkillManagementResult.Installed("weather", "1.0.0"), result)
    }

    private fun service(archive: ByteArray, onDownload: () -> Unit = {}): OwnerSkillDownloadService {
        val store = InMemorySkillStore()
        val installer = SkillInstaller(store, SkillPackageValidator())
        return OwnerSkillDownloadService(
            downloader = object : SkillArchiveDownloader { override fun download(url: String): ByteArray { onDownload(); return archive } },
            installer = installer,
            updater = SkillUpdateService(installer, RecordingSkillToolRunner()),
            installed = { setOfNotNull("weather".takeIf { store.activeVersion(it) != null }) },
            remove = { false },
        )
    }
}
