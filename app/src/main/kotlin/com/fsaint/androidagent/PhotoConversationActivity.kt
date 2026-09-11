package com.fsaint.androidagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fsaint.androidagent.ui.DarkLordTheme

/** Foreground entry point for the Samsung Side-button photo-conversation shortcut. */
class PhotoConversationActivity : ComponentActivity() {
    private var captureRequested = false
    private var generation = 0L
    private val store get() = (application as DarkLordApplication).photoConversations
    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (!(application as DarkLordApplication).interactions.isCurrent(generation)) return@registerForActivityResult
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPhotoService()
        } else {
            store.publish(PhotoConversationState(PhotoPhase.ERROR, "Camera permission is required before I can take a picture."))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureRequested = savedInstanceState?.getBoolean("captureRequested") ?: false
        generation = savedInstanceState?.getLong("generation") ?: 0L
        setContent {
            val state by store.state.collectAsState()
            if (state?.chatId != null) {
                key(state!!.chatId) {
                    com.fsaint.androidagent.chat.ChatsScreen(application as DarkLordApplication, initialChatId = state!!.chatId) {
                        startActivity(Intent(this, MainActivity::class.java).putExtra("settings", true))
                    }
                }
            } else {
            PhotoConversationSurface(
                result = state?.text ?: "No photo conversation yet.",
                terminal = state?.terminal ?: true,
                onRetry = ::requestCapture,
                onOpenApp = {
                    startActivity(Intent(this@PhotoConversationActivity, MainActivity::class.java)
                        .putExtra("chat_id", state?.chatId))
                    finish()
                },
            )
            }
        }
        // Start before Samsung can stop the activity. The service, not this screen, owns the turn.
        if (!captureRequested && !intent.getBooleanExtra(EXTRA_VIEW_RESULT, false)) requestCapture()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("captureRequested", captureRequested)
        outState.putLong("generation", generation)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra(EXTRA_VIEW_RESULT, false)) requestCapture()
    }

    private fun requestCapture() {
        generation = (application as DarkLordApplication).beginLocalInteraction()
        captureRequested = true
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startPhotoService() else requestCamera.launch(missing.toTypedArray())
    }

    private fun startPhotoService() {
        if (!(application as DarkLordApplication).interactions.isCurrent(generation)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, PhotoConversationService::class.java).putExtra("generation", generation))
        } catch (_: RuntimeException) {
            store.publish(PhotoConversationState(PhotoPhase.ERROR, "Android blocked the photo conversation. Open and unlock the phone, then try again."))
        }
    }

    companion object {
        const val EXTRA_VIEW_RESULT = "view_photo_result"
    }
}

@androidx.compose.runtime.Composable
private fun PhotoConversationSurface(
    result: String,
    terminal: Boolean,
    onRetry: () -> Unit,
    onOpenApp: () -> Unit,
) {
    DarkLordTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Dark Lord", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = result,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (terminal) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Try another picture")
                }
                Button(onClick = onOpenApp, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Open Dark Lord")
                }
            }
        }
    }
}
