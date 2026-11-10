package ai.moduui.jari.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 개인정보 저장소 추상화 (앱: SecureStore, 테스트: InMemoryStore) */
interface KeyValueStore {
    fun put(field: String, plain: String?)
    fun get(field: String): String?
    fun clearAll()
}

/** 단위 테스트용 */
class InMemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun put(field: String, plain: String?) {
        if (plain == null) map.remove(field) else map[field] = plain
    }

    override fun get(field: String): String? = map[field]
    override fun clearAll() = map.clear()
}

/**
 * 아기 프로필·수유 기록은 개인정보라 암호화해 저장 (Pleos 앱 보안 가이드: 민감 데이터는 저장 위치와 무관하게 암호화,
 * 키는 Android Keystore 에, 하드코딩 금지). AES-256-GCM, 키는 Keystore 밖으로 나오지 않음.
 */
class SecureStore(context: Context, name: String = "moduui_secure") : KeyValueStore {

    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun put(field: String, plain: String?) {
        if (plain == null) {
            prefs.edit().remove(field).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        prefs.edit().putString(field, packed).apply()
    }

    override fun get(field: String): String? {
        val packed = prefs.getString(field, null) ?: return null
        return try {
            val (iv, data) = packed.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // 키가 바뀌었거나 손상: 개인정보이므로 복구 시도 없이 지움
            Log.w("SecureStore", "decrypt failed for $field, clearing")
            prefs.edit().remove(field).apply()
            null
        }
    }

    override fun clearAll() = prefs.edit().clear().apply()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "moduui_profile_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
