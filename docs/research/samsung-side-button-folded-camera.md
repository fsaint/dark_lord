# Samsung Side-button registration and folded camera access

## Conclusions

Samsung has a separate registration mechanism for the function-level entries in its Side-button settings. It is not ordinary Android camera registration. On the examined Galaxy Z Flip3, Settings discovers content providers advertising `com.samsung.android.intent.action.SIDE_BUTTON_SETTINGS`, but accepts them only when they belong to a system app or an app with the platform signing key. Dark Lord cannot join that top-level list simply by adding an intent filter to its sideloaded APK.[^1]

The menu combines built-in actions, privileged provider-backed actions, and a general app picker. Camera, Flashlight, Magnifier, Samsung capture, Modes and Routines, and Apps therefore do not represent equivalent launch contracts. Samsung's consumer documentation describes the expanded action choices, but the exact firmware establishes how discovery and dispatch work.[^1][^4]

There is a second, independent problem: launching an ordinary activity while folded. The Side-button dispatcher checks cover-launcher eligibility before handing an activity to the lock-screen system. However, on this particular Flip3 firmware, the eligibility method returns `false` unconditionally and its enable method returns `-100`. The apparent cover allowlist route in the shared dispatcher is not implemented by this device's activity-task service.[^2]

Modes and Routines provides a more promising trigger alternative: its Side-button provider returns a service action. The dispatcher starts that service without the activity branch's unlock queue. This establishes a real difference worth testing, not proof that a routine can subsequently launch Dark Lord on the cover or grant it camera access.[^2][^3]

The recommended next step is a bounded experiment that separates **trigger delivery**, **cover-screen launch**, and **camera authorization**. Do not spend another iteration adding generic camera intent filters or assuming that a Flip5/6/7 cover-screen guide applies unchanged to the Flip3.

## Device scope and confidence

These conclusions apply to the connected device examined on September 9, 2026:

| Property | Observed value |
|---|---|
| Model | Galaxy Z Flip3, SM-F711U1 |
| Android | 15 |
| One UI property | `70000`, One UI 7 |
| Firmware increment | `F711U1UESHKZE3` |
| Settings | versionName 15, versionCode 35 |
| Dark Lord package | `com.fsaint.androidagent` |
| Cover display | Display 1, 512 × 260 pixels |
| Secondary-activity feature | `android.software.activities_on_secondary_displays` present |
| MultiStar | Not installed at inspection |

The strongest evidence is the installed Settings, Routines, and system-server bytecode, identified by hashes in the evidence appendix. Decompiled control flow can be imperfect: the provider admission check and cover-eligibility stubs were also checked in instruction-oriented output. Those simple branches are substantially firmer evidence than reconstructed names or complex decompiled methods.[^1][^2][^3]

Source-code findings establish what the examined methods do. They do not establish that an untested end-to-end workaround succeeds. In particular, this report does not claim successful folded camera capture, successful MultiStar installation, or successful routine-to-Dark-Lord dispatch.

## The registration behind the menu

### Discovery and admission

`FunctionKeyUtils.getFunctionKeyItems()` queries content providers matching the Side-button settings action with metadata enabled. Before reading a provider's contribution, it tests:

```java
next.system || Utils.hasPlatformKey(context, next.providerInfo.packageName)
```

A provider failing both conditions is skipped. This is a concrete admission gate, not merely a missing developer guide. A normally installed application does not satisfy it by declaring a permission, requesting accessibility access, registering as an assistant, or selecting itself as the default camera handler.[^1]

For admitted providers, the consumer reads the following metadata:

| Metadata | Purpose in the inspected consumer |
|---|---|
| `com.android.settings.keyhint` | Stable entry key |
| `com.android.settings.title` | Display title, literal or resource |
| `com.android.settings.icon` | Display icon, with app-icon fallback |
| `com.android.settings.order` | Ordering value |
| `com.samsung.android.settings.side_button.launch_intent_component` | Optional action-configuration activity |
| `com.samsung.android.settings.side_button.action_key_list` | Semicolon-separated action keys |

For dynamic action details, Settings acquires the provider and calls `sidebutton/getInfo`, passing an action key in a Bundle. The response supplies availability, a title, an intent type, and an encoded intent URI. The selected intent and its type are ultimately written through `putKeyCustomizationInfo` for the power key, with the double-press code represented as 8 and the hardware keycode as 26.[^1]

