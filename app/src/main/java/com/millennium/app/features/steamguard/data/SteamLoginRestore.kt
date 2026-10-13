package com.millennium.app.features.steamguard.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.millennium.app.features.steamguard.export.SteamMaFile
import com.millennium.app.features.steamguard.export.SteamMaFileCodec
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

/** Applies a user-confirmed restore once, before the host first reads its database in a new process. */
internal object SteamLoginRestore {
    @Volatile var available = false
        private set
    @Volatile private var firstOpen = true

    fun install(module: XposedModule, loader: ClassLoader) {
        val type = Class.forName("com.reactnativecommunity.asyncstorage.ReactDatabaseSupplier", false, loader)
        val contextField = type.getDeclaredField("mContext").apply { isAccessible = true }
        val get = type.getDeclaredMethod("get")
        check(get.returnType == SQLiteDatabase::class.java)
        module.hook(get).setId("millennium.mafile.restore.first-open")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                // Serialize the first call until restore completes, so a second reader cannot race it.
                synchronized(checkNotNull(chain.thisObject)) {
                    val result = chain.proceed()
                    if (firstOpen) {
                        firstOpen = false
                        val context = contextField.get(chain.thisObject) as Context
                        runCatching { applyPending(context, result as SQLiteDatabase) }.onFailure {
                            runCatching { SteamMaFileStore.update(context) { root -> root.put("restore_status", "failed") } }
                            module.log(Log.WARN, "MillenniumMaFile", "restore failed type=${it.javaClass.simpleName}")
                        }
                    }
                    result
                }
            }
        available = true
    }

    fun schedule(context: Context, account: SteamMaFile) {
        check(available && !firstOpen)
        require(SteamMaFileCodec.loginIssue(account) == null)
        SteamMaFileStore.update(context) {
            it.put("pending_login", JSONObject().put("kind", "login").put("account", JSONObject(account.json)))
            it.put("restore_status", "queued")
        }
    }

    fun undo(context: Context) {
        check(available && !firstOpen)
        SteamMaFileStore.update(context) {
            check(it.has("login_backup"))
            it.put("pending_login", JSONObject().put("kind", "undo"))
            it.put("restore_status", "queued")
        }
    }

    fun cancel(context: Context) = SteamMaFileStore.update(context) {
        it.remove("pending_login")
        it.put("restore_status", "")
    }

    internal fun applyPending(context: Context, db: SQLiteDatabase) {
        val root = SteamMaFileStore.read(context)
        val pending = root.optJSONObject("pending_login") ?: return
        // A crash across the file/SQLite boundary must never replay stale credentials automatically.
        if (root.optString("restore_status") != "queued") {
            SteamMaFileStore.update(context) { it.put("restore_status", "interrupted"); it.remove("pending_login") }
            return
        }
        check(java.io.File(db.path).name == "RKStorage" && db.version == 1)
        val columns = db.rawQuery("PRAGMA table_info(catalystLocalStorage)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        check(columns == setOf("key", "value"))
        db.transaction {
            val previous = db.query("catalystLocalStorage", arrayOf("value"), "key=?", arrayOf("AuthContext_1"), null, null, null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            val undo = pending.getString("kind") == "undo"
            val next = if (undo) {
                val backup = root.getJSONObject("login_backup")
                // Restore only if the previous result is unchanged; do not overwrite subsequent account activity.
                check(previous == backup.getString("after"))
                if (backup.isNull("before")) null else backup.getString("before")
            } else SteamMaFileCodec.mergeLogin(previous, SteamMaFileCodec.parse(pending.getJSONObject("account").toString()))
            SteamMaFileStore.update(context) {
                it.put("restore_status", "applying")
                if (!undo) it.put("login_backup", JSONObject().put("before", previous ?: JSONObject.NULL).put("after", next))
            }
            if (next == null) db.delete("catalystLocalStorage", "key=?", arrayOf("AuthContext_1"))
            else db.insertOrThrow("catalystLocalStorage", null, ContentValues().apply {
                // Delete/insert is contained in this transaction; other keys are never touched.
                db.delete("catalystLocalStorage", "key=?", arrayOf("AuthContext_1"))
                put("key", "AuthContext_1"); put("value", next)
            })
        }
        SteamMaFileStore.update(context) {
            it.remove("pending_login")
            it.put("restore_status", "applied")
            if (pending.optString("kind") == "undo") it.remove("login_backup")
        }
    }
}
