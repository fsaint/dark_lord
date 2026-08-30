package com.fsaint.androidagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class DialerActivity : ComponentActivity() {
    private var pendingNumber: String? = null
    private val requestCallPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingNumber?.takeIf { granted }?.let(::placeCall)
        pendingNumber = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialNumber = intent.takeIf { it.action == Intent.ACTION_DIAL }?.data?.schemeSpecificPart.orEmpty()
        setContent { DialerScreen(initialNumber, ::requestOrPlaceCall) }
    }

    private fun requestOrPlaceCall(number: String) {
        if (number.isBlank()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            placeCall(number)
        } else {
            pendingNumber = number
            requestCallPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    @Suppress("MissingPermission") // Permission is checked before this call and again after the result callback.
    private fun placeCall(number: String) {
        getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
    }
}

@Composable
private fun DialerScreen(initialNumber: String, onDial: (String) -> Unit) {
    var number by remember { mutableStateOf(initialNumber) }
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Dial", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(number, { number = it }, label = { Text("Phone number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { onDial(number) }) { Text("Call") }
        }
    }
}
