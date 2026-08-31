package com.fsaint.androidagent

import android.app.Application
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.fsaint.androidagent.capabilities.accessibility.AccessibilityCapability
import com.fsaint.androidagent.capabilities.accessibility.AndroidAccessibilityAdapter
import com.fsaint.androidagent.capabilities.apps.AppsCapability
import com.fsaint.androidagent.capabilities.apps.PackageManagerAppsAdapter
import com.fsaint.androidagent.capabilities.audio.AndroidAudioAdapter
import com.fsaint.androidagent.capabilities.audio.AndroidMicrophoneAdapter
import com.fsaint.androidagent.capabilities.audio.AudioCapability
import com.fsaint.androidagent.capabilities.audio.MicrophoneCapability
import com.fsaint.androidagent.capabilities.camera.AndroidCameraAdapter
import com.fsaint.androidagent.capabilities.camera.CameraCapability
import com.fsaint.androidagent.capabilities.device.DeviceCapability
import com.fsaint.androidagent.capabilities.screen.AndroidScreenCaptureAdapter
import com.fsaint.androidagent.capabilities.screen.ScreenCapability
import com.fsaint.androidagent.capabilities.notifications.AgentNotificationListenerServiceDependencies
import com.fsaint.androidagent.capabilities.notifications.NotificationEventSink
import com.fsaint.androidagent.capabilities.radios.AndroidRadioAdapter
import com.fsaint.androidagent.capabilities.radios.RadioCapability
import com.fsaint.androidagent.capabilities.environment.AndroidEnvironmentAdapter
import com.fsaint.androidagent.capabilities.environment.EnvironmentCapability
import com.fsaint.androidagent.capabilities.sms.SmsCapability
import com.fsaint.androidagent.capabilities.sms.SmsBroadcastReceiverDependencies
import com.fsaint.androidagent.capabilities.sms.SmsEventSink
import com.fsaint.androidagent.capabilities.telephony.AgentInCallServiceDependencies
import com.fsaint.androidagent.capabilities.telephony.CallEventSink
import com.fsaint.androidagent.capabilities.telephony.CallUiLauncher
import com.fsaint.androidagent.communications.CommunicationsDispatcher
import com.fsaint.androidagent.communications.CommunicationsReplySender
import com.fsaint.androidagent.communications.AndroidPhoneNumberNormalizer
import com.fsaint.androidagent.communications.OwnerSmsCommandHandler
import com.fsaint.androidagent.communications.OwnerSmsCommandProcessor
import com.fsaint.androidagent.communications.OwnerProvisioningService
import com.fsaint.androidagent.diagnostics.DiagnosticsRepository
import com.fsaint.androidagent.diagnostics.DiagnosticCapability
import com.fsaint.androidagent.data.AuditRepository
import com.fsaint.androidagent.data.EncryptedAgentDatabaseFactory
import com.fsaint.androidagent.data.EscalationRepository
import com.fsaint.androidagent.data.EventRepository
import com.fsaint.androidagent.data.PrincipalRepository
import com.fsaint.androidagent.data.DurableStateRepository
import com.fsaint.androidagent.data.RoomConversationCheckpointStore
import com.fsaint.androidagent.data.McpConfigurationEntity
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.policy.ScopeRegistry
import com.fsaint.androidagent.policy.ScopedContextBuilder
import com.fsaint.androidagent.policy.ScopedToolRouter
import com.fsaint.androidagent.runtime.AgentRuntime
import com.fsaint.androidagent.runtime.Escalation
import com.fsaint.androidagent.runtime.EscalationService
import com.fsaint.androidagent.runtime.LegacyModelProvider
import com.fsaint.androidagent.runtime.PlannedAction
import com.fsaint.androidagent.runtime.VerificationEngine
import com.fsaint.androidagent.runtime.ConversationHarness
import com.fsaint.androidagent.runtime.OpenAiHttpClient
import com.fsaint.androidagent.runtime.OwnerOnlyOpenAiCredentialStore
import com.fsaint.androidagent.runtime.OwnerOnlyTelegramBotCredentialStore
import com.fsaint.androidagent.runtime.CredentialOutcome
import com.fsaint.androidagent.runtime.TelegramBotClient
import com.fsaint.androidagent.runtime.TelegramReplySender
import com.fsaint.androidagent.telegram.TelegramUpdateService
import com.fsaint.androidagent.telegram.SharedPreferencesTelegramUpdateCheckpointStore
import com.fsaint.androidagent.telegram.IdempotentTelegramInboundEventSink
import com.fsaint.androidagent.telegram.TelegramInboundEventSink
import java.util.concurrent.ConcurrentHashMap
import com.fsaint.androidagent.oem.samsungflip3.AgentSurfaceRegistry
import com.fsaint.androidagent.oem.samsungflip3.AndroidDisplayProvider
import com.fsaint.androidagent.oem.samsungflip3.DisplayBackedPostureProvider
import com.fsaint.androidagent.oem.samsungflip3.Flip3FormFactorCapability
import com.fsaint.androidagent.ui.CoverAssistantScreen
import com.fsaint.androidagent.ui.CallScreenActivity
import com.fsaint.androidagent.ui.CommunicationsAccessStatus
import com.fsaint.androidagent.ui.OpenAssistantScreen
import com.fsaint.androidagent.voice.SpeechTranscriptBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import android.speech.tts.TextToSpeech

