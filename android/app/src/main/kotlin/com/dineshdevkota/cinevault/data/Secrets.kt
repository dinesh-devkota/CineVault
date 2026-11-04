package com.dineshdevkota.cinevault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.dineshdevkota.cinevault.core.LibraryFilter
import com.dineshdevkota.cinevault.core.LibrarySort
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretReadException(cause: Exception) : IllegalStateException(
    "Saved credentials could not be read. Enter and save them again.", cause,
)

/** Encrypts small secrets (NAS passwords, TMDB key) with a non-exportable Android Keystore AES key. */
class SecretBox {
    private val lock = Any()

    private fun key(createIfMissing: Boolean = true): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return@synchronized existing
        check(createIfMissing) { "The saved credentials' encryption key is unavailable." }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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

    /** Empty is a valid empty password. Damaged or unreadable stored credentials are reported separately. */
    fun decrypt(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            require(bytes.size >= IV_BYTES + TAG_BITS / 8) { "Invalid encrypted credentials." }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(createIfMissing = false), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretReadException(e)
        }
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

    var librarySort: LibrarySort
        get() = enumValue(SORT_KEY, LibrarySort.TITLE)
        set(value) = prefs.edit { putString(SORT_KEY, value.name) }

    var libraryFilter: LibraryFilter
        get() = enumValue(FILTER_KEY, LibraryFilter.ALL)
        set(value) = prefs.edit { putString(FILTER_KEY, value.name) }

    var libraryGenre: String?
        get() = prefs.getString(GENRE_KEY, null)
        set(value) = prefs.edit { putString(GENRE_KEY, value) }

    var librarySourceId: String?
        get() = prefs.getString(SOURCE_KEY, null)
        set(value) = prefs.edit { putString(SOURCE_KEY, value) }

    var libraryDecade: Int?
        get() = prefs.getInt(DECADE_KEY, -1).takeIf { it > 0 && it % 10 == 0 }
        set(value) = prefs.edit {
            if (value == null) remove(DECADE_KEY) else putInt(DECADE_KEY, value)
        }

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val TMDB_KEY = "tmdb_api_key"
        const val SORT_KEY = "library_sort"
        const val FILTER_KEY = "library_filter"
        const val GENRE_KEY = "library_genre"
        const val SOURCE_KEY = "library_source"
        const val DECADE_KEY = "library_decade"
    }
}