This is an internal, observable contract. No public, supported third-party Side-button provider enrollment API was established. The practical distinction matters: implementing the response Bundle correctly would still leave Dark Lord outside the admission gate.

### A real provider: Modes and Routines

The installed Routines manifest declares `SideButtonInfoProvider` with authority `com.samsung.android.app.routines.sidebuttoninfoprovider`. Its metadata identifies the entry as `key_modes_and_routines` and points to `SideButtonConfigActivity`. It advertises the exact discovery action used by Settings.[^3]

For a selected routine, the provider returns the routine's title and an intent generated with the running reason `SIDE_KEY`. Crucially, its response uses `intentType = 3`. The corresponding generic Side-button dispatcher handles type 3 as a service launch. Routines therefore demonstrates that the extra menu entries can select functional operations, not just launch their main application activities.[^2][^3]

This is the strongest answer to the registration question: **yes, another registration exists, and a shipping Samsung application uses it; that registration is privilege-gated.**

### Built-in choices and Camera

The same Settings code constructs built-in entries such as Flashlight and Magnifier directly. Flashlight maps to the special target `torch/torch`; Magnifier maps to Samsung's explicit magnifier component. The dispatcher recognizes Samsung Camera's explicit component as a special behavior rather than treating it like an arbitrary app.[^1][^2]

The dynamic action loader also special-cases the quick-camera action key and rewrites it to Samsung Camera's component. Consequently, becoming a handler for Android camera intents does not replace this explicit Samsung target. Dark Lord's camera registration remains useful for callers that actually resolve those intents, but it does not make the stock Camera choice delegate to Dark Lord.[^1]

The general Apps option is the intended route for ordinary installed applications. Its availability should not be confused with admission to the privileged provider menu, nor with permission to execute on the folded cover display.

## Why the folded launch is deferred

The relevant launch sequence contains several independent decisions:

```text
Side-button double press
  → stored key-customization intent and action type
  → special Samsung behavior or generic target
  → folded-cover eligibility check
  → activity: launch now or queue through keyguard
    service: start the target service
  → Dark Lord lifecycle and camera permissions, if reached
```

In `SideKeyDoublePress.Behavior.showCoverToast()`, the folded branch asks the activity-task service whether the target package is enabled for the cover launcher. If eligible, it adds `runOnCover` to the keyguard handoff. Otherwise, it adds `showCoverToast`. For an activity action, `OpeningApps.startTargetApp()` uses the pending-intent-after-unlock route when keyguard is showing or the cover branch applies.[^2]

This explains why declaring `showWhenLocked` in Dark Lord is insufficient by itself: the operating system may defer the launch before Dark Lord's activity runs. An activity flag cannot influence a launch that has not reached the activity.

The important device-specific result is that the following methods in this Flip3's `ActivityTaskManagerService` are stubs:

| Method | Observed result |
|---|---|
| `isPackageEnabledForCoverLauncher` | Always false |
| `isPackageSettingsEnabledForCoverLauncher` | Always false |
| `setCoverLauncherPackageEnabled` | Always -100 |
| `setCoverLauncherPackageDisabled` | Always -100 |
| `getCoverLauncherAvailableAppList` | Empty list |
| `getCoverLauncherEnabledAppList` | Null |

The numerical error value is reported as observed; its symbolic meaning was not established. Calling the setter with a different package or higher privilege would not change its unconditional return in this binary. This rules out that specific enablement API, not every older cover-widget or display-launch path.[^2]

It also resolves an apparent conflict in online advice. A framework may contain cover-launch branches shared across devices while the device-specific service disables the corresponding feature. A method name found in a public firmware repository is not sufficient evidence of support on a particular phone.

## Alternatives and their limits

### Samsung MultiStar and the older cover launcher

There is historical first-hand evidence of MultiStar launching applications on the Flip3's small cover display. A Samsung Members post from February 2022 reports usable applications on that device. This is a community observation hosted by Samsung, not a present-day compatibility guarantee.[^5]

Samsung's current cover-app instructions describe Good Lock → MultiStar → I♡Galaxy Foldable → the cover-launcher widget, but explicitly frame their coverage around Flip5 and later. Those instructions support the product concept and setup path, not verified compatibility with this Flip3 firmware.[^6]

