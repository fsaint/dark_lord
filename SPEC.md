Android Agent

Prototype Technical Specification v0.1

1. Objective

Build a fully capable autonomous agent that runs directly on an Android phone.

The phone is not merely a remote-controlled Android device. It is an agent computing platform capable of observing events, reasoning about them, interacting with applications and hardware, communicating with people, invoking external services through MCP, and executing reusable skills.

The prototype:

* is sideloaded;
* does not target Google Play;
* does not require root;
* targets Android 12+;
* may use Developer Mode/ADB for installation, provisioning, testing, and debugging;
* should operate when its application UI is not visible;
* may use a remote LLM;
* should be architected so local models can be added later.

The fundamental execution model is:

EVENT / USER REQUEST
        ↓
IDENTITY + SCOPE
        ↓
SCOPED AGENT
        ↓
SKILLS + MEMORY + TOOLS + MCP
        ↓
ACTION
        ↓
OBSERVATION
        ↓
VERIFICATION
        ↓
RESPONSE / NEXT ACTION

⸻

2. Product Principle

Android should be exposed to the agent as a collection of semantic capabilities.

Prefer:

sms.send("+14155551212", "I'll be there at 5")

over:

Open Messages
Find conversation
Tap text field
Type
Find Send
Tap

Execution priority:

1. Native Android API
2. Device management API
3. Accessibility semantic UI
4. Screenshot + vision
5. Coordinate gestures

UI automation is a general fallback for applications without direct APIs.

⸻

3. System Architecture

┌─────────────────────────────────────────────────────┐
│                    ANDROID PHONE                    │
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │                 Agent Runtime                 │  │
│  │                                               │  │
│  │  Event Router                                 │  │
│  │  Identity Resolver                            │  │
│  │  Scope Resolver                               │  │
│  │  Sub-Agent Manager                            │  │
│  │  Context Builder                              │  │
│  │  Planner                                      │  │
│  │  Policy Engine                                │  │
│  │  Memory                                       │  │
│  │  Skills Runtime                               │  │
│  │  Scheduler                                    │  │
│  └───────────────────────┬───────────────────────┘  │
│                          │                          │
│                  ┌───────▼───────┐                  │
│                  │  Tool Router  │                  │
│                  └───────┬───────┘                  │
│                          │                          │
│       ┌──────────────────┼──────────────────┐       │
│       ▼                  ▼                  ▼       │
│ Android             MCP Clients           Skills    │
│ Capabilities                                         │
│       │                                              │
│       ▼                                              │
│ Android OS / Apps / Radios / Sensors / Hardware      │
│                                                      │
│                 MCP SERVER                           │
│                     │                                │
└─────────────────────┼────────────────────────────────┘
                      │
               Remote agents/tools

The Android harness operates simultaneously as:

1. an autonomous local agent runtime;
2. an MCP client;
3. an MCP server exposing the phone;
4. an event processor;
5. a host for scoped sub-agents.

⸻

4. Core Modules

Recommended repository:

android-agent/
├── app/
├── core/
│   ├── agent/
│   ├── events/
│   ├── identity/
│   ├── scopes/
│   ├── subagents/
│   ├── tools/
│   ├── permissions/
│   ├── policy/
│   ├── memory/
│   ├── scheduler/
│   ├── skills/
│   └── mcp/
├── capabilities/
│   ├── accessibility/
│   ├── apps/
│   ├── screen/
│   ├── notifications/
│   ├── sms/
│   ├── telephony/
│   ├── contacts/
│   ├── bluetooth/
│   ├── wifi/
│   ├── camera/
│   ├── microphone/
│   ├── audio/
│   ├── location/
│   ├── sensors/
│   ├── nfc/
│   ├── usb/
│   ├── files/
│   └── device/
├── platform/
│   ├── aosp/
│   ├── pixel/
│   ├── samsung/
│   └── other/
├── skills/
├── mcp-server/
├── tests/
└── docs/

⸻

5. Capability Plugin System

Android functionality must not be hard-coded into the agent runtime.

Every capability implements a common plugin interface.

Conceptually:

interface AgentCapability {
    val id: String
    val version: String
    suspend fun initialize(context: Context)
    fun tools(): List<AgentTool>
    fun events(): Flow<AgentEvent>
    fun status(): CapabilityStatus
}

Examples:

