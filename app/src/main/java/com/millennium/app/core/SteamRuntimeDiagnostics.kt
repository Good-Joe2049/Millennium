package com.millennium.app.core

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** Read-only runtime diagnostics for Steam's React Native and Hermes startup path. */
internal class SteamRuntimeDiagnostics(
    private val emit: (priority: Int, tag: String, message: String) -> Unit,
) {
    private val hookedMethods = HashSet<String>()
    private val reportedRuntimeHosts = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        hookMethods(
            module = module,
            classLoader = classLoader,
            classNames = listOf(
                "com.facebook.react.runtime.ReactInstance",
                "com.facebook.react.ReactInstanceManager",
                "com.facebook.react.bridge.CatalystInstanceImpl",
                "com.facebook.react.runtime.ReactHostImpl",
                "com.facebook.react.bridge.JSBundleLoader",
                "com.valvesoftware.android.steam.community.MainApplication" +
                        '$' + "reactNativeHost" + '$' + "1",
            ),
            methodNames = setOf(
                "loadJSBundle",
                "loadJSBundleFromAssets",
                "loadJSBundleFromFile",
                "loadBundle",
                "loadScriptFromAssets",
                "loadScriptFromFile",
                "loadScript",
                "setupReactContext",
                "getOrCreateReactInstance",
                "getNativeModules",
                "getPackages",
                "getRuntimeExecutor",
            ),
        )
        emit(Log.INFO, BUNDLE_TAG, "runtime diagnostics installed hooks=${hookedMethods.size}")
    }

    private fun hookMethods(
        module: XposedModule,
        classLoader: ClassLoader,
        classNames: List<String>,
        methodNames: Set<String>,
    ) {
        classNames.forEach { className ->
            val type = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (type == null) {
                emit(Log.DEBUG, RUNTIME_TAG, "class unavailable class=$className")
                return@forEach
            }
            findMethods(type, methodNames).forEach { method ->
                installHook(module, method)
            }
        }
    }

    private fun installHook(module: XposedModule, method: Method) {
        if (Modifier.isAbstract(method.modifiers)) return
        val key = method.toGenericString()
        synchronized(hookedMethods) {
            if (!hookedMethods.add(key)) return
        }

        val methodId = "millennium.diagnostics.runtime.${method.declaringClass.name}.${method.name}"
        runCatching {
            module.hook(method)
                .setId(methodId)
                .intercept { chain ->
                    if (method.name == "loadJSBundle" || method.name == "loadBundle") {
                        chain.args.forEach { argument ->
                            hookConcreteBundleLoader(module, argument)
                        }
                    }
                    emit(
                        Log.DEBUG,
                        tagFor(method.name),
                        "before class=${method.declaringClass.name} method=${method.name} " +
                                "args=${describeArgs(chain.args)} this=${describeObject(chain.thisObject)}",
                    )
                    val result = chain.proceed()
                    if (
                        method.name == "getNativeModules" &&
                        method.declaringClass.name == REACT_HOST_IMPL &&
                        ((result as? Collection<*>)?.size ?: 0) > 10
                    ) {
                        reportRuntimeExecutorCandidate(chain.thisObject)
                    }
                    emit(
                        Log.DEBUG,
                        tagFor(method.name),
                        "after class=${method.declaringClass.name} method=${method.name} " +
                                "result=${describeResult(method.name, result)}",
                    )
                    result
                }
        }.onFailure {
            emit(
                Log.WARN,
                RUNTIME_TAG,
                "hook failed class=${method.declaringClass.name} method=${method.name} " +
                        "error=${it.javaClass.name}:${it.message}",
            )
        }
    }

    private fun hookConcreteBundleLoader(module: XposedModule, value: Any?) {
        if (value == null || !value.javaClass.name.contains("JSBundleLoader")) return
        findMethods(value.javaClass, setOf("loadScript")).forEach { loaderMethod ->
            installHook(module, loaderMethod)
        }
    }

    private fun findMethods(type: Class<*>, names: Set<String>): List<Method> {
        val methods = linkedMapOf<String, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods
                .filter { it.name in names }
                .forEach { methods.putIfAbsent(it.toGenericString(), it) }
            current = current.superclass
        }
        return methods.values.toList()
    }

    private fun tagFor(methodName: String): String = when (methodName) {
        "loadJSBundle", "loadJSBundleFromAssets", "loadJSBundleFromFile",
        "loadBundle", "loadScriptFromAssets", "loadScriptFromFile", "loadScript" -> BUNDLE_TAG
        "getNativeModules" -> REGISTRY_TAG
        else -> RUNTIME_TAG
    }

    private fun describeResult(methodName: String, value: Any?): String {
        return when (methodName) {
            "getNativeModules" -> describeNativeModules(value)
            "getPackages" -> describePackages(value)
            "getRuntimeExecutor" -> describeRuntimeExecutor(value)
            else -> describeObject(value)
        }
    }

    private fun reportRuntimeExecutorCandidate(host: Any?) {
        if (host == null) return
        synchronized(reportedRuntimeHosts) {
            if (!reportedRuntimeHosts.add(host)) return
        }
        emit(
            Log.INFO,
            RUNTIME_TAG,
            "runtime executor getter candidate class=${host.javaClass.name}; " +
                    "waiting for a natural call; no reflective invocation performed",
        )
    }

    private fun describeRuntimeExecutor(value: Any?): String {
        if (value == null) return "RuntimeExecutor(null)"
        val methods = value.javaClass.methods.asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { "${it.name}${it.parameterTypes.joinToString(",", "(", ")") { type -> type.simpleName }}" }
            .distinct()
            .sorted()
            .take(MAX_EXECUTOR_METHODS)
            .toList()
        return "RuntimeExecutor(class=${value.javaClass.name},methods=${methods.joinToString("|")})"
    }

    private fun describeArgs(args: List<*>): String = args.mapIndexed { index, value ->
        "#$index=${describeObject(value)}"
    }.joinToString(",")

    private fun describeObject(value: Any?): String = when (value) {
        null -> "null"
        is String -> "String(${shorten(value)})"
        is Number, is Boolean, is Char -> "${value.javaClass.simpleName}($value)"
        else -> if (value.javaClass.name.startsWith(JS_BUNDLE_LOADER_PREFIX)) {
            describeBundleLoader(value)
        } else {
            "${value.javaClass.name}@${Integer.toHexString(System.identityHashCode(value))}"
        }
    }

    private fun describeBundleLoader(value: Any): String {
        val fields = runCatching {
            var type: Class<*>? = value.javaClass
            val values = linkedMapOf<String, String>()
            while (type != null) {
                type.declaredFields
                    .filter { it.name.startsWith("val$") }
                    .forEach { field ->
                        runCatching {
                            field.isAccessible = true
                            val fieldValue = field.get(value)
                            values.putIfAbsent(field.name, describeLoaderField(fieldValue))
                        }
                    }
                type = type.superclass
            }
            values.entries.joinToString(",") { "${it.key}=${it.value}" }
        }.getOrDefault("")
        val suffix = if (fields.isEmpty()) "" else " $fields"
        return "BundleLoader(${value.javaClass.name}@${
            Integer.toHexString(System.identityHashCode(value))
        }$suffix)"
    }

    private fun describeLoaderField(value: Any?): String = when (value) {
        null -> "null"
        is String -> "String(${shorten(value)})"
        is Number, is Boolean, is Char -> value.toString()
        else -> "${value.javaClass.name}@${Integer.toHexString(System.identityHashCode(value))}"
    }

    private fun describeNativeModules(value: Any?): String {
        val modules = value as? Collection<*> ?: return describeObject(value)
        val names = modules.mapNotNull { module ->
            module ?: return@mapNotNull null
            val exportedName = runCatching {
                module.javaClass.getMethod("getName").invoke(module) as? String
            }.getOrNull()
            if (exportedName.isNullOrEmpty()) module.javaClass.name else "$exportedName<${module.javaClass.name}>"
        }.distinct().sorted()
        val joined = names.joinToString("|") { shorten(it) }
        return "NativeModules(count=${modules.size},names=$joined)"
    }

    private fun describePackages(value: Any?): String {
        val packages = value as? Collection<*> ?: return describeObject(value)
        val names = packages.mapNotNull { it?.javaClass?.name }.distinct().sorted()
        return "ReactPackages(count=${packages.size},classes=${names.joinToString("|") { shorten(it) }})"
    }

    private fun shorten(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(MAX_STRING_LENGTH)

    private companion object {
        const val BUNDLE_TAG = "MillenniumSteamBundle"
        const val REGISTRY_TAG = "MillenniumSteamRegistry"
        const val RUNTIME_TAG = "MillenniumSteamRuntime"
        const val JS_BUNDLE_LOADER_PREFIX = "com.facebook.react.bridge.JSBundleLoader"
        const val MAX_STRING_LENGTH = 240
        const val MAX_EXECUTOR_METHODS = 24
        const val REACT_HOST_IMPL = "com.facebook.react.runtime.ReactHostImpl"
    }
}
