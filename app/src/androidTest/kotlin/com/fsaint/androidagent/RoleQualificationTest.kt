package com.fsaint.androidagent

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleQualificationTest {
    @Test
    fun packageQualifiesForDialerAndSmsRoles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val roleManager = context.getSystemService(RoleManager::class.java)

        assertTrue(roleManager.isRoleAvailable(RoleManager.ROLE_DIALER))
        assertTrue(roleManager.isRoleAvailable(RoleManager.ROLE_SMS))
        assertFalse(
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")).setPackage(context.packageName),
                0,
            ).isEmpty(),
        )
    }
}
