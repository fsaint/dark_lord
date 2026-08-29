# Galaxy Z Flip3 Reset and Device Owner Provisioning

This guide prepares SM-F711U1 as a dedicated Android Agent phone. Factory reset permanently removes local data. Do not run these steps until required data, authenticator access, and eSIM details are backed up.

## Before resetting

1. Confirm the phone number and E.164 owner number to enroll after setup.
2. Back up photos, downloads, messages, authenticator recovery codes, and any data not already synchronized.
3. Confirm with the carrier whether reset can remove the active eSIM. Have the carrier activation QR code or reactivation procedure available.
4. Record the Tailscale account and an OpenAI API key in a password manager; do not place either in a shell command, ADB command, screenshot, or repository.
5. Build and sign an APK that declares `AgentDeviceAdminReceiver`, `VoiceInteractionService`, SMS, dialer, notification, boot, accessibility, and foreground-service components. Device Owner provisioning cannot succeed until that receiver is installed.
6. On the development Mac, verify `adb devices -l` shows the Flip3 when USB debugging is enabled.

## Reset the phone

1. On the Flip3, open **Settings → General management → Reset → Factory data reset**.
2. Review the listed accounts and data, select **Reset**, then **Delete all**.
3. Wait for Android to reboot to the initial setup flow. Do not restore a cloud backup or add a Google/Samsung account before Device Owner is established.
4. Complete only the minimal device setup required to reach the launcher, connect to trusted Wi-Fi, and enable Developer options/USB debugging. Do not add accounts, a work profile, or another device-management app.

## Provision Device Owner through ADB

1. Connect the phone by USB and accept the RSA debugging prompt.
2. Install the signed debug APK. Replace the path below with the actual artifact path; this command does not grant ownership by itself.

```bash
adb install -r path/to/android-agent-debug.apk
```

3. Confirm that no personal/work accounts or secondary users exist. Device Owner assignment fails if Android considers the device already managed or user-configured with accounts.
4. Assign Device Owner. Replace `com.example.androidagent` with the final application ID if it differs.

```bash
adb shell dpm set-device-owner com.example.androidagent/.AgentDeviceAdminReceiver
```

5. Verify the result.

```bash
adb shell dpm get-device-owner
adb shell dumpsys device_policy | rg -i 'Device Owner|androidagent'
```

Expected: both commands identify the Android Agent package and its `AgentDeviceAdminReceiver` as the Device Owner.

6. If `set-device-owner` fails because the device is already provisioned, do not attempt to bypass Android policy. Factory-reset again and repeat the minimal-account-free setup sequence.

## Complete agent enrollment

1. Launch the agent setup UI.
2. Grant the requested Assistant, SMS, Dialer, Accessibility, Notification Listener, microphone, camera, contacts, location, Bluetooth, Wi-Fi, and screen-capture permissions only after reviewing each Android prompt.
3. Choose the agent as the default Assistant, default SMS application, and default Phone application when Android presents the role dialogs.
4. Enter the owner number in E.164 format and complete its one-time SMS verification.
5. Join Tailscale; verify the agent MCP server listens only on the Tailscale address and rejects non-enrolled clients.
6. Connect each MCP server through its OAuth flow. Restrict every connection to its intended scopes.
7. Disable battery optimization for the agent and confirm the persistent **Agent active** notification is visible.
8. Test the Side/Power-key Assistant gesture open and closed. With the phone closed, verify that the cover screen accepts touches, the microphone accepts a request, and the response is rendered and spoken.

## Post-provisioning validation

Run the development acceptance checklist before trusting autonomous use:

```bash
./gradlew test lintDebug connectedCheck
```

Then verify real SMS, incoming calls, reboot recovery, `OWNER`/`KNOWN`/`UNKNOWN` scope isolation, owner escalation/resumption, Tailscale MCP authentication, and the Flip3 cover Assistant flow. Export the audit log and retain it with the test record.