SmsCapability
TelephonyCapability
BluetoothCapability
WifiCapability
CameraCapability
MicrophoneCapability
AccessibilityCapability
NotificationCapability
LocationCapability
SensorCapability

Capabilities contribute:

TOOLS
+
EVENTS
+
STATUS

The agent runtime should have no special knowledge of Bluetooth, SMS, cameras, etc.

⸻

6. Capability Discovery

Because Android devices differ, the harness must inspect the actual device.

Tool:

capabilities.inspect()

Example:

{
  "sms": true,
  "telephony": true,
  "accessibility": true,
  "camera": true,
  "microphone": true,
  "bluetooth": true,
  "ble": true,
  "wifiScan": true,
  "nfc": true,
  "usbHost": true,
  "barometer": false,
  "deviceOwner": false,
  "callAudioCapture": false
}

Agents and skills must query capabilities rather than assume they exist.

⸻

7. Event Bus

The agent is primarily event-driven.

Android Event
      ↓
Capability
      ↓
AgentEvent
      ↓
Event Router
      ↓
Agent

Generic event:

{
  "id": "evt_123",
  "type": "sms.received",
  "source": "sms",
  "timestamp": 1788034532,
  "payload": {}
}

Events must be persisted until processed successfully.

⸻

8. Required Events

SMS

sms.received
sms.sent
sms.delivered
sms.failed

Calls

call.ringing
call.outgoing
call.active
call.held
call.disconnected
call.audioRouteChanged

Notifications

notification.received
notification.updated
notification.removed

Bluetooth

bluetooth.found
bluetooth.connected
bluetooth.disconnected
bluetooth.bondChanged
bluetooth.gattChanged

Wi-Fi

wifi.scanUpdated
wifi.connected
wifi.disconnected
wifi.networkChanged

Device

device.booted
device.screenOn
device.screenOff
device.unlocked
device.batteryChanged
device.chargingChanged

Sensors

location.changed
motion.detected
proximity.changed
nfc.detected
usb.attached
usb.detached

Agent

agent.taskStarted
agent.taskCompleted
agent.taskFailed
skill.installed
skill.updated
skill.failed
owner.responseReceived

⸻

9. MCP

MCP is a first-class protocol in the harness.

The phone must act as both MCP client and MCP server.

MCP Server

Expose phone functionality:

android.device.*
android.apps.*
android.ui.*
android.screen.*
android.notifications.*
android.sms.*
android.phone.*
android.bluetooth.*
android.wifi.*
android.camera.*
android.microphone.*
android.audio.*
android.location.*
android.contacts.*
android.files.*
android.sensors.*
android.nfc.*
android.usb.*

Example remote invocation:

Android MCP
camera.capture()

returns an image captured by the physical phone.

MCP Client

The agent may connect to arbitrary configured MCP servers.

Examples:

email
calendar
GitHub
Home Assistant
databases
filesystems
business systems
other agents
other phones
IoT infrastructure

MCP connections should be configurable without rebuilding the APK.

⸻

10. Applications

Tools:

apps.list()
apps.search()
apps.info()
apps.launch()
apps.openSettings()

The agent should launch applications directly through Android rather than through UI automation.

⸻

11. Accessibility / General UI Control

AccessibilityService provides the generic app-control layer.

Tools:

ui.inspect()
ui.windows()
ui.find()
ui.click()
ui.longClick()
ui.type()
ui.clear()
ui.scroll()
ui.swipe()
ui.tap()
ui.back()
ui.home()
ui.recents()
ui.waitFor()
ui.exists()

The accessibility representation should expose:

text
contentDescription
role
class
resourceId
bounds
clickable
scrollable
editable
enabled
selected
focused
children

Selectors:

text
partial text
resource ID
role
class
content description
hierarchy
screen region

⸻

12. Vision Fallback

When accessibility information is insufficient:

ui.inspect()
    ↓
insufficient
    ↓
screen.capture()
    ↓
vision model
    ↓
identify target
    ↓
ui.tap(x,y)

Secure Android windows may not be capturable.

The capability must report this rather than pretending capture succeeded.

⸻

13. SMS

The prototype should become the phone’s default SMS application.

Tools:

sms.send()
sms.reply()
sms.latest()
sms.messages()
sms.thread()
sms.threads()
sms.search()
sms.markRead()
sms.deliveryStatus()

Incoming messages immediately generate:

sms.received

No polling should be required.

⸻

14. Telephony

The harness should become the default dialer where supported and integrate with Android Telecom/InCallService.

