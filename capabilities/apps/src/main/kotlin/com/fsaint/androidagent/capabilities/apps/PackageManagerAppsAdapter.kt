package com.fsaint.androidagent.capabilities.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

@Suppress("DEPRECATION") // Int flag overloads keep this minSdk 31 module compatible below API 33.
class PackageManagerAppsAdapter(context: Context) : AppsAdapter {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    override suspend fun list(): AppsListOutcome = try {
        AppsListOutcome.Success(
            packageManager.getInstalledApplications(0)
                .map { applicationInfo ->
                    InstalledApp(
                        label = packageManager.getApplicationLabel(applicationInfo).toString(),
                        packageName = applicationInfo.packageName,
                        enabled = applicationInfo.enabled,
                    )
                }
                .sortedWith(compareBy(InstalledApp::label, InstalledApp::packageName)),
        )
    } catch (_: SecurityException) {
        AppsListOutcome.PermissionRequired
    } catch (_: UnsupportedOperationException) {
        AppsListOutcome.Unsupported
    }

    override suspend fun launch(packageName: String): AppLaunchOutcome {
        if (packageName.isBlank()) return AppLaunchOutcome.NotFound

        try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return AppLaunchOutcome.NotFound
        } catch (_: SecurityException) {
            return AppLaunchOutcome.PermissionRequired
        } catch (_: UnsupportedOperationException) {
            return AppLaunchOutcome.Unsupported
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return AppLaunchOutcome.NotLaunchable
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            applicationContext.startActivity(launchIntent)
            AppLaunchOutcome.Launched
        } catch (_: SecurityException) {
            AppLaunchOutcome.PermissionRequired
        } catch (_: ActivityNotFoundException) {
            AppLaunchOutcome.Failed
        } catch (_: RuntimeException) {
            AppLaunchOutcome.Failed
        }
    }
}
