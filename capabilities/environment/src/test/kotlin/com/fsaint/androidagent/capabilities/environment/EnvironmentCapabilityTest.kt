package com.fsaint.androidagent.capabilities.environment

import com.fsaint.androidagent.model.ToolError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentCapabilityTest {
    @Test fun statusToolsReturnBoundedRecords() = runTest {
        val adapter = FakeEnvironmentAdapter(
            location = LocationStatus(true, 37.0, -122.0), sensors = listOf(SensorDescription("accelerometer", "Accelerometer")),
            nfc = NfcStatus(true, true), usb = UsbStatus(true, 1), contacts = listOf(ContactSummary("1", "Ada", "555")),
            files = listOf(FileSummary("notes.txt", 4, false)),
        )
        val capability = EnvironmentCapability(adapter)
        assertEquals(LocationStatus(true, 37.0, -122.0), capability.locationStatus().payload)
        assertEquals(1, capability.sensors().payload?.size)
        assertEquals(NfcStatus(true, true), capability.nfcStatus().payload)
        assertEquals(UsbStatus(true, 1), capability.usbStatus().payload)
        assertEquals(1, capability.contacts().payload?.size)
        assertEquals(1, capability.files().payload?.size)
    }

    @Test fun permissionAndUnsupportedStatesAreTruthful() = runTest {
        val capability = EnvironmentCapability(FakeEnvironmentAdapter(permission = false, supported = false))
        listOf(capability.locationStatus(), capability.sensors(), capability.nfcStatus(), capability.usbStatus(), capability.contacts(), capability.files())
            .forEach { result ->
                assertFalse(result.success)
                assertTrue(result.error == ToolError.PERMISSION_REQUIRED || result.error == ToolError.UNSUPPORTED)
            }
    }

    @Test fun handlersExposeAllEnvironmentTools() = runTest {
        val keys = EnvironmentCapability(FakeEnvironmentAdapter()).toolHandlers().keys
        assertEquals(setOf("location.status", "sensors.list", "nfc.status", "usb.status", "contacts.list", "files.list"), keys)
    }
}

private class FakeEnvironmentAdapter(
    private val permission: Boolean = true,
    private val supported: Boolean = true,
    private val location: LocationStatus = LocationStatus(false, null, null),
    private val sensors: List<SensorDescription> = emptyList(),
    private val nfc: NfcStatus = NfcStatus(false, false),
    private val usb: UsbStatus = UsbStatus(false, 0),
    private val contacts: List<ContactSummary> = emptyList(),
    private val files: List<FileSummary> = emptyList(),
) : EnvironmentAdapter {
    override fun permissionGranted() = permission
    override fun supported() = supported
    override fun locationStatus() = location
    override fun sensors() = sensors
    override fun nfcStatus() = nfc
    override fun usbStatus() = usb
    override suspend fun contacts() = contacts
    override suspend fun files() = files
}