The historical Flip3 implementation may use a different launch mechanism from the stubbed activity-task APIs. Therefore MultiStar remains worth a small compatibility test. The test must first establish that its widget can launch Dark Lord while folded; it must not assume that adding Dark Lord to the widget will change the Side-button handler's always-false eligibility result.

A 2023 Reddit thread reports the same request to open the phone, with replies saying fingerprint authentication restored their cover launcher. This suggests testing folded-and-authenticated separately from folded-and-locked. It is a test-design clue, not a verified fix for the current firmware.[^7]

### A routine as a trigger broker

The service dispatch already established for Modes and Routines makes this the most relevant next trigger experiment. First select a harmless routine action and verify that the routine actually executes when folded. Then determine whether an available routine action can invoke Dark Lord or an explicit owner-controlled bridge.[^2][^3]

The first successful result would demonstrate trigger delivery only. If a routine simply starts Dark Lord's normal activity and Samsung still defers that activity, changing the trigger has not solved the display problem. Similarly, receiving a broadcast or observing a routine's setting change does not itself authorize camera capture.

Avoid designing a private Routines provider integration around guessed permissions. The installed application declares several signature-or-system permissions for routine data and its SDK-facing integration. The presence of a private SDK namespace is not evidence that arbitrary applications can use it.[^3]

### A small cover-display component in Dark Lord

The device exposes a real secondary display and advertises support for secondary-display activities. That makes a minimal display-targeted probe more informative than another change to camera categories. The experiment should launch a tiny status activity on the observed cover display, verify its lifecycle and visibility, and only then request a camera capture.[^2]

SamSprung TooUI provides concrete implementation examples. At the examined revision, `CoverOptions` builds launch options with a display ID and bounds; `OnBroadcastService` launches its cover component on display 1; `SamSprungOverlay` uses an application-overlay window. These are separate techniques involving display routing, a service, and overlay permission, not another camera registration. The code is useful architectural evidence, but was not validated on this firmware.[^8]

A production design should discover the display rather than assume every device uses ID 1. It should also stop camera work when the activity loses the visibility needed for capture, preserve keyguard, and expose a clear capture indicator. Do not import launcher source wholesale without reviewing its license and attribution requirements.[^8]

The FlipWidgets repository similarly uses explicit cover-display routing, but its requirements name Flip7. Its README's description of an official integration should not be treated as proof of a public Flip3 SDK or compatibility with the examined device.[^9]

### Knox hardware-key mapping

Samsung documents enterprise hardware-key mapping through Knox Service Plugin, including target applications and custom intents. Its Side-key guide describes power-key press/release reports and an enterprise-managed setup. This is a genuine supported integration family, distinct from the consumer action-provider menu.[^10][^11]

It is not a drop-in replacement for an ordinary sideloaded app. Samsung's Android 15 restrictions require Device Owner or Profile Owner status for `SystemManager.setHardKeyIntentBroadcast`; legacy Device Administrator status alone is insufficient. The published device examples do not establish the required Flip3 behavior, so eligibility must be confirmed before provisioning.[^12]

This should be a later option for a deliberately managed, dedicated agent phone. Provisioning, licensing, and potential reset implications need a separate decision. There is no reason to take those steps before inexpensive routine and cover-display experiments establish whether they are necessary.

### ADB, Shizuku, and alternative key mappers

Privileged key mapping is a potential development route, but it does not remove the provider's signature check or repair the stubbed cover APIs. A useful future investigation would verify the exact permission checks around the installed window-manager key-customization methods and whether a shell-backed helper can deliver a service action reliably.

The public `quinnjr/sidekey` project illustrates a narrower technique: changing a long-press setting with `WRITE_SECURE_SETTINGS`, optionally granted through Shizuku. Its documented scope is newer One UI behavior and it explicitly limits device confirmation. It does not demonstrate folded double-press camera capture on Flip3.[^13]

Any helper-based design must distinguish one-time configuration from a broker that must remain running. It must also test reboot behavior and operate without a development computer attached. A successful `adb` launch alone would not meet the intended standalone-phone experience.

### Old package-name substitution tricks

SubUI-browser is directly relevant historically because it targeted Flip3. Its current README explains that it used Samsung Health's package name to pass an old widget allowlist, and says Samsung subsequently patched the workaround. It should not be proposed as a present-day solution or used as a reason to remove Samsung Health.[^14]

