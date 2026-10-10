package com.millennium.app.features.steamguard.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast

internal object SteamGuardClipboard {
    fun copy(context: Context, label: String, value: String) {
        if (value.isEmpty()) return
        val copied = runCatching {
            val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
            val clip = ClipData.newPlainText(label, value).apply {
                description.extras = PersistableBundle().apply {
                    // Use the compatibility key so older Android versions also compile.
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clipboard.setPrimaryClip(clip)
        }.isSuccess
        Toast.makeText(
            context,
            if (copied) "已复制到剪贴板" else "复制失败，请重试",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
