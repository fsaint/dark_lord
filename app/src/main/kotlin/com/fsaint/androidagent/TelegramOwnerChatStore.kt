package com.fsaint.androidagent

import android.content.Context

class TelegramOwnerChatStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("telegram_identity", Context.MODE_PRIVATE)
    fun read(): String? = preferences.getString(KEY, null)
    fun write(value: String): Boolean {
        val normalized = value.trim()
        if (!normalized.matches(Regex("[0-9]{1,32}"))) return false
        return preferences.edit().putString(KEY, normalized).commit()
    }
    private companion object { const val KEY = "owner_chat_id" }
}