Tools:

phone.dial()
phone.answer()
phone.reject()
phone.hangup()
phone.hold()
phone.unhold()
phone.mute()
phone.unmute()
phone.calls()
phone.currentCall()
phone.history()
phone.audioRoutes()
phone.setAudioRoute()

Incoming calls generate:

call.ringing

The agent can therefore autonomously decide whether to:

answer
reject
silence
allow ringing
notify owner

⸻

15. Call Audio

The prototype does not assume access to both sides of ordinary cellular call audio.

Expected:

Call detection        YES
Dial                  YES
Answer                YES
Reject                YES
Hang up               YES
Call state            YES
Audio routing         YES
Two-way call capture  NOT GUARANTEED
Conversational PSTN
voice agent           NOT MVP

This capability should be abstracted so a future privileged/root implementation can replace it.

⸻

16. Notifications

Use NotificationListenerService.

Tools:

notifications.list()
notifications.latest()
notifications.search()
notifications.dismiss()
notifications.open()
notifications.actions()
notifications.invokeAction()

This provides generic integration with applications that have no direct API.

⸻

17. Bluetooth

Support Classic Bluetooth and BLE.

Tools:

bluetooth.scan()
bluetooth.stopScan()
bluetooth.devices()
bluetooth.paired()
bluetooth.inspect()
bluetooth.connect()
bluetooth.disconnect()
bluetooth.pair()
bluetooth.unpair()

BLE:

bluetooth.gatt.services()
bluetooth.gatt.characteristics()
bluetooth.gatt.read()
bluetooth.gatt.write()
bluetooth.gatt.subscribe()
bluetooth.gatt.unsubscribe()

The agent should be able to discover unknown BLE devices and inspect exposed GATT services.

⸻

18. Wi-Fi

Tools:

wifi.scan()
wifi.networks()
wifi.current()
wifi.inspect()
wifi.connect()
wifi.disconnect()
wifi.signal()
wifi.ip()
wifi.gateway()

Network observations should expose where Android permits:

SSID
BSSID
RSSI
frequency
security capabilities

Some new connections may require Android user confirmation.

⸻

19. Camera

Treat the camera as an agent sensor.

Tools:

camera.list()
camera.capture()
camera.startPreview()
camera.stopPreview()
camera.startVideo()
camera.stopVideo()
camera.setZoom()
camera.setFocus()
camera.setTorch()

Higher-level skills/tools may provide:

camera.describe()
camera.readText()
camera.readQr()
camera.detectObjects()

Example:

camera.capture()
       ↓
vision
       ↓
"There is a package at the front door."

⸻

20. Microphone

Tools:

microphone.record()
microphone.start()
microphone.stop()
microphone.stream()
microphone.level()

Higher-level processing:

audio.transcribe()
audio.detectSpeech()
audio.detectWakeWord()

Preferred continuous architecture:

Microphone
    ↓
local VAD / wake word
    ↓
speech segment
    ↓
STT
    ↓
Agent

⸻

21. Speaker / Audio

Tools:

audio.play()
audio.stop()
audio.tts()
audio.volume()
audio.outputDevices()
audio.setOutputDevice()

⸻

22. Location

Tools:

location.current()
location.lastKnown()
location.watch()
location.stopWatch()
location.geofence()

Events:

location.changed
location.enteredGeofence
location.exitedGeofence

⸻

23. Sensors

Generic interface:

sensors.list()
sensors.read()
sensors.subscribe()
sensors.unsubscribe()

Possible sensors include:

accelerometer
gyroscope
magnetometer
barometer
ambient light
proximity
orientation
step detector
gravity
linear acceleration

⸻

24. NFC

Tools:

nfc.status()
nfc.readTag()
nfc.writeTag()
nfc.watch()

Event:

nfc.detected

⸻

25. USB

Android USB Host capabilities:

usb.devices()
usb.inspect()
usb.connect()
usb.disconnect()
usb.read()
usb.write()

Events:

usb.attached
usb.detached

⸻

26. Contacts

Tools:

contacts.search()
contacts.lookup()
contacts.phoneNumbers()
contacts.emailAddresses()

Contacts are also used by the identity system.

⸻

27. Files

Expose only files Android permits the application to access.

files.list()
files.search()
files.read()
files.write()
files.delete()
files.move()
files.copy()

Other applications’ private storage is outside the no-root prototype’s expected capabilities.