/** Application lifecycle adapter that preserves Telegram's durable shutdown boundary. */
internal class TelegramUpdatesLifecycle(
    private val updates: TelegramUpdateService,
) {
    suspend fun stop() = updates.stop()
}

/** Coordinates Android application teardown with Telegram's joined polling shutdown. */
internal class ApplicationShutdownLifecycle(
    private val telegram: TelegramUpdatesLifecycle,
    private val supervisor: Job,
) {
    suspend fun shutdown() {
        telegram.stop()
        supervisor.cancelAndJoin()
    }
}

class DarkLordApplication : Application() {
    private val applicationSupervisor = SupervisorJob()
    private val applicationScope = CoroutineScope(applicationSupervisor + Dispatchers.IO)
    private val database by lazy { EncryptedAgentDatabaseFactory.open(this) }
    val principals: PrincipalDirectory by lazy { PrincipalRepository(database.durableStateDao()) }
    private val smsCapability by lazy { SmsCapability(this) }
    private val deviceCapability by lazy { DeviceCapability(this) }
    private val accessibilityCapability by lazy { AccessibilityCapability(AndroidAccessibilityAdapter(this)) }
    private val appsCapability by lazy { AppsCapability(PackageManagerAppsAdapter(this)) }
    private val cameraCapability by lazy { CameraCapability(AndroidCameraAdapter(this)) }
    private val microphoneCapability by lazy { MicrophoneCapability(AndroidMicrophoneAdapter(this)) }
    private val audioCapability by lazy { AudioCapability(AndroidAudioAdapter(this)) }
    private val radioCapability by lazy { RadioCapability(AndroidRadioAdapter(this)) }
    private val environmentCapability by lazy { EnvironmentCapability(AndroidEnvironmentAdapter(this)) }
    private val flip3Capability by lazy {
        val displays = AndroidDisplayProvider(this)
        Flip3FormFactorCapability(displays, DisplayBackedPostureProvider(displays))
    }
    val diagnostics: DiagnosticsRepository by lazy {
        DiagnosticsRepository(
            capabilities = listOf(
                DiagnosticCapability(deviceCapability.id, true),
                DiagnosticCapability(appsCapability.id, true),
                DiagnosticCapability(environmentCapability.id, environmentCapability.status().available),
                DiagnosticCapability("${flip3Capability.id}.posture", flip3Capability.refresh().available, flip3Capability.status().details),
            ),
            permissions = mapOf("notifications" to (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)),
        )
    }
    private val screenCaptureAdapter by lazy { AndroidScreenCaptureAdapter(this) }
    private val screenCapability by lazy { ScreenCapability(screenCaptureAdapter) }
    private val scopes = ScopeRegistry()
    private val mcpCatalog = ConcurrentHashMap.newKeySet<String>()
    private val skillCatalog = ConcurrentHashMap.newKeySet<String>()
    private val telegramOwnerChat by lazy { TelegramOwnerChatStore(this) }
    private val browserTools by lazy { BrowserTools() }
    private val durableState by lazy { DurableStateRepository(database.durableStateDao()) }
    private val agentTools by lazy {
        ScopedToolRouter(
            deviceCapability.toolHandlers() +
                smsCapability.toolHandlers() +
                appsCapability.toolHandlers() +
                accessibilityCapability.toolHandlers() +
                screenCapability.toolHandlers() +
                cameraCapability.toolHandlers() +
                microphoneCapability.toolHandlers() +
                audioCapability.toolHandlers() +
                radioCapability.toolHandlers() +
                environmentCapability.toolHandlers() +
                browserTools.handlers(),
            scopes,
        )
    }
    private val openAiCredentials by lazy { OwnerOnlyOpenAiCredentialStore(AndroidOpenAiSecretStore(this)) }
    val telegramBotCredentials by lazy { OwnerOnlyTelegramBotCredentialStore(AndroidTelegramBotSecretStore(this)) }
    private val telegramClient by lazy { TelegramBotClient(UrlConnectionTelegramTransport(), telegramBotCredentials) }
    private val telegramReplies by lazy { TelegramReplySender(telegramClient) }
    private val telegramUpdates by lazy {
        TelegramUpdateService(
            client = telegramClient,
            scope = applicationScope,
            eventSink = IdempotentTelegramInboundEventSink(
                eventState = eventStore::deliveryState,
                delegate = TelegramInboundEventSink { event, channel -> dispatcher.dispatch(event, channel) },
            ),
            checkpointStore = SharedPreferencesTelegramUpdateCheckpointStore(this),
        )
    }
    private val telegramUpdatesLifecycle by lazy { TelegramUpdatesLifecycle(telegramUpdates) }
    private val shutdownLifecycle by lazy { ApplicationShutdownLifecycle(telegramUpdatesLifecycle, applicationSupervisor) }
    private val conversationModel by lazy { OpenAiHttpClient(UrlConnectionOpenAiTransport(), openAiCredentials) }
    private val conversationHarness by lazy {
        ConversationHarness(
            conversationModel,
            agentTools,
            RoomConversationCheckpointStore(DurableStateRepository(database.durableStateDao())),
            toolEffects = eventStore,
        )
    }
    private val eventStore by lazy { EventRepository(database.eventDao()) }
    private val auditStore by lazy { AuditRepository(database.auditRecordDao()) }
    val ownerProvisioning by lazy { OwnerProvisioningService(principals, auditStore) }
    private val phoneNumbers by lazy { AndroidPhoneNumberNormalizer(this) }
    private val textToSpeech by lazy { TextToSpeech(this) { } }
    private val replies by lazy {
        object : com.fsaint.androidagent.runtime.ReplySender {
            override suspend fun send(channel: String, recipient: String, text: String) {
                when (channel) {
                    "VOICE" -> textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dark-lord-response")
                    "TELEGRAM" -> telegramReplies.send(channel, recipient, text)
                    else -> smsCapability.replySender.send(recipient, text)
                }
            }
        }
    }
    private val escalationService by lazy {
        EscalationService(EscalationRepository(database.durableStateDao()), replies) {
            principals.owner()?.e164
        }
    }
    private val runtime by lazy {
        AgentRuntime(
            events = eventStore,
            audit = auditStore,
            planner = EscalateUntilConfigured,
            contextBuilder = ScopedContextBuilder(
                scopes = scopes,
                memory = emptyMap(),
                availableTools = agentTools.availableToolIds,
                availableMcps = mcpCatalog,
                availableSkills = skillCatalog,
            ),
            tools = agentTools,
            verification = VerificationEngine(),
            replies = replies,
            escalations = escalationService,
            conversationHarness = conversationHarness,
        )
    }
    private val dispatcher by lazy {
        CommunicationsDispatcher(principals, scopes, runtime, telegramOwnerChatId = { telegramOwnerChat.read() }, phoneNumbers = phoneNumbers)
    }
    private val ownerCommands by lazy { OwnerSmsCommandHandler(principals, ::ownerStatus, escalationService::resolve) }
    private val ownerCommandProcessor by lazy { OwnerSmsCommandProcessor(ownerCommands, eventStore, auditStore, replies) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            mcpCatalog += durableState.mcpConfigurations().map { it.id }
            skillCatalog += durableState.enabledSkillIds()
        }
        telegramUpdates.start()
        SmsBroadcastReceiverDependencies.configure(object : SmsEventSink {
            override fun publish(event: AgentEvent) = smsCapability.publish(event)
        })
        applicationScope.launch {
            smsCapability.events().collect { event -> dispatch(event, "SMS") }
        }
        applicationScope.launch {
            SpeechTranscriptBus.transcripts.collect { transcript ->
                val owner = principals.owner() ?: return@collect
                dispatch(AgentEvent("voice:${System.currentTimeMillis()}", "voice.transcript", "voice", System.currentTimeMillis(), mapOf("body" to transcript)), "VOICE")
            }
        }
        AgentNotificationListenerServiceDependencies.configure(NotificationEventSink { event -> dispatch(event, "NOTIFICATION") })
        AgentInCallServiceDependencies.configure(
            eventSink = CallEventSink { event -> dispatch(event, "CALL") },
            uiLauncher = CallUiLauncher { call ->
                startActivity(
                    Intent(this, CallScreenActivity::class.java)
                        .putExtra(CallScreenActivity.EXTRA_CALL_ID, call.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
        AgentSurfaceRegistry.openContent = {
            OpenAssistantScreen(
                onRequestAssistantRole = {},
                onRequestCapabilityPermissions = {},
            )
        }
        AgentSurfaceRegistry.coverContent = { CoverAssistantScreen() }
    }

    /** Explicitly stops Telegram polling and waits for its durable boundary to close. */
    suspend fun stopTelegramUpdates() = telegramUpdatesLifecycle.stop()

    /** Cancels and joins all application work after Telegram's polling boundary is closed. */
    suspend fun shutdown() = shutdownLifecycle.shutdown()

    override fun onTerminate() {
        runBlocking { shutdown() }
        super.onTerminate()
    }

    fun communicationsAccessStatus(): CommunicationsAccessStatus {
        val roleManager = getSystemService(RoleManager::class.java)
        return CommunicationsAccessStatus(
            smsRoleHeld = roleManager.isRoleHeld(RoleManager.ROLE_SMS),
            dialerRoleHeld = roleManager.isRoleHeld(RoleManager.ROLE_DIALER),
            notificationListenerEnabled = isNotificationListenerEnabled(
                packageName,
                Settings.Secure.getString(contentResolver, "enabled_notification_listeners"),
            ),
            postNotificationsPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            capabilityPermissionsGranted = COMMUNICATIONS_PERMISSIONS.all {
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    fun createScreenCaptureConsentIntent(): Intent = screenCaptureAdapter.createConsentIntent()

    suspend fun saveOpenAiApiKey(value: String): CredentialOutcome {
        val owner = principals.owner() ?: return CredentialOutcome.DENIED
        return openAiCredentials.set(owner, value)
    }

    suspend fun saveTelegramBotToken(value: String): CredentialOutcome {
        val owner = principals.owner() ?: return CredentialOutcome.DENIED
        return telegramBotCredentials.set(owner, value)
    }

    fun saveTelegramOwnerChatId(value: String): Boolean = telegramOwnerChat.write(value)

    suspend fun mcpConfigurations(): List<McpConfigurationEntity> = durableState.mcpConfigurations()
    suspend fun addMcpServer(draft: com.fsaint.androidagent.ui.McpServerDraft): Result<Unit> = runCatching {
        require(draft.name.isNotBlank()) { "Enter a display name." }
        require(draft.endpoint.startsWith("https://")) { "MCP endpoints must use HTTPS." }
        require(draft.endpoint.length <= 512) { "Endpoint is too long." }
        val id = java.util.UUID.randomUUID().toString()
        durableState.save(McpConfigurationEntity(id, draft.name.take(80), com.fsaint.androidagent.ui.encodeMcpDraft(draft)))
        mcpCatalog += id
    }
    suspend fun removeMcpServer(id: String) { durableState.deleteMcpConfiguration(id); mcpCatalog.remove(id) }

    fun acceptScreenCaptureGrant(resultCode: Int, data: Intent?) {
        screenCaptureAdapter.acceptGrant(resultCode, data)
    }

    private fun dispatch(event: AgentEvent, channel: String) {
        applicationScope.launch {
            if (channel == "SMS" && event.type == "sms.received" && event.payload["body"].isAdministrativeCommand()) {
                val normalizedSource = phoneNumbers.normalize(event.source)
                val sender = principals.lookup(normalizedSource)
                    ?: Principal("unknown:$normalizedSource", normalizedSource, PrincipalRole.UNKNOWN)
                ownerCommandProcessor.process(sender, event)
            } else if (channel == "SMS" && event.type in SMS_TRANSPORT_EVENTS) {
                recordSmsTransportEvidence(event)
            } else {
                dispatcher.dispatch(event, channel)
            }
        }
    }

    private fun ownerStatus(): String {
        val access = communicationsAccessStatus()
        return "SMS role: ${access.smsRoleHeld}; dialer role: ${access.dialerRoleHeld}; notification access: ${access.notificationListenerEnabled}; notification permission: ${access.postNotificationsPermissionGranted}."
    }

    private suspend fun recordSmsTransportEvidence(event: AgentEvent) {
        val normalizedSource = phoneNumbers.normalize(event.source)
        val principal = principals.lookup(normalizedSource)
            ?: Principal("unknown:$normalizedSource", normalizedSource, PrincipalRole.UNKNOWN)
        val verification = event.payload["verification"]?.let { name ->
            runCatching { VerificationState.valueOf(name) }.getOrDefault(VerificationState.UNVERIFIED)
        } ?: VerificationState.UNVERIFIED
        eventStore.enqueue(event)
        auditStore.append(
            AuditRecord(
                id = "${event.id}:transport",
                occurredAtEpochMs = event.occurredAtEpochMs,
                eventId = event.id,
                principalId = principal.id,
                scopeId = principal.role.name.lowercase(),
                sessionId = "${principal.id}:SMS",
                tool = event.type,
                authorization = AuthorizationDecision.ALLOW,
                verification = verification,
                result = "SMS transport ${event.payload["resultCode"].orEmpty()}",
            ),
        )
        eventStore.markCompleted(event.id)
    }

    private companion object {
        val COMMUNICATIONS_PERMISSIONS = listOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.CALL_PHONE,
        )
        val SMS_TRANSPORT_EVENTS = setOf("sms.sent", "sms.delivered")
    }
}

private fun String?.isAdministrativeCommand(): Boolean = this?.trim()?.let { command ->
    command.equals("STATUS", ignoreCase = true) ||
        command.startsWith("KNOWN ", ignoreCase = true) ||
        command.startsWith("APPROVE ", ignoreCase = true) ||
        command.startsWith("REJECT ", ignoreCase = true)
} == true

internal fun isNotificationListenerEnabled(packageName: String, enabledListeners: String?): Boolean =
    enabledListeners
        ?.split(':')
        ?.mapNotNull(ComponentName::unflattenFromString)
        ?.any { it.packageName == packageName }
        ?: false

private object EscalateUntilConfigured : LegacyModelProvider {
    override suspend fun plan(
        session: com.fsaint.androidagent.model.ScopedAgentSession,
        event: AgentEvent,
        context: com.fsaint.androidagent.policy.AgentContext,
    ): PlannedAction = PlannedAction.Escalate(
        Escalation(
            id = "escalation:${event.id}",
            sessionId = session.id,
            channel = session.channel,
            recipient = event.source,
            question = "A communications event needs owner review.",
            reason = "No model provider is configured.",
            proposedAction = "No action was taken.",
        ),
    )
}
