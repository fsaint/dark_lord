package com.fsaint.androidagent

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.ui.CommunicationsAccessStatus
import com.fsaint.androidagent.ui.PrincipalSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OwnerProvisioningUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val granted = CommunicationsAccessStatus(
        smsRoleHeld = true,
        dialerRoleHeld = true,
        notificationListenerEnabled = true,
        postNotificationsPermissionGranted = true,
        capabilityPermissionsGranted = true,
    )

    @Test
    fun setupControlsAreHiddenWhenOwnerExists() {
        val owner = Principal("owner:+14155550100", "+14155550100", PrincipalRole.OWNER)

        showScreen(owner = owner, directory = FakePrincipalDirectory(listOf(owner)))

        compose.onNodeWithText("Owner: +14155550100").assertIsDisplayed()
        compose.onNodeWithText("Set up first owner").assertDoesNotExist()
        compose.onNodeWithText("Provision owner").assertDoesNotExist()
    }

    @Test
    fun submitRemainsDisabledUntilExplicitConfirmation() {
        showScreen(owner = null)

        compose.onNodeWithText("Provision owner").assertIsNotEnabled()
        compose.onNodeWithText("Owner E.164 number").performTextInput("+14155550100")
        compose.onNodeWithText("Provision owner").assertIsNotEnabled()
        compose.onNodeWithText("I confirm this is my phone number").performClick()
        compose.onNodeWithText("Provision owner").assertIsEnabled()
    }

    @Test
    fun invalidNumberShowsValidationErrorWithoutCallingService() {
        var calls = 0
        showScreen(owner = null) {
            calls++
            error("Provisioning should not be called for invalid input")
        }

        compose.onNodeWithText("Owner E.164 number").performTextInput("415-555-0100")
        compose.onNodeWithText("I confirm this is my phone number").performClick()
        compose.onNodeWithText("Provision owner").performClick()

        compose.onNodeWithText("Enter a valid E.164 number.").performScrollTo().assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test
    fun successfulProvisioningReplacesSetupWithOwnerStatus() {
        var submitted: String? = null
        showScreen(owner = null) { e164 ->
            submitted = e164
            Result.success(Principal("owner:$e164", e164, PrincipalRole.OWNER))
        }

        compose.onNodeWithText("Owner E.164 number").performTextInput("+14155550100")
        compose.onNodeWithText("I confirm this is my phone number").performClick()
        compose.onNodeWithText("Provision owner").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Owner: +14155550100").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("+14155550100", submitted)
        compose.onNodeWithText("Set up first owner").assertDoesNotExist()
    }

    private fun showScreen(
        owner: Principal?,
        directory: PrincipalDirectory = FakePrincipalDirectory(),
        provision: suspend (String) -> Result<Principal> = {
            Result.failure(IllegalStateException("Unexpected provisioning call"))
        },
    ) {
        compose.setContent {
            PrincipalSettingsScreen(
                principals = directory,
                owner = owner,
                onProvisionOwner = provision,
                accessStatus = { granted },
                onRequestRoles = {},
                onRequestPermissions = {},
                onOpenNotificationListenerSettings = {},
                onBack = {},
            )
        }
    }

    private class FakePrincipalDirectory(
        initial: List<Principal> = emptyList(),
    ) : PrincipalDirectory {
        private val values = initial.associateByTo(linkedMapOf()) { it.id }

        override suspend fun owner(): Principal? = values.values.firstOrNull { it.role == PrincipalRole.OWNER }

        override suspend fun provisionInitialOwner(e164: String): Principal = error("Not used by UI")

        override suspend fun lookup(e164: String): Principal? = values.values.firstOrNull { it.e164 == e164 }

        override suspend fun list(): List<Principal> = values.values.toList()

        override suspend fun upsert(principal: Principal) {
            values[principal.id] = principal
        }

        override suspend fun removeKnown(e164: String): Boolean {
            val id = values.values.firstOrNull { it.role == PrincipalRole.KNOWN && it.e164 == e164 }?.id ?: return false
            values.remove(id)
            return true
        }
    }
}
