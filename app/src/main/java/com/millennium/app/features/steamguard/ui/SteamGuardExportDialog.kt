package com.millennium.app.features.steamguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.millennium.app.features.steamguard.data.SteamGuardAccount
import com.millennium.app.features.steamguard.data.SteamGuardData
import com.millennium.app.features.steamguard.export.SteamGuardClipboard
import com.millennium.app.features.steamguard.export.SteamGuardQrCode
import com.millennium.app.features.steamguard.export.SteamGuardQrFormat
import com.millennium.app.features.steamguard.export.SteamGuardQrPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SteamGuardExportEntry() {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var holdDown by rememberSaveable { mutableStateOf(false) }

    ArrowPreference(
        title = "Steam 令牌导出",
        summary = "查看令牌信息",
        onClick = {
            showDialog = true
            holdDown = true
        },
        holdDownState = holdDown,
    )

    OverlayDialog(
        show = showDialog,
        title = "Steam 令牌导出",
        largeScreen = true,
        onDismissRequest = { showDialog = false },
        onDismissFinished = { holdDown = false },
    ) {
        // Read the cached result before the first frame; later storage reads update the open dialog.
        val accounts by SteamGuardData.accounts.collectAsState(
            initial = remember { SteamGuardData.currentAccounts() },
        )
        var selectedUsername by rememberSaveable { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val entries = accounts.orEmpty()
                if (entries.isEmpty()) {
                    ExportAccount(null)
                    Text(
                        text = if (accounts == null) "尚未获取令牌数据，可先打开 Steam 令牌页面。"
                        else "未找到可导出的 Steam 令牌。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                } else {
                    val selectedIndex = entries.indexOfFirst { it.username == selectedUsername }
                        .coerceAtLeast(0)
                    if (entries.size > 1) {
                        OverlayDropdownPreference(
                            title = "账号",
                            items = entries.map { it.username },
                            selectedIndex = selectedIndex,
                            insideMargin = PaddingValues(vertical = 8.dp),
                            onSelectedIndexChange = { selectedUsername = entries[it].username },
                        )
                    }
                    val account = entries[selectedIndex]
                    ExportAccount(account)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    ExportQrCode(account)
                }
            }
            TextButton(
                text = "关闭",
                onClick = { showDialog = false },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun ExportQrCode(account: SteamGuardAccount) {
    var format by rememberSaveable { mutableStateOf(SteamGuardQrFormat.Stratum) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TabRowWithContour(
            tabs = remember { SteamGuardQrFormat.entries.map { it.label } },
            selectedTabIndex = format.ordinal,
            onTabSelected = { format = SteamGuardQrFormat.entries[it] },
        )
        if (account.username.isBlank() || account.secret.isBlank()) {
            Text(
                text = "令牌数据不完整，暂无法生成二维码。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        } else {
            // Generate both formats once per account so changing tabs never shows a loader.
            key(account) {
                val qrCodes by produceState<Result<SteamGuardQrPair>?>(initialValue = null) {
                    value = withContext(Dispatchers.Default) {
                        runCatching { SteamGuardQrCode.createPair(account) }
                    }
                }
                Box(
                    modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val codes = qrCodes?.getOrNull()
                    if (codes != null) {
                        SteamGuardQrMorph(
                            codes = codes,
                            format = format,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (qrCodes?.isFailure == true) {
                        Text(
                            text = "二维码生成失败",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    }
                }
            }
            Text(
                text = "使用 ${format.label} 扫描二维码导入",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ExportAccount(account: SteamGuardAccount?) {
    ExportField(label = "类型", value = "Steam")
    ExportField(label = "用户名", value = account?.username)
    ExportField(label = "服务商", value = "Steam")
    ExportField(label = "密钥", value = account?.secret)
}

@Composable
private fun ExportField(label: String, value: String?) {
    val context = LocalContext.current
    val viewConfiguration = LocalViewConfiguration.current
    val textTouchConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    var valueLayout by remember(value) { mutableStateOf<TextLayoutResult?>(null) }
    val valueShape = remember(valueLayout) {
        valueLayout?.let { layout ->
            GenericShape { _, _ -> addPath(layout.getPathForRange(0, layout.layoutInput.text.length)) }
        } ?: RectangleShape
    }
    val textStyle = MiuixTheme.textStyles.body1
    val labelWidth = with(LocalDensity.current) { (textStyle.fontSize * 4).toDp() }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label：",
            modifier = Modifier.width(labelWidth).alignByBaseline(),
            style = textStyle,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        // Keep Compose's automatic touch-target expansion out of the surrounding blank space.
        CompositionLocalProvider(LocalViewConfiguration provides textTouchConfiguration) {
            Text(
                text = value?.ifEmpty { null } ?: "暂无$label",
                modifier = Modifier
                    .weight(1f, fill = false)
                    .alignByBaseline()
                    .clip(valueShape)
                    .clickable(
                        enabled = !value.isNullOrEmpty(),
                        onClickLabel = "复制$label",
                        role = Role.Button,
                    ) {
                        value?.let { SteamGuardClipboard.copy(context, label, it) }
                    },
                style = textStyle,
                color = MiuixTheme.colorScheme.onBackground,
                onTextLayout = { valueLayout = it },
            )
        }
    }
}
