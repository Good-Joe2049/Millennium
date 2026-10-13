package com.millennium.app.features.steamguard.data

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Passive diagnostic: observes Steam storage; plaintext output is explicitly enabled below. */
internal class SteamSessionDiagnostics(private val module: XposedModule) {
    private val pending = WeakHashMap<Any, Request>()
    private val callbackMethods = hashSetOf<Method>()
    private val sessionLog = SteamSessionLog(PRINT_SESSION_PLAINTEXT, ::emit)

    fun install(classLoader: ClassLoader) {
        val type = Class.forName("com.reactnativecommunity.asyncstorage.AsyncStorageModule", false, classLoader)
        val methods = type.declaredMethods.filter {
            it.name in setOf("multiGet", "multiSet", "multiRemove", "clear") &&
                    it.parameterTypes.size == (if (it.name == "clear") 1 else 2) &&
                    it.parameterTypes.last().name == "com.facebook.react.bridge.Callback"
        }
        methods.forEachIndexed { index, method ->
            module.hook(method)
                .setId("millennium.diagnostics.session.storage.$index")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    runCatching { observeRequest(method.name, chain.args) }
                        .onFailure { reportFailure("request", it) }
                    chain.proceed()
                }
        }
        emit("installed hooks=${methods.size} expected=4 readOnly=true " +
                "jwtClaims=unverified plaintext=$PRINT_SESSION_PLAINTEXT deduplicate=true")
    }

    private fun observeRequest(source: String, args: List<Any?>) {
        val values = if (source == "clear") listOf("AuthContext_1")
        else arrayValues(args.firstOrNull()) ?: return
        val writes = when (source) {
            "multiSet" -> values.mapNotNull { value ->
                val row = value as? List<*> ?: return@mapNotNull null
                val key = row.firstOrNull() as? String ?: return@mapNotNull null
                if (!AUTH_KEY.matches(key)) return@mapNotNull null
                key to (row.getOrNull(1) as? String)
            }.toMap()
            "multiRemove", "clear" ->
                values.filterIsInstance<String>().filter(AUTH_KEY::matches).associateWith { null }
            else -> null
        }
        val keys = writes?.keys ?: values.filterIsInstance<String>().filter(AUTH_KEY::matches).toSet()
        if (keys.isEmpty()) return
        val callback = args.lastOrNull() ?: return
        hookCallback(callback)
        synchronized(pending) { pending[callback] = Request(source, keys, writes) }
    }

    private fun hookCallback(callback: Any) {
        val method = callback.javaClass.getMethod("invoke", Array<Any>::class.java)
        synchronized(callbackMethods) {
            if (method in callbackMethods) return
            module.hook(method)
                .setId("millennium.diagnostics.session.callback.${method.declaringClass.name}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val request = synchronized(pending) { pending.remove(chain.thisObject) }
                    if (request != null) {
                        runCatching {
                            val result = chain.args.firstOrNull() as? Array<*>
                            if (result == null) emit("source=${request.source} callback=unsupported")
                            else observeResult(request, result)
                        }.onFailure { reportFailure("callback", it) }
                    }
                    chain.proceed()
                }
            callbackMethods += method
        }
    }

    private fun observeResult(request: Request, result: Array<*>) {
        if (result.firstOrNull() != null) {
            emit("source=${request.source} operation=failed keys=${request.keys.joinToString(",")}")
            return
        }
        if (request.writes != null) {
            request.writes.forEach { (key, rawJson) -> record(request.source, key, rawJson) }
            return
        }
        val rows = arrayValues(result.getOrNull(1))
        if (rows == null) {
            emit("source=${request.source} result=unsupported")
            return
        }
        val found = hashSetOf<String>()
        rows.forEach { value ->
            val row = value as? List<*> ?: return@forEach
            val key = row.firstOrNull() as? String ?: return@forEach
            if (key !in request.keys) return@forEach
            found += key
            record(request.source, key, row.getOrNull(1) as? String)
        }
        (request.keys - found).forEach {
            SteamSessionData.accept(it, null)
            emit("source=${request.source} key=$it operation=success entry=not-returned")
        }
    }

    private fun record(source: String, key: String, raw: String?) {
        SteamSessionData.accept(key, raw)
        sessionLog.report(source, key, raw)
    }

    private fun arrayValues(value: Any?): List<*>? = when (value) {
        null -> null
        is List<*> -> value
        else -> value.javaClass.getMethod("toArrayList").invoke(value) as? List<*>
    }

    private fun reportFailure(stage: String, error: Throwable) {
        // Exception messages and callback error objects may contain credentials.
        emit("stage=$stage diagnostic=failed type=${error.javaClass.simpleName}")
    }

    private fun emit(message: String) = module.log(Log.INFO, "MillenniumSteamSession", "[session] $message")

    private data class Request(
        val source: String,
        val keys: Set<String>,
        // Held only until the host's callback, then released; failed writes are never dumped.
        val writes: Map<String, String?>?,
    )

    private companion object {
        // User-requested diagnostic output. Set false to return to summary-only logging.
        const val PRINT_SESSION_PLAINTEXT = true
        val AUTH_KEY = Regex("AuthContext_[0-9]+")
    }
}