⸻

28. Skills

A tool is an atomic capability.

A skill is reusable procedural knowledge describing how the agent accomplishes a goal.

Example tool:

calendar.availability()

Example skill:

schedule-meeting
1. Identify participants.
2. Query availability.
3. Find mutually available slots.
4. Ask required decision makers.
5. Create meeting.
6. Notify participants.

⸻

29. Skill Package

Suggested structure:

skills/
  schedule-meeting/
    skill.yaml
    instructions.md
    examples/
    tests/

Example:

id: schedule-meeting
name: Schedule Meeting
version: 1.2.0
requires:
  tools:
    - calendar.availability
    - calendar.create
allowed_scopes:
  - owner
  - coworker
entrypoint: instructions.md

⸻

30. Skill Registry

Skills can originate from:

bundled skills
Git repository
HTTP registry
MCP server
private registry
user-created skills

Tools:

skills.list()
skills.search()
skills.inspect()
skills.install()
skills.remove()
skills.enable()
skills.disable()

⸻

31. Skill Updates

Skills must update independently of the Android APK.

skills.checkUpdates()
skills.update()
skills.updateAll()
skills.rollback()

Update flow:

registry
   ↓
manifest
   ↓
download
   ↓
validate
   ↓
install
   ↓
test
   ↓
activate

A failed update must leave the previous working skill available.

⸻

32. Memory

Memory should be separated into:

working memory
conversation memory
task memory
principal memory
shared memory
device state
long-term semantic memory

Possible implementation:

Room / SQLite
encrypted local storage
vector index
Android Keystore for secrets

⸻

33. Scoped Sub-Agents

Every incoming human interaction runs within a scope.

The central model is:

PHONE NUMBER
     ↓
PRINCIPAL
     ↓
ROLE
     ↓
SCOPE
     ↓
SCOPED SUB-AGENT

For the prototype, normalized phone number is sufficient for principal identification.

⸻

34. Principal Registry

Example:

principals:
  boss:
    name: Boss
    phone: "+14155551212"
    role: owner
    scope: owner
  alice:
    name: Alice
    phone: "+14155559876"
    role: coworker
    scope: coworker
  plumber:
    name: Bob
    phone: "+14155553333"
    role: contractor
    scope: contractor

Unknown numbers automatically become:

principal = anonymous
scope = unknown

⸻

35. Initial Scope Model

The prototype needs only three fundamental levels:

OWNER
KNOWN
UNKNOWN

Known principals may additionally have role-specific configurations.

⸻

36. Owner Scope

Owner receives effectively full agent capability:

Android tools
MCP servers
skills
memory
files
communications
hardware
scope management
principal management
skill management

Example:

Owner SMS:
"Take a picture and tell me what you see."
→ camera.capture
→ vision
→ sms.reply

⸻

37. Known Scope

Known people receive role-specific capabilities.

Example coworker:

ALLOW
calendar.availability
sms.reply
contacts.lookup
meeting coordination
DENY
private email
private calendar details
camera
microphone
location
private files
home automation

Example:

Alice:
"When is the boss available tomorrow?"

Agent may access:

calendar.availability

but not reveal private event details.

Response:

"He's available between 2:00 and 3:30."

rather than revealing what private meetings occur around that window.

⸻

38. Unknown Scope

Unknown callers receive minimal capabilities.

Allow:

conversation
basic public responses
message taking
sms.reply
owner notification

Deny:

private memory
calendar
email
location
camera
microphone
files
contacts
Bluetooth control
Wi-Fi control
private MCP servers

Example:

Unknown:
"Where is the boss?"
→ location.current DENIED

⸻

39. Scoped Agent Session

Each conversation creates or resumes a scoped session.

data class ScopedAgentSession(
    val id: String,
    val principalId: String,
    val role: String,
    val scopeId: String,
    val channel: Channel,
    val memoryNamespace: String
)

Channels initially:

SMS
PHONE
LOCAL
MCP

⸻

40. Scope Resolution

Incoming SMS:

sms.received
      ↓
normalize number
      ↓
PrincipalRegistry.lookup()
      ↓
resolve role
      ↓
resolve scope
      ↓
resume/create sub-agent
      ↓
process message

Calls use the same mechanism.

⸻

41. Scoped Resources

A scope determines:

tools
MCP servers
skills
memory
files
contacts
communications
hardware
autonomy
escalation rights

Example:

