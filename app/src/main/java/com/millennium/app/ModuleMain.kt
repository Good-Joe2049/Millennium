package com.millennium.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.millennium.app.core.SteamRuntimeDiagnostics
import com.millennium.app.features.ui.SteamUiProbeFeature
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Built-in Steam Guard export feature matching SteamGuardDump's startup trigger. */
class ModuleMain : XposedModule() {
    private var loadedProcess = "unknown"
    private var moduleApkPath: String? = null

    companion object {
        private const val TAG = "MillenniumXposed"
        private const val GUARD_TAG = "MillenniumSteamGuard"
        private const val STEAM_PACKAGE = "com.valvesoftware.android.steam.community"
        private const val MAIN_APPLICATION = "com.valvesoftware.android.steam.community.MainApplication"
        private const val REACT_INSTANCE_MANAGER = "com.facebook.react.ReactInstanceManager"
        private const val REACT_APPLICATION_CONTEXT = "com.facebook.react.bridge.ReactApplicationContext"
        private const val REACT_INSTANCE = "com.facebook.react.runtime.ReactInstance"
        private const val REACT_EVENT = "com.facebook.react.uimanager.events.Event"
        private const val REACT_EVENT_DISPATCHER_IMPL =
            "com.facebook.react.uimanager.events.EventDispatcherImpl"
        private const val REACT_TOUCH_TARGET_HELPER =
            "com.facebook.react.uimanager.TouchTargetHelper"
        private const val JS_BUNDLE_LOADER = "com.facebook.react.bridge.JSBundleLoader"
        private const val EXPORTED_MODULE = "expo.modules.core.ExportedModule"
        private val SECURE_STORE_ENCRYPTERS = listOf(
            "expo.modules.securestore.SecureStoreModule" + '$' + "HybridAESEncrypter",
            "expo.modules.securestore.SecureStoreModule" + '$' + "AESEncrypter",
            "expo.modules.securestore.SecureStoreModule" + '$' + "LegacySDK20Encrypter",
            "expo.modules.securestore.encryptors.AESEncryptor",
            "expo.modules.securestore.encryptors.HybridAESEncryptor",
        )
        private const val SETUP_HOOK_ID = "millennium.probe.setup-react-context"
        private const val APPLICATION_HOOK_ID = "millennium.probe.application-on-create"
        private const val LOAD_BUNDLE_HOOK_ID = "millennium.probe.react-instance-load-bundle"
        private const val NATIVE_MODULES_HOOK_ID = "millennium.probe.react-instance-native-modules"
        private const val TOUCH_DISPATCH_HOOK_ID = "millennium.probe.activity-dispatch-touch"
        private const val REACT_EVENT_HOOK_ID = "millennium.probe.react-touch-events"
    }

    @Volatile
    private var steamContext: Context? = null

    private val steamUiProbeFeature = SteamUiProbeFeature { priority, message ->
        log(priority, "MillenniumSteamUI", message)
    }

    private val steamRuntimeDiagnostics = SteamRuntimeDiagnostics { priority, tag, message ->
        log(priority, tag, message)
    }

    private val hookedPromiseClasses = hashSetOf<Class<*>>()
    private var targetPromise: Any? = null
    private var lastTouchTargetLogKey: String? = null
    private var lastTouchTargetLogAt = 0L

