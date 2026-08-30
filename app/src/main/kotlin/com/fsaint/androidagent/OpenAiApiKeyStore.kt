package com.fsaint.androidagent

import android.content.Context
import android.util.Base64
import com.fsaint.androidagent.runtime.OpenAiSecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the OpenAI credential encrypted with an Android Keystore AES key. */
class AndroidOpenAiSecretStore(context: Context) : OpenAiSecretStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun read(): String? = preferences.getString(VALUE, null)?.let(::decrypt)

    override suspend fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString(VALUE, encoded).commit()) { "Unable to persist OpenAI credential" }
    }

    override suspend fun clear() { check(preferences.edit().remove(VALUE).commit()) { "Unable to clear OpenAI credential" } }

    private fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES))) }
        cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(256)
        }.generateKey()
    }

    companion object {
        private const val VALUE = "openai_api_key"
        private const val PREFERENCES = "dark_lord_secure_credentials"
        private const val KEY_ALIAS = "dark_lord_openai_api_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