scopes:
  owner:
    tools: "*"
    mcp: "*"
    skills: "*"
    memory: "*"
  coworker:
    tools:
      - calendar.availability
      - sms.reply
    mcp:
      - work_calendar
    skills:
      - scheduling
    memory:
      - coworker_shared
  unknown:
    tools:
      - sms.reply
      - owner.notify
    mcp: []
    skills:
      - message-taking
    memory:
      - current_session

⸻

42. Hard Scope Enforcement

Scope enforcement must occur below the LLM.

Sub-Agent
    ↓
requests tool
    ↓
Scoped Tool Router
    ↓
Authorization Engine
    ↓
ALLOW / DENY

The model cannot escape its scope by asking for another tool.

The same applies independently to:

tools
MCP
memory
skills

⸻

43. Scoped MCP

Example:

Coworker agent
CAN ACCESS:
work_calendar
CANNOT ACCESS:
personal_email
private_files
Home Assistant
other private MCP servers

MCP must not provide a path around scope restrictions.

⸻

44. Scoped Skills

Skill availability is filtered before the model sees skills.

installed skills
       ↓
scope filter
       ↓
available skills
       ↓
agent

Example:

id: control-home
allowed_scopes:
  - owner

An unknown sub-agent should not even receive this skill as an available option.

⸻

45. Scoped Memory

Memory namespaces:

memory/
    owner/
    alice/
    plumber/
    anonymous/session-123/
shared/
    work/
    family/
    project-alpha/

A sub-agent receives only its authorized namespaces.

Private information should be excluded during context construction rather than included with instructions not to reveal it.

⸻

46. Owner Escalation

Known and unknown sub-agents can reach out to the owner when appropriate.

Primary tool:

owner.ask()

Example:

Alice:
"Can we move the deadline to Friday?"

Alice’s agent cannot approve.

It executes:

owner.ask(
  question = "Can Alice move Project Alpha's deadline to Friday?",
  context = "Alice is coordinating Project Alpha."
)

⸻

47. Escalation Object

{
  "id": "esc_123",
  "principal": "alice",
  "session": "session_881",
  "question": "Can Project Alpha move to Friday?",
  "reason": "Decision outside coworker scope",
  "status": "pending"
}

The owner receives the request through a configured channel.

Initially this can simply be:

Android notification

or:

SMS

⸻

48. Asynchronous Escalation

The agent must not require the owner to respond immediately.

Example:

Alice:
"Can we move the deadline?"
Agent:
"I need approval for that. I'll ask and get back to you."

Then:

create escalation
      ↓
persist session
      ↓
contact owner
      ↓
wait
      ↓
owner answers
      ↓
resume Alice sub-agent
      ↓
send Alice response

⸻

49. Owner Response

The owner can:

approve
deny
provide an answer
modify proposed response
delegate authority

Example:

Owner:
"Tell Alice Friday is fine."

The harness resolves the outstanding escalation and resumes the originating sub-agent.

⸻

50. Temporary Delegation

Future prototype iteration should support:

"Let Alice approve Project Alpha schedule changes until Friday."

Represented as:

principal: alice
allow:
  - project-alpha.schedule.modify
expires:
  2026-09-04T23:59:59

Delegation expires automatically.

⸻

51. Owner Communication Tools

owner.ask()
owner.notify()
owner.requestApproval()
owner.pendingRequests()

These tools provide communication with the owner without exposing the owner’s private agent context.

⸻

52. Example: Owner SMS

Owner:
"What's the battery?"
        ↓
identify owner
        ↓
Owner Sub-Agent
        ↓
device.battery()
        ↓
sms.reply("Battery is 72%.")

⸻

53. Example: Known Person

Alice:
"When is the boss free tomorrow?"
        ↓
Alice
        ↓
coworker scope
        ↓
calendar.availability()
        ↓
sms.reply(
  "He's available 10–11 and 2–3:30."
)

⸻

54. Example: Known Person Escalation

Alice:
"Can we move tomorrow's meeting to 4?"
        ↓
coworker scope
        ↓
decision requires owner
        ↓
owner.ask()
        ↓
Owner:
"Yes."
        ↓
resume Alice session
        ↓
calendar.reschedule()
        ↓
sms.reply("Yes. I've moved it to 4.")

⸻

55. Example: Unknown Person

Unknown:
"What's the boss's location?"
        ↓
unknown scope
        ↓
