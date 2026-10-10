package com.millennium.app

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.millennium.app.core.SteamRuntimeDiagnostics
import com.millennium.app.features.steamdb.network.SteamDbConnectionDiagnostics
import com.millennium.app.features.steamguard.data.SteamGuardStorageHook
import com.millennium.app.features.ui.SteamUiProbeFeature
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Installs the module features in Steam. */
class ModuleMain : XposedModule() {
    private var loadedProcess = "unknown"
    private var moduleApkPath: String? = null

    companion object {
        private const val TAG = "MillenniumXposed"
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

    private val steamGuardStorageHook = SteamGuardStorageHook(this) { steamContext }
    private var lastTouchTargetLogKey: String? = null
    private var lastTouchTargetLogAt = 0L

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        loadedProcess = param.processName
        log(Log.INFO, TAG, "loaded process=$loadedProcess framework=$frameworkName/$frameworkVersion api=$apiVersion")
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

        runCatching {
            SteamDbConnectionDiagnostics.install(this, param.classLoader) { priority, message ->
                log(priority, "MillenniumSteamUI", message)
            }
        }.onFailure {
            log(Log.WARN, "MillenniumSteamUI", "steamdb network diagnostics install failed", it)
        }

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

        runCatching { steamGuardStorageHook.install(param.classLoader) }
            .onFailure { log(Log.ERROR, "MillenniumSteamGuard", "storage hook failed", it) }

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
        val methods = findTouchTargetMethods(helper)
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

    private fun findTouchTargetMethods(type: Class<*>): List<Method> {
        val methods = linkedSetOf<Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods
                .filter {
                    it.name == "findTargetTagAndCoordinatesForTouch" && !Modifier.isAbstract(it.modifiers)
                }
                .forEach { methods += it }
            current = current.superclass
        }
        return methods.toList()
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
