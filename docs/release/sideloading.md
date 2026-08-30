# Dark Lord prototype sideloading

The prototype is installed with ADB on a dedicated, unlocked test phone. Private keys, OAuth tokens, and Tailscale credentials must never be committed or pasted into this repository.

## Build and identify the artifact

From the repository root:

```bash
./gradlew :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK"
shasum -a 256 "$APK"
```

Record the complete SHA-256, commit, device serial, model, and Android version in the acceptance evidence record. The release build is non-debuggable and uses the local debug keystore only as a prototype fallback when no production keystore is configured.

## Install on a test phone

Enable Developer options and USB debugging on the dedicated phone, verify the device identity, and install only the artifact you just hashed:

```bash
adb devices -l
adb -s <SERIAL> install -r app/build/outputs/apk/release/app-release.apk
adb -s <SERIAL> shell pm path com.fsaint.androidagent
```

If replacing a build signed by a different key, uninstalling may erase app data; perform that only on a disposable test phone and re-run setup. Do not use `pm grant` or role commands to simulate user consent. Use Android Settings for roles, runtime permissions, notification/accessibility access, MediaProjection, and Device Owner setup.

## Reprovision and recovery

For a clean prototype run, use the phone's normal Settings reset flow, then install the hashed APK and follow the setup guide. Device Owner provisioning is destructive/reset-sensitive and must be explicitly performed by the operator. After setup, verify boot recovery with:

```bash
adb -s <SERIAL> shell am force-stop com.fsaint.androidagent
adb -s <SERIAL> reboot
adb -s <SERIAL> wait-for-device
adb -s <SERIAL> shell dumpsys package com.fsaint.androidagent | grep -E 'BootReceiver|AgentDeviceAdminReceiver'
adb -s <SERIAL> shell dumpsys jobscheduler | grep dark-lord-runtime-restore
```

The exact package is `com.fsaint.androidagent`; the Device Admin component is `com.fsaint.androidagent/.AgentDeviceAdminReceiver`. Record command output as evidence. A production deployment must replace prototype signing through the environment variables documented below and must not reuse a debug key.

## Production signing configuration

Set `DARK_LORD_RELEASE_STORE_FILE` to a keystore path outside the repository. Supply the alias/passwords through environment variables or a local untracked Gradle properties file:

```bash
export DARK_LORD_RELEASE_STORE_FILE=/secure/path/dark-lord-release.jks
export DARK_LORD_RELEASE_KEY_ALIAS=dark-lord
export DARK_LORD_RELEASE_STORE_PASSWORD='...'
export DARK_LORD_RELEASE_KEY_PASSWORD='...'
./gradlew :app:assembleRelease
```

CI should provide these as secret variables. If the store file is absent, the build must fail rather than silently use a production key. Never print these variables, include them in logs, or commit the keystore.
