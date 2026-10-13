package com.millennium.app.features.steamguard.export

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.OpenableColumns
import com.millennium.app.R
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SAF bridge for a module dialog hosted by Steam, independent of the host's AndroidX classloader. */
internal object SteamMaFileDocuments {
    data class State(val busy: Boolean = false, val preview: SteamMaFile? = null, val name: String = "", val message: Int = 0)
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var module: XposedModule? = null
    private val hooked = hashSetOf<Method>()
    private data class Request(val component: String, val export: SteamMaFile?)
    private var pending: Request? = null
    private const val REQUEST = 0x6d46

    fun install(module: XposedModule) { this.module = module }

    fun open(context: Context) = launch(context, null)
    fun save(context: Context, account: SteamMaFile) = launch(context, account)
    fun message(id: Int) { current.value = current.value.copy(message = id) }

    @Suppress("DEPRECATION")
    private fun launch(context: Context, export: SteamMaFile?) {
        if (current.value.busy) return
        runCatching {
            val activity = activity(context) ?: error("activity unavailable")
            installResultHook(activity)
            val intent = Intent(if (export == null) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // DocumentsUI appends .json for application/json; maFile must keep its own extension.
                type = if (export == null) "*/*" else "application/octet-stream"
                if (export != null) putExtra(Intent.EXTRA_TITLE, export.fileName)
            }
            pending = Request(activity.componentName.flattenToString(), export)
            current.value = if (export == null) State(busy = true) else current.value.copy(busy = true, message = 0)
            activity.startActivityForResult(intent, REQUEST)
        }.onFailure {
            pending = null
            current.value = current.value.copy(busy = false, message = R.string.mafile_picker_failed)
        }
    }

    private fun installResultHook(activity: Activity) {
        val owner = checkNotNull(module)
        var type: Class<*>? = activity.javaClass
        var result: Method? = null
        while (type != null && result == null) {
            result = runCatching { type.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java) }.getOrNull()
            type = type.superclass
        }
        val method = checkNotNull(result)
        if (method in hooked) return
        owner.hook(method).setId("millennium.mafile.documents.${method.declaringClass.name}")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                if (handleResult(chain.thisObject as Activity, chain.args[0] as Int, chain.args[1] as Int, chain.args[2] as? Intent)) null
                else chain.proceed()
            }
        hooked += method
    }

    internal fun handleResult(activity: Activity, code: Int, result: Int, intent: Intent?): Boolean {
        val request = pending ?: return false
        if (code != REQUEST || activity.componentName.flattenToString() != request.component) return false
        pending = null
        if (result != Activity.RESULT_OK) {
            current.value = current.value.copy(busy = false, message = 0)
            return true
        }
        val resolver = activity.applicationContext.contentResolver
        val uri = intent?.data
        scope.launch {
            try {
                require(uri?.scheme == "content")
                if (request.export == null) {
                    val (name, file) = withContext(Dispatchers.IO) {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0).orEmpty().take(200) else ""
                        }.orEmpty()
                        val bytes = checkNotNull(resolver.openInputStream(uri)).use { SteamMaFileCodec.readBytes(it) }
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                        name to SteamMaFileCodec.parse(text, name)
                    }
                    current.value = State(preview = file, name = name)
                } else {
                    withContext(Dispatchers.IO) {
                        checkNotNull(resolver.openOutputStream(uri, "wt")).use {
                            it.write(request.export.json.toByteArray(Charsets.UTF_8))
                        }
                    }
                    current.value = current.value.copy(busy = false, message = R.string.mafile_saved)
                }
            } catch (_: Exception) {
                current.value = current.value.copy(busy = false, message =
                    if (request.export == null) R.string.mafile_read_failed else R.string.mafile_write_failed)
            }
        }
        return true
    }

    private fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return null
    }
}