location.current unavailable
        ↓
"I can't provide that information."

⸻

56. Example: Unknown Caller

An unknown caller may interact with a limited receptionist-style sub-agent.

It can:

identify caller
collect reason for call
take message
request callback information
notify owner

It cannot:

query private memory
access location
access private calendar
control hardware
access private MCP services

⸻

57. Scheduler

Support:

one-time tasks
recurring tasks
event-triggered tasks
conditional tasks

Examples:

"When these headphones connect, open Spotify."
"When Alice texts, process it with her scoped agent."
"At 8 AM give me my schedule."
"When I arrive home, invoke the home-arrival skill."

⸻

58. Agent Loop

Event / Goal
     ↓
Identify Principal
     ↓
Resolve Scope
     ↓
Create/Resume Sub-Agent
     ↓
Build Scoped Context
     ↓
Find Available Skills
     ↓
Plan
     ↓
Select Tool
     ↓
Scope Check
     ↓
Execute
     ↓
Observe
     ↓
Verify
     ↓
Continue / Escalate / Complete

⸻

59. Tool Result Contract

Every tool returns structured status.

Example:

{
  "success": false,
  "error": "PERMISSION_REQUIRED",
  "recoverable": true,
  "message": "Bluetooth scan permission is required."
}

Standard errors:

UNSUPPORTED
NOT_FOUND
PERMISSION_REQUIRED
SCOPE_DENIED
USER_CONFIRMATION_REQUIRED
DEVICE_BUSY
TIMEOUT
NETWORK_ERROR
APP_NOT_RUNNING
SECURE_WINDOW
OS_RESTRICTED

⸻

60. Background Operation

Use Android-supported persistent mechanisms:

Foreground Services
WorkManager
BroadcastReceiver
AccessibilityService
NotificationListenerService
InCallService
SMS receiver
boot receiver

After reboot:

BOOT
 ↓
initialize core
 ↓
load principals/scopes
 ↓
load skills
 ↓
initialize capabilities
 ↓
restore scheduled tasks
 ↓
reconnect MCP
 ↓
ready

⸻

61. Model Architecture

Initial version:

Android
   ↓
remote LLM API

Local processing should be used where useful for:

VAD
wake word
event filtering
simple classification
embeddings
basic routing

Later:

small local LLM

can provide offline operation.

The model provider should not be hard-coded into capabilities or skills.

⸻

62. Audit Log

Record autonomous operations.

{
  "time": "...",
  "event": "sms.received",
  "principal": "alice",
  "scope": "coworker",
  "skill": "schedule-meeting",
  "tool": "calendar.availability",
  "result": "success"
}

Tools:

audit.list()
audit.search()
audit.export()

This is particularly important for debugging sub-agent behavior.

⸻

63. Debug Interface

Development build should expose:

event monitor
event injection
tool inspector
capability inspector
scope inspector
principal inspector
MCP inspector
skill inspector
memory inspector
agent trace
permission status
audit log

Mock event:

{
  "type": "sms.received",
  "from": "+15551234567",
  "text": "What's your battery?"
}

This allows most agent behavior to be tested without generating real cellular events.

⸻

64. One-Phone Prototype Testing

Only one Android device is required.

Other endpoints can be:

an iPhone
VoIP number
computer
router
Bluetooth headphones
BLE device

Initial acceptance tests:

01 Launch application
02 Inspect accessibility tree
03 Click application control
04 Enter text
05 Capture screenshot
06 Scan Wi-Fi
07 Scan Bluetooth
08 Connect Bluetooth peripheral
09 Capture photo
10 Record microphone
11 Receive notification event
12 Receive SMS
13 Resolve sender scope
14 Respond automatically
15 Receive call
16 Resolve caller scope
17 Answer/reject/hang up
18 Operate with screen off
19 Recover after reboot
20 Connect external MCP
21 Execute skill
22 Update skill
23 Create owner sub-agent
24 Create known sub-agent
25 Create unknown sub-agent
26 Enforce scope denial
27 Escalate known-agent question to owner
28 Resume conversation after owner response

⸻

65. Primary Prototype Scenario

Owner sends:

"Take a picture and tell me what you see."

Execution:

sms.received
     ↓
resolve phone number
     ↓
OWNER
     ↓
Owner Sub-Agent
     ↓
camera.capture()
     ↓
vision
     ↓
sms.reply(description)

This validates:

