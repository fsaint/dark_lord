package com.fsaint.androidagent.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fsaint.androidagent.DarkLordApplication
import com.fsaint.androidagent.runtime.Chat
import com.fsaint.androidagent.runtime.ChatMessage
import com.fsaint.androidagent.ui.DarkLordTheme
import com.fsaint.androidagent.voice.VoiceTurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(app: DarkLordApplication, initialChatId: String? = null, openOutside: Boolean = false, onSettings: () -> Unit) {
    var ownerId by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(initialChatId) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(app) {
        try {
            val outside = app.outsideChat()
            ownerId = outside.ownerId
            if (openOutside && selected == null) selected = outside.id
        } catch (_: Exception) { failure = "Set up your owner in Settings to start chatting." }
    }
    DarkLordTheme {
        val owner = ownerId
        if (owner == null) {
            Scaffold(topBar = { TopAppBar(title = { Text("Chats") }, actions = { TextButton(onClick = onSettings) { Text("Settings") } }) }) { padding ->
                Column(Modifier.padding(padding).padding(24.dp)) {
                    Text(failure ?: "Opening your chats…")
                    if (failure != null) Button(onClick = onSettings) { Text("Set up owner") }
                }
            }
        } else {
            ChatHome(app, owner, selected, { selected = it }, onSettings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHome(app: DarkLordApplication, ownerId: String, selected: String?, select: (String?) -> Unit, onSettings: () -> Unit) {
    val chats by remember(ownerId) { app.chats.observe(ownerId) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val chat = chats.firstOrNull { it.id == selected }
    val currentSelection by rememberUpdatedState(selected)
    val detailState = rememberSaveableStateHolder()
    BackHandler(selected != null) { select(null); naming = false }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(
            title = { Text(chat?.title ?: "Chats", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (selected != null) TextButton(onClick = { select(null); naming = false }) { Text("Back") } },
            actions = {
                if (chat != null) TextButton(onClick = { naming = !naming; name = chat.title }) { Text("Rename") }
                TextButton(onClick = onSettings) { Text("Settings") }
            },
        ) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                if (naming) {
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Chat name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = name.isNotBlank(), onClick = { scope.launch {
                            try {
                                if (chat == null) select(app.chats.create(ownerId, name).id) else app.chats.rename(ownerId, chat.id, name)
                                naming = false
                            } catch (_: Exception) { error = "Could not save the chat name." }
                        } }) { Text("Save") }
                        TextButton(onClick = { naming = false }) { Text("Cancel") }
                    }
                }
                if (selected == null) {
                    Button(onClick = { naming = true; name = "" }) { Text("New chat") }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chats, key = { it.id }) { item ->
                            OutlinedCard(onClick = { select(item.id); naming = false }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                                    Text(when { item.outside -> "Pinned · Side-button photos and voice"; item.archived -> "Archived · Read only"; else -> "Private conversation" }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else if (chat != null) {
                    detailState.SaveableStateProvider(chat.id) {
                    ChatDetail(app, ownerId, chat, onNewOutside = { scope.launch {
                        try {
                            val fresh = app.newOutsideChat()
                            // A database completion must not undo a Back/navigation action.
                            if (currentSelection == chat.id) select(fresh.id)
                        }
                        catch (_: Exception) { error = "Stop the current interaction before starting a new Outside chat." }
                    } })
                    }
                } else Text("Loading chat…")
            }
        }
    }
}

@Composable
private fun ColumnScope.ChatDetail(app: DarkLordApplication, ownerId: String, chat: Chat, onNewOutside: () -> Unit) {
    val messages by remember(chat.id) { app.chats.observeMessages(ownerId, chat.id) }.collectAsState(emptyList())
    val requests by remember(chat.id) { app.chats.observeRequests(ownerId, chat.id) }.collectAsState(emptyList())
    val textStates by app.textChats.states.collectAsState()
    val voice by app.voiceTurn.state.collectAsState()
    val photo by app.photoConversations.state.collectAsState()
    val speaking by app.interactions.speaking.collectAsState()
    var draft by rememberSaveable(chat.id) { mutableStateOf("") }
    var selectedPhoto by rememberSaveable(chat.id) { mutableStateOf<String?>(null) }
    val photoBusy = photo?.let { it.chatId == chat.id && !it.terminal } == true
    val voiceBusy = chat.outside && voice != VoiceTurnState.Idle && voice !is VoiceTurnState.Error
    val busy = requests.any { it.state == "RUNNING" } || textStates[chat.id]?.running == true || photoBusy || voiceBusy || (chat.outside && speaking)
    val scroll = rememberLazyListState()
    LaunchedEffect(chat.id, messages.size) { if (messages.isNotEmpty()) scroll.animateScrollToItem(messages.lastIndex) }
    if (chat.outside) {
        Text("Double press adds a photo. Long press talks about it.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !busy, onClick = onNewOutside) { Text("New Outside chat") }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (messages.isEmpty()) item { Text("Start a conversation. Messages and photos stay in this chat.") }
        items(messages, key = { it.id }) { message ->
            if (message.role != "tool") ChatMessageCard(app, chat.id, message, selectedPhoto, { selectedPhoto = it })
        }
    }
    if (busy) {
        Text(when {
            chat.outside && speaking -> "Speaking…"
            voiceBusy -> when (voice) { VoiceTurnState.Listening -> "Listening…"; VoiceTurnState.Recovering -> "Reconnecting speech…"; VoiceTurnState.Finalizing -> "Finishing listening…"; is VoiceTurnState.Responding -> "Speaking…"; else -> "Thinking…" }
            photoBusy -> photo!!.text
            else -> "Thinking…"
        }, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            if (chat.outside) app.beginLocalInteraction()
            app.textChats.stop(chat.id)
        }) { Text("Stop") }
    }
    textStates[chat.id]?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!busy && requests.any { it.id == messages.lastOrNull()?.requestId && it.state == "INTERRUPTED" }) Text("Interrupted. You can ask another question about the saved photo.")
    if (selectedPhoto != null) TextButton(onClick = { selectedPhoto = null }) { Text("Earlier photo selected · Use latest instead") }
    if (chat.archived) Text("Archived conversation", modifier = Modifier.padding(bottom = 16.dp))
    else {
        OutlinedTextField(draft, { draft = it.take(16_384) }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
        Button(enabled = draft.isNotBlank() && !busy, onClick = {
            if (app.textChats.send(chat.id, draft, selectedPhoto)) draft = ""
        }, modifier = Modifier.align(Alignment.End).padding(bottom = 8.dp)) { Text("Send") }
    }
}

@Composable
private fun ChatMessageCard(app: DarkLordApplication, chatId: String, message: ChatMessage, selectedPhoto: String?, selectPhoto: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (message.role == "user") "You" else if (message.role == "status") "Status" else "Dark Lord", style = MaterialTheme.typography.labelMedium)
            Text(message.text)
            message.artifactId?.let { id ->
                val bitmap by produceState<ImageBitmap?>(null, chatId, id) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { app.readChatPhoto(chatId, id)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } }.getOrNull()
                    }
                }
                if (bitmap != null) Image(bitmap!!, contentDescription = "Photo in this conversation", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
                else Text("Photo preview unavailable", style = MaterialTheme.typography.bodySmall)
                FilterChip(selected = selectedPhoto == id, onClick = { selectPhoto(id) }, label = { Text("Ask about this photo") })
            }
        }
    }
}
