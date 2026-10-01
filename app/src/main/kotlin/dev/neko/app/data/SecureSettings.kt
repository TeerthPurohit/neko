package dev.neko.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("neko_private", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("neko.local.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("neko.local.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun encrypt(value: String, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(purpose.toByteArray()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    @Synchronized fun decrypt(value: String, purpose: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(purpose.toByteArray()); String(doFinal(bytes.copyOfRange(12, bytes.size))) }
    }
    fun get(name: String, default: String = ""): String = prefs.getString(name, null)?.let { decrypt(it, name) } ?: default
    fun put(name: String, value: String) { prefs.edit().putString(name, encrypt(value, name)).apply() }
    fun remove(name: String) { prefs.edit().remove(name).apply() }
    var aiEnabled: Boolean get() = get("ai_enabled", "false").toBoolean(); set(value) = put("ai_enabled", value.toString())
    var backendUrl: String get() = get("backend_url"); set(value) = put("backend_url", value.trimEnd('/'))
    var deviceToken: String get() = get("device_token"); set(value) = put("device_token", value)
    var splitwiseKey: String get() = get("splitwise_key"); set(value) = put("splitwise_key", value)
    var theme: String get() = get("theme", "system"); set(value) = put("theme", value)
    var reducedMotion: Boolean get() = get("reduced_motion", "false").toBoolean(); set(value) = put("reduced_motion", value.toString())
    var paused: Boolean get() = get("paused", "false").toBoolean(); set(value) = put("paused", value.toString())
}
