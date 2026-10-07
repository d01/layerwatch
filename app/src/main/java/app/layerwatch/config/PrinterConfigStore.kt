package app.layerwatch.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Connection details for one printer. */
data class PrinterConfig(
    val host: String,
    val serial: String,
    val accessCode: String,
)

private val Context.dataStore by preferencesDataStore(name = "printer")

/**
 * Persists [PrinterConfig]. The access code is AES-GCM encrypted with a non-exportable
 * Android Keystore key; host and serial aren't secret and are stored as-is.
 */
class PrinterConfigStore(private val context: Context) {

    val config: Flow<PrinterConfig?> = context.dataStore.data.map { prefs ->
        val host = prefs[HOST] ?: return@map null
        val serial = prefs[SERIAL] ?: return@map null
        val sealed = prefs[ACCESS_CODE] ?: return@map null
        // A key wiped by the OS (e.g. after a backup restore) means re-running setup.
        val code = runCatching { decrypt(sealed) }.getOrNull() ?: return@map null
        PrinterConfig(host, serial, code)
    }

    suspend fun save(config: PrinterConfig) {
        val sealed = encrypt(config.accessCode)
        context.dataStore.edit {
            it[HOST] = config.host
            it[SERIAL] = config.serial
            it[ACCESS_CODE] = sealed
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "layerwatch.printer-config"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12

        val HOST = stringPreferencesKey("host")
        val SERIAL = stringPreferencesKey("serial")
        val ACCESS_CODE = stringPreferencesKey("access_code")
    }
}
