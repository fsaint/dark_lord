# Interactive questions on the Galaxy Z Flip3 cover screen

## Recommendation

Dark Lord can plausibly offer a compact question with Yes and No buttons on its dedicated Flip3 without root, but the exact folded-and-locked interaction remains a device experiment, not an established supported integration. The best direction is a temporary Dark Lord-owned cover surface backed by a durable question record. Start by testing the existing secondary-display `Presentation`; if its visibility or lifetime is unsuitable, test a narrowly scoped application overlay inspired by SamSprung TooUI. Do not build an entire replacement launcher merely to display two buttons.

Samsung does publish a third-party cover-widget integration, but its documentation explicitly targets the larger Flip5 Flex Window. That is a good supported route for newer hardware, not proof that the same manifest registration will work on the Flip3. The older device needs a separate compatibility decision. [Samsung Flex Window](https://developer.samsung.com/galaxy-z/flex_window.html).[^1]

Three independent conditions must hold: the app must get a visible surface on the cover; that surface must receive real touch input in the relevant lock state; and a tap must resume exactly the question displayed, without duplicating or broadening an action. A notification banner or successful background trigger establishes none of those three by itself.

The recommended first question is harmless: **“Want the full version?”** Yes requests additional detail in the Outside chat; No ends that offer. Prove this before using cover buttons for sending messages, placing calls, deleting data, or any other consequential operation.

## Device and evidence scope

The target is Dark Lord's Samsung Galaxy Z Flip3 SM-F711U1, Android 15/API 35, with the repository's previous device investigation identifying One UI 7 and firmware `F711U1UESHKZE3`. Read-only display diagnostics on September 10, 2026 again identify display 1 as a 512 × 260 internal cover display, density 160, with `FLAG_PRESENTATION`. Base and override display records contain different power-state values, so neither a single state field nor the existence of display 1 should be treated as a complete folded-state detector. The earlier firmware analysis is recorded in the [Side-button research](samsung-side-button-folded-camera.md).[^2]

This report distinguishes public API contracts, public implementation examples, repository code observations, and recommendations. No external launcher was installed, no lock-screen setting was weakened, and no interactive cover-screen prototype was deployed as part of this investigation. In particular, genuine finger taps while folded and securely locked have not been demonstrated for the proposed question surface.

The separate voice-prompt change now instructs spoken VOICE and CAPTURE turns to be brief and offer detail. It does not yet create a structured pending question, retain a separate full answer, or render Yes/No buttons. That distinction is important: conversational wording is not a durable UI interaction contract.

## Official integration options

### Samsung Flex Window widgets

Samsung's developer documentation describes an Android `AppWidgetProvider` with two metadata resources: standard `android.appwidget.provider` metadata and Samsung's `com.samsung.android.appwidget.provider`. The Samsung XML identifies `display="sub_screen"`; the standard widget uses the keyguard category. This is a real documented interface, not a guessed private intent. However, the page introduces it specifically for Flip5. [Flex Window widget registration](https://developer.samsung.com/galaxy-z/flex_window.html).[^1]

The official calendar codelab also requires a Flip5. It demonstrates selecting the display for an activity launched from a widget and shows an ongoing-notification integration with a One UI 6.0-specific note. Its roughly square layout dimensions should not be copied into the Flip3's short landscape display. The codelab proves a supported newer-device pattern, not universal coverage across the Flip family. [Samsung codelab](https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html).[^3]

For two fixed buttons, a normal widget would be sufficient if Samsung's host admits it. Android `RemoteViews.setOnClickPendingIntent` associates a view click with a pending intent. Separate immutable broadcast pending intents can identify Yes and No without opening the main activity. This avoids depending on activity-launch routing for the response itself; host admission and lock-screen policy still need verification. [RemoteViews API](https://developer.android.com/reference/android/widget/RemoteViews#setOnClickPendingIntent(int,%20android.app.PendingIntent)).[^4]

### Flip3 support is a different question

A Samsung cover-screen moderator stated in August 2021 that Flip3 cover widgets were limited to Samsung's own apps and features, with no external SDK decision at that time. This is dated first-party evidence explaining the older restrictions, not a claim that every later firmware behaves identically. [Samsung cover-screen team response](https://r1.community.samsung.com/t5/galaxy-z/galaxy-z-flip3-%EC%BB%A4%EB%B2%84%ED%99%94%EB%A9%B4%EC%9D%98-widget-%EA%B0%9C%EB%B0%9C-%EB%AC%B8%EC%9D%98/td-p/12354686).[^5]

Current Samsung support instructions for cover-screen app launching through Good Lock and MultiStar describe Flip5 and later. They are useful for choosing the supported route on newer phones. They do not supersede a direct compatibility test on this Flip3. [Samsung Good Lock instructions](https://www.samsung.com/jp/support/mobile-devices/coverdisplay-goodlock/).[^6]

A minimal native-widget registration probe is reasonable because it is inexpensive and reversible. The acceptance criterion must be that Dark Lord appears in this phone's native cover-widget picker and its buttons execute while folded. Merely installing the APK or seeing its widget on the main home screen is insufficient. If the picker excludes it, do not repeatedly add speculative manifest flags.

### Standard notification actions

Android notifications support up to three action buttons, including actions delivered directly to a `BroadcastReceiver`. This supplies a clean fallback and a cheap compatibility probe: one notification, one question identifier, two explicit actions. It also works on the ordinary notification shade when the cover cannot host the richer surface. [Android notification actions](https://developer.android.com/develop/ui/compose/notifications/create-notification#Actions).[^7]

The limitation is Samsung's cover renderer. Samsung's original Flip3 instructions describe reading notifications while folded, but opening an app or replying requires unfolding. Therefore standard Android action support does not guarantee that arbitrary Yes/No actions will be rendered and clickable on this particular cover. This remains worth measuring, but should not be the only implementation strategy. [Flip3 notification behavior](https://www.samsung.com/in/support/mobile-devices/use-the-informative-cover-screen-on-the-galaxy-z-flip3-5g/).[^8]

On Android 12 and later, notification actions can explicitly require authentication through `setAuthenticationRequired(true)`. That is useful for sensitive responses but does not authenticate touches on a custom overlay; the latter needs its own checked authorization path. [Notification action authentication](https://developer.android.com/develop/ui/compose/notifications).[^9]

## Public GitHub implementations

| Project | What the inspected material demonstrates | Implication for Dark Lord |
| --- | --- | --- |
| SamSprung TooUI | Cover-targeted activity launch plus an application-overlay window | Most relevant Flip3 workaround to study; not a current-device certification |
| FlipWidgets | Samsung widget registration combined with a separate interactive widget-host activity | Useful newer-device example; excessive infrastructure for two buttons |
| SubUI-browser | Historical Flip3 widget allowlist workaround | Patched approach; do not adopt |
| SamSprung-Widget | Older launcher widget with explicit firmware and lock limitations | Historical evidence, not the maintained TooUI approach |

### SamSprung TooUI

The project describes itself as originating on Flip3 and supporting cover apps, notifications, widgets, and toggles. Its own documentation says secure-lock use requires fingerprint unlocking and notes an Android 13 touch-routing issue when minimizing the launcher. These are compatibility warnings to carry into testing, not assurances that Android 15 on this phone works unchanged. [Project documentation](https://samsprung.github.io/launcher/index.html).[^10]

The inspected `launcher` branch revision is `905868baae1f6b57ac5a54d3f70d412ba7f170af`, dated February 17, 2025. `CoverOptions` builds activity options with a display ID and optional launch bounds. `OnBroadcastService` checks overlay permission and starts the cover activity on display 1, including screen-on/user-present paths. This demonstrates how the project separates its service trigger from display targeting. [CoverOptions](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/app/CoverOptions.kt), [OnBroadcastService](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/samsprung/OnBroadcastService.kt).[^11][^12]

`SamSprungOverlay` is an activity that calls `setShowWhenLocked(true)`, changes its window to `TYPE_APPLICATION_OVERLAY`, and installs an interactive layout. It also tracks keyguard state and screen-off events. It is not simply an Android widget and does not obtain blanket ownership of Samsung's lock screen. Dark Lord should borrow the separation of responsibilities, not the full launcher feature set. [Overlay implementation](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/samsprung/SamSprungOverlay.kt).[^13]

A clean implementation using documented Android primitives is preferable to copying the project wholesale. Its license contains custom attribution and redistribution conditions; it should not be assumed to be uniformly Apache-2.0 simply because portions mention Apache terms. Reuse would need a separate license review. [Repository license](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/LICENSE).[^14]

### FlipWidgets

The README advertises Samsung cover-widget integration but its development instructions name Flip7. It also distinguishes the cover preview from the interactive activity opened by tapping that preview. This is not evidence that any arbitrary widget becomes directly interactive inside the Flip3's stock host. [FlipWidgets README](https://github.com/Gh0strab/FlipWidgets).[^15]

At revision `c5c4388527c36b0cdc137f068ba0b883d6e2d291`, the inspected `CoverWidgetProvider` builds `RemoteViews`, configures a collection adapter, and assigns a pending intent to launch `WidgetHostActivity`. That method does not explicitly set `launchDisplayId`, despite the broader README claim. Other lifecycle code or the widget host may supply display routing; this specific provider alone does not prove it. [Provider source](https://github.com/Gh0strab/FlipWidgets/blob/c5c4388527c36b0cdc137f068ba0b883d6e2d291/app/src/main/java/com/flipcover/widgets/CoverWidgetProvider.kt).[^16]

Dark Lord needs neither installed-widget enumeration nor an `AppWidgetHost` for its own two-button question. A directly owned layout is simpler to secure, size, and restore.

### Patched historical approaches

SubUI-browser's maintainer explicitly says its Samsung Health package-name substitution was patched. Do not replace Samsung Health, spoof a Samsung package, or downgrade firmware to recreate it. The older SamSprung-Widget README independently warns that it does not work with Android 12/One UI 4 or firmware verifying Samsung Health, and lists lock-screen limitations. These repositories explain the history, not a viable Android 15 solution. [SubUI-browser](https://github.com/JoelHorrocks/SubUI-browser), [SamSprung-Widget](https://github.com/SamSprung/SamSprung-Widget).[^17][^18]

## Android primitives and their boundaries

### Secondary-display surface

`ActivityOptions.setLaunchDisplayId` is a public API, but display access is checked and devices must support activities on secondary displays. The API documentation points to `ActivityManager.isActivityStartAllowedOnDisplay` for eligibility. Choosing display 1 does not itself grant background-launch permission, wake the cover, or dismiss keyguard. Discover and validate the display at runtime instead of making its numeric ID a portable assumption. [ActivityOptions](https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int)).[^19]

Dark Lord already uses `Presentation`, Android's secondary-display dialog abstraction. A presentation has its own display-appropriate context and resources, making it a legitimate candidate for a compact question panel. The API's existence does not guarantee that Samsung will put it above the cover system UI or deliver touches to it in every lock state. [Presentation reference](https://developer.android.com/reference/android/app/Presentation).[^20]

### A narrowly scoped overlay

Android documents creating a display context, then a window context for `TYPE_APPLICATION_OVERLAY`, and obtaining the window manager from that context. The window type passed when creating the context and adding the view must match. This supplies a public-API route for a standalone question overlay without hosting an entire launcher activity. It remains an engineering proposal until tested on the cover. [Context window API](https://developer.android.com/reference/android/content/Context#createWindowContext(int,%20android.os.Bundle)).[^21]

Application overlays require `SYSTEM_ALERT_WINDOW` authorization. They sit above ordinary activity windows but below critical system windows, and the system may adjust their visibility or position. Android also restricts touches passed through untrusted overlays. Design the question panel to consume its own button taps and disappear when dismissed, instead of relying on transparent full-screen touch forwarding. Never disable untrusted-touch protection as a production workaround. [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams).[^22]

### Background, lock, and wake are independent

Android 14 and 15 tightened pending-intent background-activity launch delegation. A sender or creator opt-in can be necessary, but opt-in is not a new privilege: an eligible launch path must still exist. Current documentation also includes newer SDK-only constants, which must not be copied unguarded into this API-35 device's path. [Activity launch security](https://developer.android.com/guide/components/activities/secure-bal).[^23]

The initial target should be a question arising from an owner-initiated Outside interaction, not an arbitrary background event taking over the display. If the cover is asleep or Samsung refuses the surface, post a normal notification and preserve the question. A failed attempt to show the UI must never be interpreted as No, and certainly never as Yes.

Do not use a fake call or alarm to obtain a full-screen intent for an ordinary question. Android documents those intents for urgent, time-sensitive situations. Dark Lord having telephone capabilities does not make every agent question an incoming call. [Full-screen notification guidance](https://developer.android.com/develop/ui/compose/notifications/create-notification#urgent-message).[^7]

## Existing Dark Lord gaps

These observations come from the local source, not assumptions about Samsung:

1. `CoverAssistantScreen` delegates to the compact `AssistantSurface`; it has voice state and one tap callback, not an explicit question and two response callbacks.
2. `AgentVoiceInteractionSession.onShow()` can create a cover `Presentation`, but `onHide()` dismisses it and destroys its Compose lifecycle owner. A question arriving after speech processing cannot rely on that view remaining alive.
3. `DisplayBackedPostureProvider` labels the phone closed whenever a presentation display exists. Display existence is not equivalent to hinge posture. The current diagnostics demonstrate why display identity, power state, and actual visibility should be reported separately.
4. The conversation harness returns final text or executes tools; the inspected contracts do not expose a general durable Yes/No question lifecycle. Existing approval/escalation mechanisms should be integrated where applicable, not bypassed by sending an unqualified “yes” into an unrelated turn.

Relevant files are [AgentVoiceInteractionSession](../../oem/samsung-flip3/src/main/kotlin/com/fsaint/androidagent/oem/samsungflip3/AgentVoiceInteractionSession.kt), [AndroidDisplayProvider](../../oem/samsung-flip3/src/main/kotlin/com/fsaint/androidagent/oem/samsungflip3/AndroidDisplayProvider.kt), [CoverAssistantScreen](../../app/src/main/kotlin/com/fsaint/androidagent/ui/CoverAssistantScreen.kt), and [ConversationHarness](../../core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/ConversationHarness.kt).

The most consequential fix is therefore a question lifecycle independent of the short voice-input session. Changing only the cover layout would leave the waiting, resumption, cancellation, and identity problems unresolved.

## Proposed question contract

The following is an architectural recommendation, not an implemented feature.

Persist a `PendingQuestion` with an unpredictable identifier, owner and chat IDs, originating request ID, short question text, fixed Yes/No choices, expiry, state, and a continuation reference. Add a revision or action fingerprint if an approval authorizes a concrete side effect. The displayed choice must refer to that exact persisted record, not whichever conversation happens to be most recent.

The lifecycle should be explicit:

```text
Create question → persist WAITING → show cover panel / notification
                       ├─ Yes → atomically record answer → resume once
                       ├─ No  → atomically record answer → decline once
                       ├─ expiry → EXPIRED, no action
                       └─ superseded/cancelled → no stale continuation
```

After recording an answer, disable both buttons and show a short acknowledgment. A duplicate tap, a second notification action, or a voice answer racing a touch must lose the same atomic state transition. The harness should resume from durable state rather than keep a model request or coroutine suspended indefinitely while waiting for the person.

Use an immutable explicit pending intent per question and choice; extras alone do not distinguish pending intents. This prevents a stale Yes button answering a newer question. [Pending-intent identity caution](https://developer.android.com/develop/ui/compose/notifications/create-notification#reply-action).[^7] The recommended receiver validation checks the current owner, waiting state, and expiry before accepting any response.

For ordinary detail requests, Yes can resume the same Outside context with the resolved meaning “provide the full version of this answer.” For authorizing an action, the continuation should name the exact action and recheck policy and authentication. A generic model-generated follow-up question is not automatically an owner-approval token.

Sensitive content should remain hidden until the applicable owner-authentication requirement is met. A tap on a locked phone proves physical interaction, not identity. No and dismiss can safely cancel many requests while locked; Yes to a sensitive operation should require an authenticated owner path. Never globally remove the screen lock to make the prototype appear successful.

## Interaction design

Use one short question above two large, plainly labeled buttons. For the initial harmless test, show “Want the full version?” with Yes and No. Keep raw MCP output, email subjects, identifiers, and debug details off the cover by default. The full context can remain in the Outside chat.

The panel should be temporary, not a permanent home-screen replacement. It should survive voice-session hide, but close on answer, explicit dismiss, expiry, or a superseding interaction. Folding and unfolding should move access to the same pending question rather than create a duplicate. A side-button interruption should stop current speech immediately while preserving or explicitly cancelling the pending question according to one consistent policy.

For consequential approvals, avoid labels whose meaning is ambiguous. “Send message?” must include enough authenticated context to identify the recipient and message, or direct the owner to the full review screen. The cover's small size is not a reason to omit material approval details.

## Experiment sequence and acceptance criteria

| Experiment | Passing evidence | Decision if it fails |
| --- | --- | --- |
| Native notification actions | Both buttons visibly rendered; a physical tap delivers the correct question ID | Keep notification as alert/fallback only |
| Existing Presentation probe | Harmless question appears on cover and real Yes/No touches arrive | Record window/display failure; try the scoped overlay |
| Display-scoped overlay | Visible and touchable on the cover, main screen unaffected | Investigate Samsung window policy; do not weaken keyguard |
| Native Samsung widget probe | Widget appears in Flip3 cover picker and its buttons work | Use documented widget route only on verified newer devices |
| Delayed model question | Panel remains or appears after voice session hides | Separate panel lifetime from voice session |
| Durable response | One continuation despite double tap, voice race, or process recreation | Fix question store/resumption before real actions |

Test folded-and-authenticated separately from folded-and-securely-locked. Also cover screen-off, fingerprint unlock, unfold during a pending question, rotation/density changes, process restart, revoked overlay permission, expired questions, and no network. For each case, record the actual display/window identity, visibility, response event, and continuation count. Do not record personal question text or tool payloads in routine diagnostics.

ADB-injected taps are useful for wiring tests but are not a substitute for a real finger on the cover. Likewise, an ADB activity launch is not proof that the app can launch the surface independently. The final acceptance run must work without the development computer or a shell-backed helper.

If the scoped overlay works only after fingerprint authentication, report that limitation explicitly and decide whether it meets the intended experience. If nothing can receive touch while securely locked, preserve lock security and offer voice or authenticated cover interaction instead. A successful notification banner should not be relabeled as interactive cover support.

## Conclusion

There is enough evidence to justify a focused Flip3 prototype, but not a promise of universal locked-cover support. Samsung's official newer-device widgets, Android's secondary-display primitives, and SamSprung's public implementation together identify realistic paths. The lowest-risk product design is a persistent question record with interchangeable cover and notification renderers, starting with “Want the full version?” and adding sensitive approvals only after identity and exactly-once continuation are proven.

## Sources

Public sources accessed September 10, 2026 unless otherwise noted. Version-specific claims are limited to the cited source or inspected revision.

[^1]: Samsung Developer, [Flex Window](https://developer.samsung.com/galaxy-z/flex_window.html), undated current documentation, explicitly Flip5.
[^2]: Dark Lord, [Samsung Side-button registration and folded camera access](samsung-side-button-folded-camera.md), September 9, 2026; supplemented by September 10 read-only display diagnostics. Local device evidence, not a public compatibility certification.
[^3]: Samsung Developer, [Develop a widget for Flex Window](https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html), current codelab, Flip5 prerequisite.
[^4]: Google, [RemoteViews.setOnClickPendingIntent](https://developer.android.com/reference/android/widget/RemoteViews#setOnClickPendingIntent(int,%20android.app.PendingIntent)), Android API reference.
[^5]: Samsung cover-screen moderator, [Flip3 cover-widget SDK response](https://r1.community.samsung.com/t5/galaxy-z/galaxy-z-flip3-%EC%BB%A4%EB%B2%84%ED%99%94%EB%A9%B4%EC%9D%98-widget-%EA%B0%9C%EB%B0%9C-%EB%AC%B8%EC%9D%98/td-p/12354686), August 30, 2021, Samsung Members; historical first-party statement.
[^6]: Samsung Japan, [Using applications on Galaxy Z Flip cover screens](https://www.samsung.com/jp/support/mobile-devices/coverdisplay-goodlock/), current support article; Flip5-and-later scope.
[^7]: Google, [Create a notification](https://developer.android.com/develop/ui/compose/notifications/create-notification), updated September 1, 2026; actions, pending-intent identity, and urgent full-screen intents.
[^8]: Samsung India, [Use the informative Cover screen on the Galaxy Z Flip3 5G](https://www.samsung.com/in/support/mobile-devices/use-the-informative-cover-screen-on-the-galaxy-z-flip3-5g/), August 20, 2021; historical model-specific behavior.
[^9]: Google, [About notifications](https://developer.android.com/develop/ui/compose/notifications), current documentation; action authentication on API 31+.
[^10]: SamSprung, [Launcher documentation](https://samsprung.github.io/launcher/index.html), developer-maintained usage and compatibility notes.
[^11]: SamSprung, [CoverOptions.kt](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/app/CoverOptions.kt), pinned source; revision dated February 17, 2025.
[^12]: SamSprung, [OnBroadcastService.kt](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/samsprung/OnBroadcastService.kt), same revision.
[^13]: SamSprung, [SamSprungOverlay.kt](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/app/src/main/java/com/eightbit/samsprung/SamSprungOverlay.kt), same revision.
[^14]: SamSprung, [LICENSE](https://github.com/SamSprung/SamSprung-TooUI/blob/905868baae1f6b57ac5a54d3f70d412ba7f170af/LICENSE), same revision; custom terms.
[^15]: Gh0strab, [FlipWidgets README](https://github.com/Gh0strab/FlipWidgets), public repository; development instructions identify Flip7.
[^16]: Gh0strab, [CoverWidgetProvider.kt](https://github.com/Gh0strab/FlipWidgets/blob/c5c4388527c36b0cdc137f068ba0b883d6e2d291/app/src/main/java/com/flipcover/widgets/CoverWidgetProvider.kt), pinned inspected source.
[^17]: JoelHorrocks, [SubUI-browser](https://github.com/JoelHorrocks/SubUI-browser), maintainer's explicit patched-workaround notice.
[^18]: SamSprung, [SamSprung-Widget](https://github.com/SamSprung/SamSprung-Widget), historical repository compatibility notice.
[^19]: Google, [ActivityOptions.setLaunchDisplayId](https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int)), Android API reference, API 26+.
[^20]: Google, [Presentation](https://developer.android.com/reference/android/app/Presentation), Android secondary-display dialog reference.
[^21]: Google, [Context.createWindowContext](https://developer.android.com/reference/android/content/Context#createWindowContext(int,%20android.os.Bundle)), Android API reference, API 30+.
[^22]: Google, [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams), overlay window and touch-delivery contracts.
[^23]: Google, [Activity security](https://developer.android.com/guide/components/activities/secure-bal), updated May 18, 2026; use API-specific sections applicable to the target device.
