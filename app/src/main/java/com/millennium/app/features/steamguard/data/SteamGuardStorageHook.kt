package com.millennium.app.features.steamguard.data

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** Observes Steam's existing storage reads without exporting to the clipboard. */
internal class SteamGuardStorageHook(
    private val module: XposedModule,
    private val context: () -> Context?,
) {
    private val hookedPromiseClasses = hashSetOf<Class<*>>()
    private val targetPromises = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
    )

    fun install(classLoader: ClassLoader) {
        var decryptHookCount = 0
        var exportedHookCount = 0
        SECURE_STORE_ENCRYPTERS.forEach { className ->
            val encrypter = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                ?: return@forEach
            findMethodsNamed(encrypter, "decryptItem").forEachIndexed { index, method ->
                module.hook(method)
                    .setId("millennium.feature.steam-guard.decrypt.$decryptHookCount.$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (result is String && isSteamGuardRead()) capture(result, "decryptItem")
                        result
                    }
                decryptHookCount++
            }
        }

        val exportedModule = runCatching {
            Class.forName("expo.modules.core.ExportedModule", false, classLoader)
        }.getOrNull()
        if (exportedModule != null) {
            findMethodsNamed(exportedModule, "invokeExportedMethod").forEachIndexed { index, method ->
                module.hook(method)
                    .setId("millennium.feature.steam-guard.exported.$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        capturePromise(chain.args)
                        chain.proceed()
                    }
                exportedHookCount++
            }
        }
        module.log(Log.INFO, TAG, "storage hooks installed decrypt=$decryptHookCount exported=$exportedHookCount")
    }

    private fun isSteamGuardRead(): Boolean = Throwable().stackTrace.any {
        it.methodName == "readJSONEncodedItem" || it.methodName == "readLegacySDK20Item"
    }

    private fun capturePromise(args: List<*>) {
        if (args.firstOrNull() != "getValueWithKeyAsync") return
        val callArgs = args.getOrNull(1) as? Collection<*> ?: return
        val key = callArgs.firstOrNull() as? String ?: return
        if (!key.startsWith("SteamGuard")) return
        val promise = callArgs.elementAtOrNull(2) ?: return
        targetPromises.add(promise)
        val promiseClass = promise.javaClass
        synchronized(hookedPromiseClasses) {
            if (!hookedPromiseClasses.add(promiseClass)) return
            findMethodsNamed(promiseClass, "resolve")
                .filter { it.parameterTypes.size == 1 }
                .forEachIndexed { index, method ->
                    module.hook(method)
                        .setId("millennium.feature.steam-guard.promise.${promiseClass.name}.$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val value = chain.args.firstOrNull()
                            if (targetPromises.remove(chain.thisObject) && value is String) {
                                capture(value, "promise.resolve")
                            }
                            chain.proceed()
                        }
                }
            findMethodsNamed(promiseClass, "reject").forEachIndexed { index, method ->
                module.hook(method)
                    .setId("millennium.feature.steam-guard.promise-reject.${promiseClass.name}.$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        targetPromises.remove(chain.thisObject)
                        chain.proceed()
                    }
            }
        }
    }

    private fun capture(rawJson: String, source: String) {
        val enhanced = runCatching { SteamGuardData.enhanceSteamGuardJson(rawJson, context()) }
            .getOrElse {
                // Do not log JSON or exception messages that may contain account secrets.
                module.log(Log.WARN, TAG, "JSON enhancement failed; keeping original source=$source")
                rawJson
            }
        if (SteamGuardData.accept(enhanced)) {
            module.log(Log.DEBUG, TAG, "data cached in memory source=$source")
        }
    }

    private fun findMethodsNamed(type: Class<*>, name: String): List<Method> {
        val methods = linkedSetOf<Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods
                .filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
                .forEach { methods += it }
            current = current.superclass
        }
        return methods.toList()
    }

    private companion object {
        const val TAG = "MillenniumSteamGuard"
        val SECURE_STORE_ENCRYPTERS = listOf(
            "expo.modules.securestore.SecureStoreModule" + '$' + "HybridAESEncrypter",
            "expo.modules.securestore.SecureStoreModule" + '$' + "AESEncrypter",
            "expo.modules.securestore.SecureStoreModule" + '$' + "LegacySDK20Encrypter",
            "expo.modules.securestore.encryptors.AESEncryptor",
            "expo.modules.securestore.encryptors.HybridAESEncryptor",
        )
    }
}