external event
→ identity
→ scope
→ agent
→ hardware
→ model
→ response

⸻

66. Scope Scenario

Three SMS messages:

Owner

"What's the battery?"

Expected:

OWNER
→ device.battery
→ response

Known coworker

"When is the boss available tomorrow?"

Expected:

KNOWN / COWORKER
→ calendar.availability
→ response

Unknown

"Where is the boss?"

Expected:

UNKNOWN
→ location.current = SCOPE_DENIED
→ safe response

This is the minimum test proving sub-agent isolation.

⸻

67. Escalation Scenario

Known coworker:

"Can we move tomorrow's meeting to 4?"

Execution:

sms.received
      ↓
identify coworker
      ↓
Coworker Sub-Agent
      ↓
requires owner decision
      ↓
owner.ask()
      ↓
owner receives notification
      ↓
"Yes"
      ↓
resolve escalation
      ↓
resume Coworker Sub-Agent
      ↓
perform authorized action
      ↓
reply to coworker

This is the minimum test proving delegated agency.

⸻

68. Prototype MVP

Do not implement every capability initially.

MVP A — Agent substrate

Implement:

EventBus
Tool Registry
Capability Registry
Agent Runtime
MCP Server
MCP Client
Skills Registry
Memory

MVP B — Android interaction

Implement:

device.battery
apps.launch
ui.inspect
ui.click
ui.type
screen.capture
camera.capture
notifications

MVP C — Communications

Implement:

sms.received
sms.send
call.ringing
phone.answer
phone.reject
phone.hangup

MVP D — Scoped agents

Implement:

PrincipalRegistry
ScopeRegistry
ScopedAgentSession
ScopedToolRouter
ScopedMemoryProvider
ScopedMcpRouter
OWNER
KNOWN
UNKNOWN
owner.ask
asynchronous escalation

MVP E — Physical environment

Implement:

Bluetooth
BLE
Wi-Fi
microphone
audio
location
sensors
NFC
USB

MVP F — Extensibility

Implement:

skill installation
skill updates
skill rollback
dynamic MCP configuration
scheduler
Device Owner provisioning
OEM adapters

⸻

69. Known No-Root Boundary

Expected capability:

SMS                          STRONG
Call control                 STRONG
Call events                  STRONG
Notifications                STRONG
App UI control               STRONG
Screen inspection            STRONG
Camera                       STRONG
Microphone                   STRONG
Bluetooth/BLE                STRONG
Wi-Fi                        GOOD/STRONG
Location                     STRONG
Sensors                      STRONG
NFC                          DEVICE DEPENDENT
USB                          DEVICE DEPENDENT
Background operation         GOOD
Other-app private data       BLOCKED
Secure screenshots           BLOCKED
Secure system settings       LIMITED
Cellular call audio          LIMITED/BLOCKED
Kernel access                BLOCKED

Root is explicitly outside v0.1.

The architecture should nevertheless allow privileged capability implementations later.

⸻

70. Success Definition

The prototype succeeds when the phone can independently:

PERCEIVE
   ↓
IDENTIFY WHO IT IS DEALING WITH
   ↓
SELECT AUTHORITY/SCOPE
   ↓
CREATE OR RESUME A SUB-AGENT
   ↓
REASON
   ↓
LOAD RELEVANT SKILLS
   ↓
ACCESS PERMITTED MEMORY
   ↓
USE LOCAL AND MCP TOOLS
   ↓
INTERACT WITH SOFTWARE/HARDWARE
   ↓
VERIFY RESULTS
   ↓
RESPOND
   ↓
ESCALATE TO OWNER WHEN NECESSARY

The defining abstraction is:

The Android phone is an autonomous multi-principal agent platform.

Its operating environment—communications, applications, hardware, radios, sensors, local data, skills, and external MCP services—is exposed as tools and events.

Each person interacting with the phone receives a scoped agent appropriate to their role.

The owner receives the broad agent.

Known people receive constrained role-specific agents.

Unknown people receive minimal agents.

Scoped agents can ask the owner questions and asynchronously resume their original interaction after receiving an answer.

The system therefore combines:

ANDROID
+
AUTONOMOUS AGENT
+
EVENT BUS
+
SCOPED SUB-AGENTS
+
MCP
+
SKILLS
+
UPDATABLE SKILLS
+
MEMORY
+
HARDWARE ACCESS
+
OWNER DELEGATION

into a single extensible Android agent harness.
