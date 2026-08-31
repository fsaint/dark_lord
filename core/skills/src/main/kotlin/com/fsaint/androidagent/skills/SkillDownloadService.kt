package com.fsaint.androidagent.skills

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import java.net.URI

interface SkillArchiveDownloader { fun download(url: String): ByteArray }

sealed interface SkillManagementResult {
    data class Installed(val id: String, val version: String) : SkillManagementResult
    data class Rejected(val reason: String) : SkillManagementResult
}

/** Owner-gated boundary for fetching and activating declarative skills. */
class OwnerSkillDownloadService(
    private val downloader: SkillArchiveDownloader,
    private val installer: SkillInstaller,
    private val updater: SkillUpdateService,
    private val installed: () -> Set<String>,
    private val remove: (String) -> Boolean,
) {
    fun install(owner: Principal, url: String, sha256: String? = null): SkillManagementResult {
        if (owner.role != PrincipalRole.OWNER) return SkillManagementResult.Rejected("owner authorization required")
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return SkillManagementResult.Rejected("invalid URL")
        if (uri.scheme != "https" || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return SkillManagementResult.Rejected("HTTPS URL without credentials, query, or fragment required")
        }
        val archive = runCatching { downloader.download(url) }.getOrElse { return SkillManagementResult.Rejected("download failed") }
        val dryRun = updater.dryRunBytes(archive, sha256)
        if (dryRun !is SkillDryRunResult.Success) return SkillManagementResult.Rejected(dryRun.toString())
        return when (val result = installer.installArchive(archive, sha256)) {
            is SkillInstallResult.Installed -> SkillManagementResult.Installed(result.skillId, result.version)
            is SkillInstallResult.Rejected -> SkillManagementResult.Rejected("${result.failure}: ${result.detail}")
        }
    }

    fun list(owner: Principal): Set<String> = if (owner.role == PrincipalRole.OWNER) installed() else emptySet()
    fun remove(owner: Principal, skillId: String): Boolean = owner.role == PrincipalRole.OWNER && remove(skillId)
}
