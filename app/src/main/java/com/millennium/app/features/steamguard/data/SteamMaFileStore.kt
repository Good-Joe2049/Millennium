package com.millennium.app.features.steamguard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.millennium.app.features.steamguard.export.SteamMaFile
import com.millennium.app.features.steamguard.export.SteamMaFileCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Module-owned data inside the host sandbox, excluded from Android backups. Call on IO. */
internal object SteamMaFileStore {
    data class State(
        val accounts: List<SteamMaFile> = emptyList(), val restore: String = "", val canUndo: Boolean = false,
        val restored: Set<String> = emptySet(),
    )
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()
    private const val KEY = "millennium.mafile.v1"

    @Synchronized
    fun load(context: Context) { publish(read(context)) }

    @Synchronized
    fun update(context: Context, change: (JSONObject) -> Unit) {
        val root = read(context)
        change(root)
        write(context, root)
    }

    @Synchronized
    fun read(context: Context): JSONObject {
        val file = file(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject()
        val bytes = file.openRead().use { stream ->
            SteamMaFileCodec.readBytes(stream, 20 * SteamMaFileCodec.MAX_BYTES)
        }
        require(bytes.size > 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }

    private fun write(context: Context, root: JSONObject) {
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        require(plain.size < 20 * SteamMaFileCodec.MAX_BYTES - 64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val file = file(context)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        publish(root)
    }

    private fun publish(root: JSONObject) {
        val accounts = root.optJSONObject("accounts") ?: JSONObject()
        current.value = State(
            accounts.keys().asSequence().map { SteamMaFileCodec.parse(accounts.getJSONObject(it).toString()) }.toList(),
            root.optString("restore_status", ""),
            root.has("login_backup"),
            root.optJSONObject("restored_accounts")?.keys()?.asSequence()?.toSet().orEmpty(),
        )
    }

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "millennium-mafiles-v1.bin"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
