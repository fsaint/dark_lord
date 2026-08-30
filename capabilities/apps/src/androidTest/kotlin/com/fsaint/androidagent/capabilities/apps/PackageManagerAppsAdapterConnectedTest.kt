package com.fsaint.androidagent.capabilities.apps

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackageManagerAppsAdapterConnectedTest {
    @Test
    fun packageListIncludesAndroidFrameworkMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val outcome = PackageManagerAppsAdapter(context).list()

        assertTrue(outcome is AppsListOutcome.Success)
        val androidPackage = (outcome as AppsListOutcome.Success).apps.firstOrNull { it.packageName == "android" }
        assertNotNull(androidPackage)
        assertTrue(androidPackage!!.label.isNotBlank())
        assertEquals(true, androidPackage.enabled)
    }
}