These older projects demonstrate that cover-screen restrictions have changed, not that all restrictions can still be bypassed through manifest metadata.

## Camera authorization remains a separate requirement

Android distinguishes permission to start a foreground service from access to camera and microphone permissions that are limited to active use. On Android 14 and later, a camera service started from the background can fail immediately despite the app having previously received camera permission. Google documents specific exceptions, including certain system, widget, notification, device-owner, and voice-interaction paths.[^15]

Dark Lord should use a supported, verifiable path rather than assume that a routine or overlay confers those exceptions. A visible cover activity is one candidate. Its existing assistant integration is another candidate for a narrowly scoped experiment, but that role does not establish that arbitrary Dark Lord service starts qualify.[^15]

The desired acceptance condition is consequently more precise than “the button works”: one physical double press, while folded in the agreed lock state, reaches the agent, produces a real fresh image, submits it once for analysis, and returns a useful response. Camera errors must remain distinguishable from model/network errors and from failure to launch.

## Recommended experiment sequence

No implementation or phone configuration change is part of this report. The following steps are proposed in order of information gained relative to effort.

1. **Establish a folded routine trigger.** Temporarily select a harmless routine for double press. Confirm actual execution while folded, both before and after fingerprint authentication. Restore the original assignment after the test. Record whether the service starts, not just whether the settings UI accepts the choice.
2. **Establish cover visibility independently.** If the compatible Samsung MultiStar is offered for this phone, test its older cover-launcher widget with Dark Lord. Otherwise, or if it fails, use a minimal Dark Lord cover-display probe. Keep this test separate from the hardware button.
3. **Establish camera access.** From the verified visible component, capture one image and validate that the artifact is newly created and nonempty. Test foreground loss and a blocked-camera condition. Do not involve the LLM until the hardware result is clear.
4. **Connect the proven trigger and display/capture path.** Use an available routine bridge if one is demonstrated. Investigate privileged key mapping only if the trigger cannot otherwise reach the proven capture path.
5. **Validate standalone operation.** Repeat after reboot and without ADB, with the phone folded, authenticated, locked, and reopened. Check that repeated presses produce fresh captures without duplicate submissions or an old activity being reused silently.

| Check | Passing evidence | What a failure isolates |
|---|---|---|
| Folded trigger | Routine/service event timestamp | Hardware dispatch or lock-state gating |
| Cover component | Resumed activity/window on the cover display | Display routing or launch policy |
| Photo | Fresh JPEG artifact with successful camera outcome | Camera eligibility or lifecycle |
| Analysis | One model request referencing that artifact | Harness or network handling |
| Repeat press | Another new artifact, no duplicate first request | Intent/lifecycle reuse |
| Reboot, no ADB | Same workflow still works | Dependence on temporary privileges or helpers |

This sequence can produce a useful partial result without mislabeling it as complete. For example, cover capture requiring a fingerprint may be acceptable, while a workflow requiring the phone to unfold is not the same feature.

## Evidence appendix

The installed artifacts provide reproducible evidence without redistributing Samsung binaries. These SHA-256 values identify the versions underlying the device-specific conclusions:

| Artifact | Device path | SHA-256 |
|---|---|---|
| Settings | `/system/priv-app/SecSettings/SecSettings.apk` | `3f005b6dd7c3cd7042d03b55112912f9c55cd2b49b8160901f45906afb24672d` |
| System services | `/system/framework/services.jar` | `57c96a043d5d7373b46235a2c161ba27a58aca4b1986f9f554eb4f12a66d24c7` |
| Routines | `/system/priv-app/Routines/Routines.apk` | `baeb30088223134ed8334e4bd1fbcb0a0a2354668eb5c0daf932127bcdd9228f` |

Relevant symbols are `FunctionKeyUtils.getFunctionKeyItems`, `FunctionKeyUtils.loadDynamicFunctionKeyActions`, `UsefulfeatureUtils.setSideKeyCustomizationInfo`, `SideKeyDoublePress.Behavior.showCoverToast`, `PhoneWindowManagerExt.OpeningApps.startTargetApp`, the cover-launcher methods on `ActivityTaskManagerService`, and `SideButtonInfoProvider.call`. The admission branch skips a provider when both the system flag and platform-key check fail; the cover-eligibility instruction sequence returns zero directly.

