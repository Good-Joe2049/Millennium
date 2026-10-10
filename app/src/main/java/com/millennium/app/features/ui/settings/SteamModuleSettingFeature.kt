package com.millennium.app.features.ui.settings

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.drawToBitmap
import java.util.IdentityHashMap

/** Owns a full-screen window in Steam; ComponentDialog supplies module-side Compose owners. */
internal class SteamModuleSettingFeature(
    private val emit: (priority: Int, message: String) -> Unit,
    private val onSteamDbChanged: (Activity, Boolean) -> Unit,
) {
    private val sessions = IdentityHashMap<Activity, SettingSession>()
    private val pendingRestore = IdentityHashMap<Activity, SteamModuleSettingState>()
    private var moduleApkPath: String? = null

    fun setModuleApkPath(path: String?) {
        moduleApkPath = path
    }

    fun show(activity: Activity) = show(activity, restoredState = null)

    private fun show(activity: Activity, restoredState: SteamModuleSettingState?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { show(activity, restoredState) }
            return
        }
        if (activity.isFinishing || activity.isDestroyed || sessions.containsKey(activity)) return

        var openingDialog: ComponentDialog? = null
        try {
            val context = settingContext(activity)
            val dialog = ComponentDialog(context, android.R.style.Theme_Material_NoActionBar)
            openingDialog = dialog
            val session = SettingSession(dialog, restoredState ?: SteamModuleSettingState())
            if (restoredState == null) session.menuSnapshot = captureMenu(activity)
            dialog.setOwnerActivity(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCanceledOnTouchOutside(false)
            val window = checkNotNull(dialog.window)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // The Miuix navigation host owns both entry and exit; avoid a second window animation.
            window.setWindowAnimations(0)
            val content = ComposeView(dialog.context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    var enabled by remember { mutableStateOf(SteamModuleSettings.isSteamDbEnabled(activity)) }
                    var liquidGlassEnabled by remember {
                        mutableStateOf(SteamModuleSettings.isLiquidGlassEnabled(activity))
                    }
                    val dark = isSystemInDarkTheme()
                    SideEffect { styleSystemBars(window, dark) }
                    SteamModuleSettingNavigation(
                        menuSnapshot = session.menuSnapshot,
                        pageState = session.pageState,
                        animateEntrance = restoredState == null,
                        closing = session.closing,
                        steamDbEnabled = enabled,
                        liquidGlassEnabled = liquidGlassEnabled,
                        onLiquidGlassEnabledChange = { value ->
                            SteamModuleSettings.setLiquidGlassEnabled(activity, value)
                            liquidGlassEnabled = value
                            emit(Log.INFO, "module setting changed liquidGlassEnabled=$value")
                        },
                        onSteamDbEnabledChange = { value ->
                            SteamModuleSettings.setSteamDbEnabled(activity, value)
                            enabled = value
                            onSteamDbChanged(activity, value)
                            emit(Log.INFO, "module setting changed steamDbEnabled=$value")
                        },
                        onRequestClose = {
                            if (!session.closing) {
                                session.menuSnapshot = captureMenu(activity) ?: session.menuSnapshot
                                session.closing = true
                            }
                        },
                        onClosed = { dialog.dismiss() },
                    )
                }
            }
            dialog.setContentView(content)
            dialog.setOnDismissListener {
                sessions.remove(activity, session)
                content.disposeComposition()
                session.menuSnapshot = null
                emit(Log.INFO, "module setting closed")
            }
            sessions[activity] = session
            dialog.show()
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            emit(Log.INFO, "module setting opened page=miuix fullscreen=true transition=miuix-default restored=${restoredState != null}")
        } catch (error: Exception) {
            openingDialog?.dismiss()
            sessions.remove(activity)
            emit(Log.ERROR, "module setting open failed: " + Log.getStackTraceString(error))
            Toast.makeText(activity, "模块设置打开失败，请查看 module setting 日志", Toast.LENGTH_LONG).show()
        }
    }

    fun detach(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { detach(activity) }
            return
        }
        pendingRestore.remove(activity)
        sessions.remove(activity)?.dialog?.dismiss()
    }

    /** Called only for actual Activity recreation; ordinary backgrounding keeps the window alive. */
    fun restoreState(activity: Activity, savedInstanceState: Bundle?) {
        val saved = savedInstanceState?.getBundle(STATE_KEY) ?: return
        pendingRestore[activity] = SteamModuleSettingState(
            selectedTab = saved.getInt("tab"),
            scrollPosition = saved.getInt("scroll"),
        )
    }

    fun resume(activity: Activity) {
        pendingRestore.remove(activity)?.let { show(activity, restoredState = it) }
    }

    fun saveState(activity: Activity, outState: Bundle) {
        outState.remove(STATE_KEY)
        val session = sessions[activity]
        if (session?.closing == true) return
        val state = session?.pageState ?: pendingRestore[activity] ?: return
        outState.putBundle(STATE_KEY, Bundle().apply {
            putInt("tab", state.selectedTab)
            putInt("scroll", state.scroll.value)
        })
    }

    private fun captureMenu(activity: Activity): Bitmap? = try {
        // The real Steam menu is outside Compose. Use a temporary, in-memory image as the
        // covered navigation page so Miuix can apply its original parallax, dim and corner clip.
        activity.window.decorView.drawToBitmap()
    } catch (error: RuntimeException) {
        emit(Log.WARN, "module setting menu snapshot unavailable: ${error.message}")
        null
    }

    private class SettingSession(
        val dialog: ComponentDialog,
        val pageState: SteamModuleSettingState,
    ) {
        var menuSnapshot by mutableStateOf<Bitmap?>(null)
        var closing by mutableStateOf(false)
    }

    private companion object {
        const val STATE_KEY = "com.millennium.module.setting.page"
    }

    @Suppress("DEPRECATION")
    private fun settingContext(activity: Activity): Context {
        val path = checkNotNull(moduleApkPath) { "Module APK path unavailable" }
        val info = checkNotNull(activity.packageManager.getPackageArchiveInfo(path, 0)?.applicationInfo) {
            "Module APK resources unavailable"
        }.apply {
            sourceDir = path
            publicSourceDir = path
        }
        val moduleResources = activity.packageManager.getResourcesForApplication(info)
        val moduleLoader = SteamModuleSettingFeature::class.java.classLoader
        // Compose reads its own resource IDs, not the host's identically numbered resources.
        return object : ContextThemeWrapper(activity, android.R.style.Theme_Material_NoActionBar) {
            override fun getResources(): Resources = moduleResources
            override fun getAssets(): AssetManager = moduleResources.assets
            override fun getClassLoader(): ClassLoader = checkNotNull(moduleLoader)
            override fun getApplicationContext(): Context = this
        }
    }

    @Suppress("DEPRECATION")
    private fun styleSystemBars(window: Window, dark: Boolean) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
