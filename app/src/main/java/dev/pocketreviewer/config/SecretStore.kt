package dev.pocketreviewer.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pocketreviewer.api.dataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts credentials at rest. Values are encrypted with an AES-256-GCM key that
 * lives in the Android Keystore (hardware-backed where available, never exportable)
 * and stored in DataStore as `enc:v1:<base64(iv + ciphertext)>`.
 *
 * Reads are backwards compatible: a value without the prefix is treated as legacy
 * plaintext and returned as is; it gets encrypted the next time it is saved.
 *
 * If the Keystore is unavailable, [encrypt] falls back to plaintext (and logs a
 * warning) rather than making the app unusable. If a stored value can no longer be
 * decrypted (key lost, e.g. after a device restore), [decrypt] returns null and the
 * user simply has to enter the credential again.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val KEY_ALIAS = "pocket_reviewer_secrets_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    const val PREFIX = "enc:v1:"

    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore unavailable, storing value unencrypted: ${e.javaClass.simpleName}")
            plain
        }
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        if (!isEncrypted(stored)) return stored   // legacy plaintext
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt a stored credential: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Decrypted value of [name] in an already-loaded [prefs] snapshot. */
    fun read(prefs: Preferences, name: String): String? =
        decrypt(prefs[stringPreferencesKey(name)])?.takeIf { it.isNotBlank() }

    suspend fun get(context: Context, name: String): String? =
        read(context.dataStore.data.first(), name)

    /** Stores [value] encrypted; a blank value removes the entry. */
    suspend fun put(context: Context, name: String, value: String?) {
        context.dataStore.edit { p ->
            val k = stringPreferencesKey(name)
            if (value.isNullOrBlank()) p.remove(k) else p[k] = encrypt(value.trim())
        }
    }
}
