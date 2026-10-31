package com.dineshdevkota.cinevault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (NAS passwords, TMDB key) with a non-exportable Android Keystore AES key. */
class SecretBox {
    private val lock = Any()

    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Returns "" when the value is empty or can no longer be decrypted (e.g. key reset). */
    fun decrypt(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "cinevault-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Small app preferences; secrets inside are sealed with [SecretBox]. */
class Settings(context: Context, private val secrets: SecretBox) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tmdbApiKey: String
        get() = secrets.decrypt(prefs.getString(TMDB_KEY, "").orEmpty())
        set(value) = prefs.edit { putString(TMDB_KEY, secrets.encrypt(value.trim())) }

    val hasTmdbKey: Boolean get() = tmdbApiKey.isNotEmpty()

    private companion object {
        const val TMDB_KEY = "tmdb_api_key"
    }
}