    private fun guardLog(priority: Int, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            log(priority, GUARD_TAG, message)
        } else {
            log(priority, GUARD_TAG, message, throwable)
        }
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        loadedProcess = param.processName
        log(Log.INFO, TAG, "loaded process=$loadedProcess framework=$frameworkName/$frameworkVersion api=$apiVersion")
        guardLog(Log.INFO, "module loaded process=$loadedProcess")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != STEAM_PACKAGE || !param.isFirstPackage) return

        moduleApkPath = runCatching { getModuleApplicationInfo().sourceDir }
            .onFailure { log(Log.ERROR, TAG, "module application info unavailable", it) }
            .getOrNull()
        steamUiProbeFeature.setModuleApkPath(moduleApkPath)
        log(
            Log.INFO,
            TAG,
            "module APK path available=${!moduleApkPath.isNullOrBlank()} path=$moduleApkPath",
        )
        log(Log.INFO, TAG, "Steam package ready process=$loadedProcess loader=${param.classLoader}")
        guardLog(Log.INFO, "feature entry package=${param.packageName}")

        runCatching { steamRuntimeDiagnostics.install(this, param.classLoader) }
            .onFailure { log(Log.ERROR, "MillenniumSteamRuntime", "diagnostics install failed", it) }

        runCatching { hookMainApplication(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "MainApplication hook failed", it) }

        runCatching { hookReactContextSetup(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "ReactInstanceManager hook failed", it) }

        runCatching { hookBridgelessReactInstance(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Bridgeless ReactInstance hook failed", it) }

        runCatching { hookReactTouchEvents(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "React touch event hook failed", it) }

        runCatching { hookReactTouchTargetResolution(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "React touch target hook failed", it) }

        runCatching { hookActivityTouchDispatch() }
            .onFailure { log(Log.ERROR, TAG, "Activity touch probe hook failed", it) }

        runCatching { hookSteamGuardStorage(param.classLoader) }
            .onFailure { guardLog(Log.ERROR, "storage hook failed", it) }

    }

    private fun hookActivityTouchDispatch() {
        val dispatchTouchEvent = Activity::class.java.getDeclaredMethod(
            "dispatchTouchEvent",
            MotionEvent::class.java,
        )
        hook(dispatchTouchEvent)
            .setId(TOUCH_DISPATCH_HOOK_ID)
            .intercept { chain ->
                val activity = chain.thisObject as? Activity
                val event = chain.args.firstOrNull() as? MotionEvent
                if (activity?.packageName == STEAM_PACKAGE && event != null) {
                    if (steamUiProbeFeature.onActivityTouch(activity, event)) {
                        return@intercept true
                    }
                }
                chain.proceed()
            }
        log(Log.INFO, TAG, "Activity touch probe hook installed")
    }

    private fun hookReactTouchEvents(classLoader: ClassLoader) {
        val eventType = Class.forName(REACT_EVENT, false, classLoader)
        val dispatcher = Class.forName(REACT_EVENT_DISPATCHER_IMPL, false, classLoader)
        val methods = dispatcher.declaredMethods.filter { method ->
            method.name == "dispatchEvent" &&
                    !Modifier.isAbstract(method.modifiers) &&
                    method.parameterTypes.any { eventType.isAssignableFrom(it) }
        }
        if (methods.isEmpty()) {
            log(Log.WARN, TAG, "React touch event hook found no dispatchEvent methods")
            return
        }
        methods.forEachIndexed { index, method ->
            hook(method)
                .setId("$REACT_EVENT_HOOK_ID.$index")
                .intercept { chain ->
                    chain.args.firstOrNull { value -> value != null && eventType.isInstance(value) }
                        ?.let(::inspectReactTouchEvent)
                    chain.proceed()
                }
        }
        log(Log.INFO, TAG, "React touch event hook installed methods=${methods.size}")
    }

    private fun inspectReactTouchEvent(event: Any) {
        val eventName = invokeNoArg(event, "getEventName")?.toString().orEmpty()
        val className = event.javaClass.name
        if (!className.contains("Touch", ignoreCase = true) &&
            !eventName.contains("Touch", ignoreCase = true) &&
            !eventName.contains("Press", ignoreCase = true)
        ) return
        val viewTag = invokeNoArg(event, "getViewTag")?.toString() ?: "unknown"
        log(
            Log.INFO,
            "MillenniumSteamUI",
            "react touch event class=$className name=${eventName.ifEmpty { "unknown" }} " +
                    "viewTag=$viewTag event=$event",
        )
    }

    private fun hookReactTouchTargetResolution(classLoader: ClassLoader) {
        val helper = Class.forName(REACT_TOUCH_TARGET_HELPER, false, classLoader)
        val methods = findMethodsNamed(helper, "findTargetTagAndCoordinatesForTouch")
        if (methods.isEmpty()) {
            log(Log.WARN, TAG, "React touch target hook found no target resolver methods")
            return
        }
        methods.forEachIndexed { index, method ->
            hook(method)
                .setId("millennium.probe.react-touch-target.$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    val tag = result as? Int
                    if (tag != null) {
                        val targetView = findResolvedReactView(chain.args, tag)
                        val targetDetails = describeResolvedReactView(chain.args, tag)
                        runCatching {
                            steamUiProbeFeature.onReactTouchTarget(tag, targetView)
                        }.onFailure {
                            log(Log.ERROR, TAG, "React target callback failed", it)
                        }
                        val logKey = "$tag|$targetDetails|${describeHookArgs(chain.args)}"
                        val now = android.os.SystemClock.uptimeMillis()
                        if (logKey != lastTouchTargetLogKey || now - lastTouchTargetLogAt > 250L) {
                            lastTouchTargetLogKey = logKey
                            lastTouchTargetLogAt = now
                            log(
                                Log.INFO,
                                "MillenniumSteamUI",
                                "react touch target resolved viewTag=$tag " +
                                        "$targetDetails args=${describeHookArgs(chain.args)}",
                            )
                        }
                    }
                    result
                }
        }
        log(Log.INFO, TAG, "React touch target hook installed methods=${methods.size}")
    }

    private fun describeResolvedReactView(args: List<Any?>, tag: Int): String {
        val root = args.filterIsInstance<View>().firstOrNull() ?: return "view=none"
        val resolved = findResolvedReactView(args, tag)
            ?: return "view=none root=${root.javaClass.name}"
        val bounds = Rect()
        resolved.getGlobalVisibleRect(bounds)
        val text = (resolved as? android.widget.TextView)?.text?.toString().orEmpty()
        return "view=${resolved.javaClass.name} bounds=$bounds " +
                "id=${resolved.id} tag=${resolved.tag ?: "none"} " +
                "description=${resolved.contentDescription ?: "none"} text=${text.take(80)}"
    }

    private fun findResolvedReactView(args: List<Any?>, tag: Int): View? {
        val root = args.filterIsInstance<View>().firstOrNull() ?: return null
        return runCatching { root.findViewById<View>(tag) }.getOrNull()
    }

    private fun describeHookArgs(args: List<Any?>): String = args.joinToString(",") { value ->
        when (value) {
            null -> "null"
            is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
            is Number, is Boolean, is CharSequence -> value.toString()
            is View -> value.javaClass.name
            else -> value.javaClass.name
        }
    }

    private fun invokeNoArg(value: Any, name: String): Any? =
        runCatching { value.javaClass.getMethod(name).invoke(value) }.getOrNull()

    private fun hookMainApplication(classLoader: ClassLoader) {
        val applicationClass = Class.forName(MAIN_APPLICATION, false, classLoader)
        val onCreate = applicationClass.getDeclaredMethod("onCreate")

        hook(onCreate)
            .setId(APPLICATION_HOOK_ID)
            .intercept { chain ->
                steamContext = (chain.thisObject as? Context)?.applicationContext
                val result = chain.proceed()
                steamUiProbeFeature.install(steamContext ?: (chain.thisObject as? Context))
                log(Log.INFO, TAG, "Steam MainApplication.onCreate completed")
                result
            }
    }

    private fun hookReactContextSetup(classLoader: ClassLoader) {
        val managerClass = Class.forName(REACT_INSTANCE_MANAGER, false, classLoader)
        val contextClass = Class.forName(REACT_APPLICATION_CONTEXT, false, classLoader)
        val setup = managerClass.getDeclaredMethod("setupReactContext", contextClass)

        hook(setup)
            .setId(SETUP_HOOK_ID)
            .intercept { chain ->
                val result = chain.proceed()
                inspectReactContext(chain.args.firstOrNull())
                result
            }
    }

    private fun inspectReactContext(value: Any?) {
        if (value == null) {
            log(Log.WARN, TAG, "React context setup completed with null context")
            return
        }

        runCatching {
            val context = value as Context
            steamContext = context.applicationContext
            val catalyst = value.javaClass.getMethod("getCatalystInstance").invoke(value)
            val modules = catalyst.javaClass.getMethod("getNativeModules").invoke(catalyst) as? Collection<*>
            val names = modules.orEmpty().mapNotNull { it?.javaClass?.name }.sorted()
            log(Log.INFO, TAG, "React context ready package=${context.packageName} catalyst=${catalyst.javaClass.name} nativeModules=${names.size}")
            names.forEach { log(Log.DEBUG, TAG, "nativeModule=$it") }
        }.onFailure {
            log(Log.ERROR, TAG, "React context inspection failed", it)
        }
    }

    private fun hookBridgelessReactInstance(classLoader: ClassLoader) {
        val instanceClass = Class.forName(REACT_INSTANCE, false, classLoader)
        val loaderClass = Class.forName(JS_BUNDLE_LOADER, false, classLoader)
        val loadBundle = instanceClass.getDeclaredMethod("loadJSBundle", loaderClass)
        val getNativeModules = instanceClass.getDeclaredMethod("getNativeModules")

        hook(loadBundle)
            .setId(LOAD_BUNDLE_HOOK_ID)
            .intercept { chain ->
                val result = chain.proceed()
                inspectBridgelessInstance(chain.thisObject)
                result
            }

        hook(getNativeModules)
            .setId(NATIVE_MODULES_HOOK_ID)
            .intercept { chain ->
                val result = chain.proceed()
                inspectBridgelessModules(chain.thisObject, result)
                result
            }
    }

    private fun hookSteamGuardStorage(classLoader: ClassLoader) {
        var decryptHookCount = 0
        var exportedHookCount = 0
        SECURE_STORE_ENCRYPTERS.forEach { className ->
            val encrypter = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: return@forEach
            findMethodsNamed(encrypter, "decryptItem")
                .forEachIndexed { index, method ->
                    hook(method)
                        .setId("millennium.feature.steam-guard.decrypt.$decryptHookCount.$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (result is String && isSteamGuardRead()) captureSteamGuard(result, "decryptItem")
                            result
                        }
                    decryptHookCount++
                }
        }

        val exportedModule = runCatching {
            Class.forName(EXPORTED_MODULE, false, classLoader)
        }.getOrNull()
        if (exportedModule == null) {
            guardLog(Log.DEBUG, "optional ExportedModule path not present; using decryptItem path")
        } else {
            findMethodsNamed(exportedModule, "invokeExportedMethod")
                .forEachIndexed { index, method ->
                    hook(method)
                        .setId("millennium.feature.steam-guard.exported.$index")
                        .intercept { chain ->
                            captureSteamGuardPromise(chain.args)
                            chain.proceed()
                        }
                    exportedHookCount++
                }
        }

        guardLog(Log.INFO, "storage hooks installed decrypt=$decryptHookCount exported=$exportedHookCount")
    }

    private fun isSteamGuardRead(): Boolean {
        return Throwable().stackTrace.any {
            it.methodName == "readJSONEncodedItem" || it.methodName == "readLegacySDK20Item"
        }
    }

    private fun captureSteamGuardPromise(args: List<*>) {
        if (args.isEmpty() || args[0] != "getValueWithKeyAsync") return
        val callArgs = args.getOrNull(1) as? Collection<*> ?: return
        val key = callArgs.firstOrNull() as? String ?: return
        if (!key.startsWith("SteamGuard")) return
        guardLog(Log.DEBUG, "SteamGuard read request observed key=$key")
        val promise = callArgs.elementAtOrNull(2) ?: return
        targetPromise = promise
        val promiseClass = promise.javaClass
        synchronized(hookedPromiseClasses) {
            if (!hookedPromiseClasses.add(promiseClass)) return
        }
        findMethodsNamed(promiseClass, "resolve")
            .filter { it.parameterTypes.size == 1 }
            .forEachIndexed { index, method ->
                hook(method)
                    .setId("millennium.feature.steam-guard.promise.${promiseClass.name}.$index")
                    .intercept { chain ->
                        val value = chain.args.firstOrNull()
                        if (chain.thisObject === targetPromise && value is String) {
                            captureSteamGuard(value, "promise.resolve")
                        }
                        chain.proceed()
                    }
            }
        guardLog(Log.DEBUG, "SteamGuard promise hook installed class=${promiseClass.name}")
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

    private fun captureSteamGuard(rawJson: String, source: String) {
        val enhanced = runCatching { enhanceSteamGuardJson(rawJson, steamContext) }
            .getOrElse {
                guardLog(Log.WARN, "JSON enhancement failed; keeping original", it)
                rawJson
            }
        copySteamGuardData(enhanced, source)
    }

    private fun enhanceSteamGuardJson(rawJson: String, context: Context?): String {
        val steamGuard = JSONObject(rawJson)
        val accounts = steamGuard.optJSONObject("accounts")
        if (accounts != null) {
            val keys = accounts.keys()
            while (keys.hasNext()) {
                val account = accounts.optJSONObject(keys.next()) ?: continue
                if (account.optString("uri").isNotEmpty()) continue
                val sharedSecret = account.optString("shared_secret")
                if (sharedSecret.isEmpty()) continue
                val decoded = Base64.decode(sharedSecret, Base64.DEFAULT)
                val base32 = encodeBase32(decoded)
                account.put(
                    "uri",
                    "otpauth://totp/Steam:${account.optString("account_name")}" +
                            "?secret=$base32&issuer=Steam"
                )
            }
        }
        val uuid = context?.getSharedPreferences("steam.uuid", Context.MODE_PRIVATE)
            ?.getString("uuidKey", null)
        if (!uuid.isNullOrEmpty()) steamGuard.put("uuid_key", uuid)
        return steamGuard.toString()
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(alphabet[(buffer shr bits) and 0x1f])
            }
        }
        if (bits > 0) output.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
        return output.toString()
    }

    private fun copySteamGuardData(data: String, source: String) {
        val context = steamContext
        if (context == null) {
            guardLog(Log.ERROR, "copy skipped: Steam application context is unavailable source=$source")
            return
        }
        if (data.isEmpty()) {
            guardLog(Log.WARN, "copy skipped: empty SteamGuard data source=$source")
            Toast.makeText(context, "SteamGuard data is not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            guardLog(Log.ERROR, "clipboard service unavailable")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("SteamGuard", data))
        Toast.makeText(context, "SteamGuard data copied", Toast.LENGTH_SHORT).show()
        guardLog(Log.INFO, "data copied immediately source=$source length=${data.length}")
    }

    private fun inspectBridgelessInstance(instance: Any?) {
        if (instance == null) return
        runCatching {
            val modules = instance.javaClass.getMethod("getNativeModules").invoke(instance) as? Collection<*>
            val names = modules.orEmpty().mapNotNull { it?.javaClass?.name }.sorted()
            log(Log.INFO, TAG, "Bridgeless ReactInstance ready after loadJSBundle nativeModules=${names.size}")
            names.forEach { log(Log.DEBUG, TAG, "bridgelessNativeModule=$it") }
        }.onFailure {
            log(Log.ERROR, TAG, "Bridgeless ReactInstance inspection failed", it)
        }
    }

    private fun inspectBridgelessModules(instance: Any?, value: Any?) {
        if (instance == null) return
        val modules = value as? Collection<*> ?: return
        val names = modules.mapNotNull { it?.javaClass?.name }.sorted()
        log(Log.INFO, TAG, "Bridgeless native modules queried count=${names.size}")
        names.forEach { log(Log.DEBUG, TAG, "bridgelessNativeModule=$it") }
    }
}
