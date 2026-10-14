package com.millennium.app.features.steamguard.data

import android.content.Context
import android.annotation.SuppressLint
import android.content.SharedPreferences
import org.json.JSONObject
import java.lang.reflect.Method
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Steam 3.10.11 / Expo SecureStore. Keys are generated on the receiving device. */
internal object SteamGuardRestore {
    private const val GUARD_KEY = "SteamGuard_1"
    private data class Adapter(
        val encryptor: Any, val options: Any, val service: String,
        val alias: String, val legacyAlias: String, val initialize: Method, val encode: Method,
    )
    private var adapter: Adapter? = null

    fun configure(loader: ClassLoader) {
        val type = Class.forName("expo.modules.securestore.encryptors.AESEncryptor", false, loader)
        val optionsType = Class.forName("expo.modules.securestore.SecureStoreOptions", false, loader)
        val module = Class.forName("expo.modules.securestore.SecureStoreModule", false, loader)
        val prefsName = module.getDeclaredField("SHARED_PREFERENCES_NAME").apply { isAccessible = true }.get(null)
        check(prefsName == "SecureStore")
        val options = optionsType.getConstructor().newInstance()
        check(optionsType.getMethod("getRequireAuthentication").invoke(options) == false)
        val service = optionsType.getMethod("getKeychainService").invoke(options) as String
        val encryptor = type.getConstructor().newInstance()
        val alias = type.getMethod("getExtendedKeyStoreAlias", optionsType, Boolean::class.javaPrimitiveType)
            .invoke(encryptor, options, false) as String
        val legacyAlias = type.getMethod("getKeyStoreAlias", optionsType).invoke(encryptor, options) as String
        val initialize = type.methods.single {
            it.name == "initializeKeyStoreEntry" && it.returnType == KeyStore.SecretKeyEntry::class.java
        }
        val encode = type.methods.single { it.name.startsWith("createEncryptedItemWithCipher") }
        check(encode.parameterTypes.toList() == listOf(String::class.java, Cipher::class.java, GCMParameterSpec::class.java))
        adapter = Adapter(encryptor, options, service, alias, legacyAlias, initialize, encode)
    }

    fun snapshot(context: Context): JSONObject {
        val config = checkNotNull(adapter)
        val prefs = context.getSharedPreferences("SecureStore", Context.MODE_PRIVATE)
        return JSONObject().put("service", config.service)
            .put("current", prefs.getString("${config.service}-$GUARD_KEY", null) ?: JSONObject.NULL)
            .put("legacy", prefs.getString(GUARD_KEY, null) ?: JSONObject.NULL)
            .put("device", context.getSharedPreferences("steam.uuid", Context.MODE_PRIVATE)
                .getString("uuidKey", null) ?: JSONObject.NULL)
    }

    fun decode(snapshot: JSONObject): String? {
        val config = checkNotNull(adapter)
        check(snapshot.getString("service") == config.service)
        val encoded = snapshot.nullableString("current") ?: snapshot.nullableString("legacy") ?: return null
        if (encoded == "null") return null
        val item = JSONObject(encoded)
        // Never erase unrelated authenticators if an existing entry cannot be decoded.
        check(item.getString("scheme") == "aes" && !item.optBoolean("requireAuthentication"))
        val alias = if (item.optBoolean("usesKeystoreSuffix")) config.alias else config.legacyAlias
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = checkNotNull(store.getKey(alias, null))
        val tag = item.getInt("tlen")
        require(tag in 96..128 && tag % 8 == 0)
        val iv = Base64.getMimeDecoder().decode(item.getString("iv"))
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(tag, iv))
        return String(cipher.doFinal(Base64.getMimeDecoder().decode(item.getString("ct"))), Charsets.UTF_8)
    }

    fun encode(before: JSONObject, json: String, device: String?): JSONObject {
        val config = checkNotNull(adapter)
        check(before.getString("service") == config.service)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = if (store.containsAlias(config.alias)) store.getEntry(config.alias, null) as KeyStore.SecretKeyEntry
        else config.initialize.invoke(config.encryptor, store, config.options) as KeyStore.SecretKeyEntry
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, entry.secretKey)
        val spec = cipher.parameters.getParameterSpec(GCMParameterSpec::class.java)
        // Use the host's serializer, so ct/iv/tlen use exactly the native format.
        val item = config.encode.invoke(config.encryptor, json, cipher, spec) as JSONObject
        item.put("scheme", "aes").put("usesKeystoreSuffix", true)
            .put("keystoreAlias", config.service).put("requireAuthentication", false)
        return JSONObject(before.toString()).put("current", item.toString()).put("legacy", JSONObject.NULL)
            .apply { if (!device.isNullOrEmpty()) put("device", device) }
    }

    @SuppressLint("UseKtx") // The KTX edit extension discards commit() failure; rollback needs that result.
    fun write(context: Context, snapshot: JSONObject) {
        val config = checkNotNull(adapter)
        check(snapshot.getString("service") == config.service)
        fun SharedPreferences.Editor.restore(key: String, value: String?) {
            if (value == null) remove(key) else putString(key, value)
        }
        val prefs = context.getSharedPreferences("SecureStore", Context.MODE_PRIVATE).edit()
        prefs.restore("${config.service}-$GUARD_KEY", snapshot.nullableString("current"))
        prefs.restore(GUARD_KEY, snapshot.nullableString("legacy"))
        check(prefs.commit())
        val device = context.getSharedPreferences("steam.uuid", Context.MODE_PRIVATE).edit()
        device.restore("uuidKey", snapshot.nullableString("device"))
        check(device.commit())
    }

    /** Accept before/after for an interrupted cross-store transaction; reject later host changes. */
    fun canUndo(current: JSONObject, before: JSONObject, after: JSONObject): Boolean =
        listOf("service", "current", "legacy", "device").all { key ->
            current.opt(key) == before.opt(key) || current.opt(key) == after.opt(key)
        }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
}
