package com.millennium.app.features.guest

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.millennium.app.features.ui.settings.SteamModuleSettings
import io.github.libxposed.api.XposedModule
import java.io.File
import java.security.MessageDigest

/** Installs the guest navigation policy before Steam registers its React components. */
internal class SteamGuestBrowsingFeature(
    private val context: () -> Context?,
    private val emit: (Int, String) -> Unit,
) {
    private var prepared = false
    private var bootstrap: File? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        installBootstrapHook(module, loader)
    }

    private fun installBootstrapHook(module: XposedModule, loader: ClassLoader) {
        val instance = Class.forName("com.facebook.react.runtime.ReactInstance", false, loader)
        val bundleLoader = Class.forName("com.facebook.react.bridge.JSBundleLoader", false, loader)
        val assetLoader = Class.forName("com.facebook.react.bridge.JSBundleLoader$1", false, loader)
        val assetUrl = assetLoader.getDeclaredField($$"val$assetUrl").apply { isAccessible = true }
        val load = instance.getDeclaredMethod("loadJSBundle", bundleLoader)
        val loadFile = instance.getDeclaredMethod("loadJSBundleFromFile", String::class.java, String::class.java)
            .apply { isAccessible = true }
        module.hook(load).setId("millennium.guest.bootstrap").intercept { chain ->
            runCatching {
                val sourceLoader = chain.args.firstOrNull()
                // The APK hash cannot validate an Expo-downloaded bundle or another source.
                if (assetLoader.isInstance(sourceLoader) && assetUrl.get(sourceLoader) == "assets://index.android.bundle") {
                    prepare()?.let { file ->
                        // React queues both scripts on its JS runtime before starting the surface.
                        loadFile.invoke(chain.thisObject, file.absolutePath, file.absolutePath)
                    }
                } else {
                    emit(Log.WARN, "Guest browsing unavailable for this bundle source; native navigation retained")
                }
            }
            chain.proceed()
        }
    }

    @Synchronized
    private fun prepare(): File? {
        if (prepared) return bootstrap
        val app = context() ?: return null
        prepared = true
        // Module IDs are private to this bundle. Never apply them to a different Steam build.
        val digest = MessageDigest.getInstance("SHA-256")
        app.assets.open("index.android.bundle").use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (hash != SUPPORTED_BUNDLE) {
            emit(Log.WARN, "Guest browsing unavailable for this Steam bundle; native navigation retained")
            return null
        }
        val file = File(app.codeCacheDir, "millennium-guest-bootstrap.js")
        val script = SteamGuestBrowsingScript.source(
            defaultRoute = SteamModuleSettings.steamGuardDefaultPage(app).routeName,
            nextCodeEnabled = SteamModuleSettings.isSteamGuardNextCodeEnabled(app),
            libraryPinchZoomEnabled = SteamModuleSettings.isSteamLibraryPinchZoomEnabled(app),
        )
        val bytes = script.toByteArray(Charsets.UTF_8)
        if (!file.isFile || file.readText() != script) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        }
        bootstrap = file
        return file
    }

    private companion object {
        // Steam 3.10.11 (10928360), Hermes bytecode version 96.
        const val SUPPORTED_BUNDLE = "f877cc35eff2a2a833c6330e8650b296369e2ccb4e07984ca6559b2daa4090a4"
    }
}
