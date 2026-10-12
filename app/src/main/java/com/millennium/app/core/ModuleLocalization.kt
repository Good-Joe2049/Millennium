package com.millennium.app.core

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

internal enum class ModuleLanguage(val tag: String) {
    System(""), English("en"), SimplifiedChinese("zh-Hans");

    companion object {
        fun fromTag(tag: String?): ModuleLanguage = entries.firstOrNull { it.tag == tag } ?: System
    }
}

/** Reads the module APK's strings without changing Steam's resources or process locale. */
internal object ModuleLocalization {
    var language by mutableStateOf(ModuleLanguage.System)
    private var apkPath: String? = null
    private var assetsSource: Resources? = null
    private var localized: Resources? = null

    fun setModuleApkPath(path: String?) {
        if (apkPath == path) return
        apkPath = path
        assetsSource = null
        localized = null
    }

    @Suppress("DEPRECATION")
    fun resources(context: Context, configuration: Configuration = context.resources.configuration): Resources {
        val config = Configuration(configuration).apply {
            setLocales(
                if (language == ModuleLanguage.System) Resources.getSystem().configuration.locales
                else LocaleList(Locale.forLanguageTag(language.tag)),
            )
        }
        localized?.takeIf { it.configuration == config }?.let { return it }
        val source = assetsSource ?: run {
            val path = apkPath
            val result = if (path == null) {
                // Also supports running the module UI in its own app for previews and tests.
                context.createPackageContext("com.millennium.app", 0).resources
            } else {
                val info = checkNotNull(context.packageManager.getPackageArchiveInfo(path, 0)?.applicationInfo).apply {
                    sourceDir = path
                    publicSourceDir = path
                }
                context.packageManager.getResourcesForApplication(info)
            }
            assetsSource = result
            result
        }
        return Resources(source.assets, context.resources.displayMetrics, config).also { localized = it }
    }

    fun text(context: Context, @StringRes id: Int, vararg args: Any): String =
        resources(context).getString(id, *args)

    fun quantity(context: Context, @PluralsRes id: Int, count: Int, vararg args: Any): String =
        resources(context).getQuantityString(id, count, *args)

    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val resources = resources(context, LocalConfiguration.current)
        val localizedContext = remember(context, resources) {
            object : ContextWrapper(context) {
                override fun getResources(): Resources = resources
                override fun getAssets() = resources.assets
            }
        }
        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides resources.configuration,
            LocalResources provides resources,
            content = content,
        )
    }
}