The public [samsung_framework repository](https://github.com/488315/samsung_framework/tree/main/services/sources/com/android/server/policy) provides useful comparable dispatcher code, but its firmware identity differs. It is not the source for the unconditional return values reported for this phone.

## Sources

[^1]: Samsung, installed `SecSettings.apk`, firmware `F711U1UESHKZE3`, inspected September 9, 2026. Primary device artifact; not publicly linked. Relevant classes and hash are in the evidence appendix. Provider admission was corroborated in instruction-oriented output.
[^2]: Samsung, installed `services.jar` and device display/feature diagnostics, same firmware and inspection date. Primary device evidence; not publicly linked. Cover-eligibility and enablement returns were corroborated in instruction-oriented output.
[^3]: Samsung, installed `Routines.apk`, same inspection date. Manifest and `com.samsung.android.app.routines.domainmodel.sidebuttoninfoprovider.SideButtonInfoProvider`. Primary device artifact; hash above.
[^4]: Samsung, [Customize the side button or Bixby button on your Galaxy phone](https://www.samsung.com/us/support/answer/ANS10002033/). Accessed September 9, 2026. Consumer action list; functions vary by software/device.
[^5]: UiHua, Samsung Members, [Launch apps on Z Flip 3 cover screen via Good Lock](https://r1.community.samsung.com/t5/galaxy-z/launch-apps-on-z-flip-3-cover-screen-via-good-lock/td-p/15463761), February 26, 2022. Historical first-hand community report, not official support certification.
[^6]: Samsung Japan, [Using apps on the Galaxy Z Flip cover screen](https://www.samsung.com/jp/support/mobile-devices/coverdisplay-goodlock/), updated August 14, 2026. Current instructions explicitly describe Flip5 onward; not evidence of this Flip3's compatibility.
[^7]: Reddit r/galaxyzflip, [Goodlock multistar coverscreen flip 3 no longer working?](https://www.reddit.com/r/galaxyzflip/comments/15qxyj7/), August 2023. First-hand reports and replies, anecdotal and older firmware.
[^8]: SamSprung, [SamSprung TooUI](https://github.com/SamSprung/SamSprung-TooUI), revision `905868baae1f6b57ac5a54d3f70d412ba7f170af`, inspected September 9, 2026. Primary implementation: [CoverOptions.kt](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/app/CoverOptions.kt), [OnBroadcastService.kt](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/samsprung/OnBroadcastService.kt), [SamSprungOverlay.kt](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/samsprung/SamSprungOverlay.kt), and [license](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/LICENSE). No on-device compatibility test in this report.
[^9]: Gh0strab, [FlipWidgets](https://github.com/Gh0strab/FlipWidgets), README accessed September 9, 2026. Developer's implementation claims and Flip7-oriented requirements; not Samsung SDK documentation.
[^10]: Samsung Knox, [Device key mapping](https://docs.samsungknox.com/admin/knox-platform-for-enterprise/knox-service-plugin/configure-advanced-policies/device-key-mapping/), updated December 19, 2025. Enterprise configuration documentation.
[^11]: Samsung Knox, [KeyMapping for Side key](https://docs.samsungknox.com/admin/knox-platform-for-enterprise/knox-service-plugin/assets/ksp-side-key-mapping.pdf). Ten-page integration guide; accessed September 9, 2026. Older device examples require model-specific confirmation.
[^12]: Samsung Knox, [Restricted Knox SDK methods](https://docs.samsungknox.com/dev/knox-sdk/api-reference/restricted-api-methods/), accessed September 9, 2026. Android 15/Knox 3.11 Device Owner/Profile Owner requirements.
[^13]: quinnjr, [sidekey](https://github.com/quinnjr/sidekey), README accessed September 9, 2026. Newer-One-UI long-press configuration technique, not a demonstrated Flip3 folded-camera solution.
[^14]: JoelHorrocks, [SubUI-browser](https://github.com/JoelHorrocks/SubUI-browser), README accessed September 9, 2026. Author's explanation of the older Samsung Health package-name workaround and its subsequent patching.
[^15]: Google, [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), updated September 1, 2026. Separate background-start and while-in-use camera/microphone restrictions and exceptions.
