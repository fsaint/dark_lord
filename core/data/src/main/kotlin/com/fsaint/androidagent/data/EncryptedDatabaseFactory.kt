package com.fsaint.androidagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Opens the production database with a SQLCipher key envelope protected by Android Keystore. */
object EncryptedAgentDatabaseFactory {
    fun open(context: Context): AgentDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = KeystoreDatabasePassphraseProvider(context.applicationContext).loadOrCreate()
        return Room.databaseBuilder(context, AgentDatabase::class.java, "agent.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase, null, true))
            .addMigrations(AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3)
            .build()
    }
}

class KeystoreDatabasePassphraseProvider(private val context: Context) {
    private val preferences = context.getSharedPreferences("agent-database-key", Context.MODE_PRIVATE)

    fun loadOrCreate(): ByteArray {
        preferences.getString(ENVELOPE_KEY, null)?.let { return decrypt(android.util.Base64.decode(it, android.util.Base64.NO_WRAP)) }

        val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        preferences.edit().putString(ENVELOPE_KEY, android.util.Base64.encodeToString(encrypt(passphrase), android.util.Base64.NO_WRAP)).commit()
        return passphrase
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(cipher.iv.size.toByte()).put(cipher.iv).put(ciphertext).array()
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(envelope)
        val iv = ByteArray(buffer.get().toInt()) { buffer.get() }
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(ciphertext)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "android-agent.database-envelope.v1"
        const val ENVELOPE_KEY = "encrypted-passphrase"
        const val PASSPHRASE_BYTES = 32
    }
}
