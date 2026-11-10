package io.github.karthiaiengineer.rflow.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the user's Gemini key lives. Tests use a fake; the app uses [AndroidKeyVault]. */
interface KeyVault {
    fun read(): String?
    fun write(key: String)
    fun clear()
}

/**
 * The Gemini key, encrypted with a key kept in the Android Keystore (the phone's DPAPI): the stored text is useless
 * off this phone. The preferences file is left out of backups (see the app's backup rules), so the key never reaches
 * the cloud. It is never logged.
 */
class AndroidKeyVault(context: Context) : KeyVault {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    override fun read(): String? {
        val stored = prefs.getString(ENTRY, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()  // the Keystore key can be lost (a phone reset keeps apps' data only rarely): ask for the key again
    }

    override fun write(key: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val sealed = cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(ENTRY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(ENTRY).apply()
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "rflow-gemini-key"
        const val ENTRY = "gemini"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
