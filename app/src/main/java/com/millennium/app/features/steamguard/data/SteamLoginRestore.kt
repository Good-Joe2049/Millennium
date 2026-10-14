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

/** Restores both stores before either React initializer reads its account data. */
internal object SteamLoginRestore {
    @Volatile var available = false
        private set
    @Volatile var guardAvailable = false
        private set
    @Volatile private var firstOpen = true

    fun install(module: XposedModule, loader: ClassLoader) {
        val type = Class.forName("com.reactnativecommunity.asyncstorage.ReactDatabaseSupplier", false, loader)
        val contextField = type.getDeclaredField("mContext").apply { isAccessible = true }
        val get = type.getDeclaredMethod("get")
        val instance = type.getDeclaredMethod("getInstance", Context::class.java).apply { isAccessible = true }
        check(get.returnType == SQLiteDatabase::class.java)
        fun restore(context: Context, supplier: Any, database: SQLiteDatabase? = null) {
            if (firstOpen) {
                firstOpen = false
                runCatching {
                    if (SteamMaFileStore.read(context).has("pending_login")) {
                        val db = database ?: get.invoke(supplier) as SQLiteDatabase
                        applyPending(context, db)
                    }
                }.onFailure {
                    runCatching { SteamMaFileStore.update(context) { root ->
                        if (root.optString("restore_status") != "interrupted") root.put("restore_status", "failed")
                        root.remove("pending_login")
                    } }
                    module.log(Log.WARN, "MillenniumMaFile", "restore failed type=" + it.javaClass.simpleName)
                }
            }
        }
        module.hook(get).setId("millennium.mafile.restore.first-open")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                // Use the supplier's own monitor: a second lock could deadlock its synchronized get().
                synchronized(checkNotNull(chain.thisObject)) {
                    val result = chain.proceed()
                    restore(contextField.get(chain.thisObject) as Context, checkNotNull(chain.thisObject), result as SQLiteDatabase)
                    result
                }
            }
        available = true
        runCatching {
            SteamGuardRestore.configure(loader)
            val secure = Class.forName("expo.modules.securestore.SecureStoreModule", false, loader)
            val read = secure.declaredMethods.single { it.name == "getItemImpl" && it.parameterTypes.size == 3 }
            val context = secure.getMethod("getReactContext")
            val helpers = Class.forName("com.valvesoftware.android.steam.community.ValveHelpersModule", false, loader)
            val device = helpers.declaredMethods.single { it.name == "GetUniqueDeviceIdentifier" && it.parameterTypes.size == 1 }
            val reactContext = generateSequence(helpers) { it.superclass }
                .firstNotNullOf { owner -> owner.declaredMethods.firstOrNull { it.name == "getReactApplicationContext" && it.parameterCount == 0 } }
                .apply { isAccessible = true }
            module.hook(device).setId("millennium.mafile.restore.before-device-id")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val host = reactContext.invoke(chain.thisObject) as Context
                    val supplier = checkNotNull(instance.invoke(null, host))
                    synchronized(supplier) {
                        restore(host, supplier)
                        chain.proceed()
                    }
                }
            module.hook(read).setId("millennium.mafile.restore.before-guard")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (chain.args.firstOrNull() != "SteamGuard_1") chain.proceed()
                    else {
                        val host = context.invoke(chain.thisObject) as Context
                        val supplier = checkNotNull(instance.invoke(null, host))
                        synchronized(supplier) {
                            restore(host, supplier)
                            chain.proceed()
                        }
                    }
                }
            guardAvailable = true
        }.onFailure {
            module.log(Log.WARN, "MillenniumMaFile", "guard restore unavailable type=" + it.javaClass.simpleName)
        }
    }

    fun canRestore(account: SteamMaFile): Boolean = available && if (SteamMaFileCodec.canRestoreGuard(account))
        guardAvailable else SteamMaFileCodec.loginIssue(account) == null

    /** Persist the import and its restoration request together, including same-ID replacements. */
    fun import(context: Context, account: SteamMaFile) = SteamMaFileStore.update(context) { root ->
        val accounts = root.optJSONObject("accounts") ?: JSONObject()
        accounts.put(account.key, JSONObject(account.json))
        root.put("accounts", accounts)
        root.optJSONObject("restored_accounts")?.remove(account.key)
        if (canRestore(account)) {
            check(!firstOpen)
            queue(root, account)
        } else {
            // A replacement must not leave older credentials queued for the same account.
            val pending = root.optJSONObject("pending_login")
            val queued = pending?.optJSONObject("accounts")
            queued?.remove(account.key)
            if (queued?.length() == 0 || pending?.optJSONObject("account")?.optString("steamid") == account.steamId) {
                root.remove("pending_login")
            }
            if (!root.has("pending_login")) root.put("restore_status", "saved")
        }
    }

    fun schedule(context: Context, account: SteamMaFile) {
        check(!firstOpen && canRestore(account))
        SteamMaFileStore.update(context) { queue(it, account) }
    }

    private fun queue(root: JSONObject, account: SteamMaFile) {
        root.optJSONObject("restored_accounts")?.remove(account.key)
        val previous = root.optJSONObject("pending_login")
        val accounts = if (root.optString("restore_status") == "queued" && previous?.optString("kind") == "accounts")
            previous.getJSONObject("accounts") else JSONObject()
        accounts.put(account.key, JSONObject(account.json))
        root.put("pending_login", JSONObject().put("kind", "accounts").put("accounts", accounts).put("active", account.key))
        root.put("restore_status", "queued")
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
        if (root.optString("restore_status") != "queued") {
            SteamMaFileStore.update(context) { it.put("restore_status", "interrupted"); it.remove("pending_login") }
            return
        }
        check(java.io.File(db.path).name == "RKStorage" && db.version == 1)
        val columns = db.rawQuery("PRAGMA table_info(catalystLocalStorage)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        check(columns == setOf("key", "value"))
        val undo = pending.getString("kind") == "undo"
        var guardBefore: JSONObject? = null
        var guardAfter: JSONObject? = null
        var guardTouched = false
        var authAfter: String? = null
        try {
            db.transaction {
                val previous = db.query("catalystLocalStorage", arrayOf("value"), "key=?", arrayOf("AuthContext_1"), null, null, null)
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                authAfter = previous
                if (undo) {
                    val backup = root.getJSONObject("login_backup")
                    val before = backup.nullableString("before")
                    val after = backup.nullableString("after")
                    check(previous == after || previous == before)
                    authAfter = before
                    if (backup.has("guard_before")) {
                        check(guardAvailable)
                        guardBefore = SteamGuardRestore.snapshot(context)
                        guardAfter = backup.getJSONObject("guard_before")
                        check(SteamGuardRestore.canUndo(guardBefore, checkNotNull(guardAfter), backup.getJSONObject("guard_after")))
                    }
                } else {
                    val files = if (pending.getString("kind") == "accounts") {
                        val accounts = pending.getJSONObject("accounts")
                        accounts.keys().asSequence().sorted().map { SteamMaFileCodec.parse(accounts.getJSONObject(it).toString()) }
                            .toList().sortedBy { it.key == pending.optString("active") }
                    } else listOf(SteamMaFileCodec.parse(pending.getJSONObject("account").toString())) // v1.1.3 queued login
                    var guardJson: String? = null
                    var device: String? = null
                    for (file in files) {
                        val guard = pending.getString("kind") == "accounts" && SteamMaFileCodec.canRestoreGuard(file)
                        val login = SteamMaFileCodec.loginIssue(file) == null
                        check(guard || login)
                        if (guard) {
                            check(guardAvailable)
                            if (guardBefore == null) {
                                guardBefore = SteamGuardRestore.snapshot(context)
                                guardJson = SteamGuardRestore.decode(guardBefore)
                            }
                            guardJson = SteamMaFileCodec.mergeGuard(guardJson, file)
                            JSONObject(file.json).optString("device_id").takeIf(String::isNotEmpty)?.let { device = it }
                        }
                        if (login) authAfter = SteamMaFileCodec.mergeLogin(authAfter, file)
                    }
                    if (guardBefore != null) guardAfter = SteamGuardRestore.encode(guardBefore, checkNotNull(guardJson), device)
                }
                SteamMaFileStore.update(context) {
                    it.put("restore_status", "applying")
                    if (!undo) {
                        val backup = JSONObject().put("before", previous ?: JSONObject.NULL).put("after", authAfter ?: JSONObject.NULL)
                        guardBefore?.let { before -> backup.put("guard_before", before).put("guard_after", guardAfter) }
                        it.put("login_backup", backup)
                    }
                }
                if (authAfter != previous) {
                    db.delete("catalystLocalStorage", "key=?", arrayOf("AuthContext_1"))
                    authAfter?.let { next -> db.insertOrThrow("catalystLocalStorage", null, ContentValues().apply {
                        put("key", "AuthContext_1"); put("value", next)
                    }) }
                }
                guardAfter?.let { next ->
                    guardTouched = true
                    SteamGuardRestore.write(context, next)
                }
            }
        } catch (failure: Exception) {
            // SQLite rolls back on failure; restore the original ciphertext and device ID too.
            val rolledBack = !guardTouched || runCatching { SteamGuardRestore.write(context, checkNotNull(guardBefore)) }.isSuccess
            SteamMaFileStore.update(context) {
                it.remove("pending_login")
                it.put("restore_status", if (rolledBack) "failed" else "interrupted")
                if (rolledBack && !undo) it.remove("login_backup")
            }
            throw failure
        }
        SteamMaFileStore.update(context) {
            it.remove("pending_login")
            it.put("restore_status", "applied")
            if (undo) it.remove("login_backup")
            // Applied accounts now use the host's current credentials on future exports.
            val applied = it.optJSONObject("restored_accounts") ?: JSONObject()
            pending.optJSONObject("accounts")?.keys()?.asSequence()?.forEach { id -> applied.put(id, true) }
            it.put("restored_accounts", applied)
        }
        SteamSessionData.accept("AuthContext_1", authAfter)
        guardAfter?.let { snapshot ->
            val json = SteamGuardRestore.decode(snapshot) ?: "{\"accounts\":{}}"
            SteamGuardData.accept(SteamGuardData.enhanceSteamGuardJson(json, context))
        }
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
}
