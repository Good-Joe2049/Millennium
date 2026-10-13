package com.millennium.app.features.steamguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.millennium.app.R
import com.millennium.app.features.steamguard.data.SteamGuardData
import com.millennium.app.features.steamguard.data.SteamSessionData
import com.millennium.app.features.steamguard.data.SteamMaFileStore
import com.millennium.app.features.steamguard.data.SteamLoginRestore
import com.millennium.app.features.steamguard.export.SteamGuardClipboard
import com.millennium.app.features.steamguard.export.SteamMaFile
import com.millennium.app.features.steamguard.export.SteamMaFileCodec
import com.millennium.app.features.steamguard.export.SteamMaFileDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
internal fun SteamMaFileEntry() {
    var show by rememberSaveable { mutableStateOf(false) }
    var hold by rememberSaveable { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(R.string.mafile_title), summary = stringResource(R.string.mafile_summary),
        onClick = { show = true; hold = true }, holdDownState = hold,
    )
    var working by remember { mutableStateOf(false) }
    OverlayDialog(
        show = show, title = stringResource(R.string.mafile_title), largeScreen = true,
        onDismissRequest = { if (!working) show = false }, onDismissFinished = { hold = false },
    ) {
        MaFileContent(working, { working = it }, { show = false })
    }
}

@Composable
private fun MaFileContent(working: Boolean, setWorking: (Boolean) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guard by SteamGuardData.snapshot.collectAsState()
    val auth by SteamSessionData.json.collectAsState()
    val store by SteamMaFileStore.state.collectAsState()
    val documents by SteamMaFileDocuments.state.collectAsState()
    val live = remember(guard, auth) { SteamMaFileCodec.live(guard, auth) }
    val choices = live.map { "live:${it.key}" to it } + store.accounts.map { "import:${it.key}" to it }
    var selected by remember { mutableStateOf<String?>(null) }
    val index = choices.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val account = choices.getOrNull(index)?.second
    val imported = choices.getOrNull(index)?.first?.startsWith("import:") == true
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var confirmedAccount by remember { mutableStateOf<SteamMaFile?>(null) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    val busy = working || documents.busy
    fun CoroutineScope.work(success: Int, action: () -> Unit, done: () -> Unit = {}) {
        setWorking(true)
        launch {
            try {
                withContext(Dispatchers.IO) { action() }
                SteamMaFileDocuments.message(success)
                done()
            } catch (_: Exception) {
                SteamMaFileDocuments.message(R.string.mafile_operation_failed)
            } finally { setWorking(false) }
        }
    }
    fun import(file: SteamMaFile, replace: Boolean) {
        scope.work(R.string.mafile_imported, { SteamMaFileStore.import(context, file, replace) }) {
            selected = "import:${file.key}"
            tab = 0
        }
    }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { SteamMaFileStore.load(context) }
            ready = true
        } catch (_: Exception) { SteamMaFileDocuments.message(R.string.mafile_store_failed) }
    }
    Column(Modifier.fillMaxWidth()) {
        TabRowWithContour(
            tabs = listOf(stringResource(R.string.mafile_export_tab), stringResource(R.string.mafile_import_tab)),
            selectedTabIndex = tab, onTabSelected = { if (!busy) tab = it },
        )
        Column(Modifier.weight(1f, fill = false).padding(top = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0) {
                if (account == null) {
                    Note(stringResource(R.string.mafile_no_accounts))
                } else {
                    if (choices.size > 1) OverlayDropdownPreference(
                        title = stringResource(R.string.guard_account),
                        items = choices.map { (key, value) ->
                            "${value.accountName.ifEmpty { value.steamId }} · " + stringResource(
                                if (key.startsWith("import:")) R.string.mafile_source_import_short else R.string.mafile_source_live_short)
                        }, selectedIndex = index, insideMargin = PaddingValues(vertical = 4.dp),
                        onSelectedIndexChange = { if (!busy) selected = choices[it].first },
                    )
                    Note(stringResource(if (imported) R.string.mafile_source_import else R.string.mafile_source_live))
                    AccountDetails(account) { detail = it }
                    Note(stringResource(R.string.mafile_plaintext))
                    if (imported) {
                        HorizontalDivider()
                        val issue = SteamMaFileCodec.loginIssue(account)
                        Note(stringResource(when {
                            !SteamLoginRestore.available -> R.string.mafile_restore_unavailable
                            issue == SteamMaFileCodec.LoginIssue.Missing -> R.string.mafile_login_missing
                            issue == SteamMaFileCodec.LoginIssue.Expired -> R.string.mafile_login_expired
                            issue == SteamMaFileCodec.LoginIssue.Invalid -> R.string.mafile_login_invalid
                            else -> R.string.mafile_login_ready
                        }))
                        TextButton(
                            text = stringResource(R.string.mafile_restore), modifier = Modifier.fillMaxWidth(),
                            enabled = ready && !busy && issue == null && SteamLoginRestore.available,
                            onClick = { confirmedAccount = account; confirmation = "restore" },
                        )
                    }
                }
            } else {
                Note(stringResource(R.string.mafile_import_help))
                TextButton(text = stringResource(R.string.mafile_choose), onClick = { SteamMaFileDocuments.open(context) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                documents.preview?.let { preview ->
                    Note(documents.name)
                    AccountDetails(preview) { detail = it }
                }
            }
            if (store.restore.isNotEmpty()) {
                Note(stringResource(when (store.restore) {
                    "queued" -> R.string.mafile_restore_queued
                    "applied" -> R.string.mafile_restore_applied
                    "applying", "interrupted" -> R.string.mafile_restore_interrupted
                    else -> R.string.mafile_restore_failed
                }))
                if (store.restore == "queued") TextButton(
                    text = stringResource(R.string.mafile_cancel_restore), enabled = !busy,
                    onClick = { scope.work(0, { SteamLoginRestore.cancel(context) }) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (store.canUndo && store.restore != "queued") TextButton(
                text = stringResource(R.string.mafile_undo), enabled = !busy && SteamLoginRestore.available,
                onClick = { confirmation = "undo" }, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (busy || documents.message != 0) {
            Column(Modifier.padding(top = 12.dp)) {
                Note(stringResource(if (busy) R.string.mafile_working else documents.message))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.module_close), onClick = close, enabled = !working, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(if (tab == 0) R.string.mafile_save else R.string.mafile_import),
                onClick = {
                    if (tab == 0) account?.let { SteamMaFileDocuments.save(context, it) }
                    else documents.preview?.let { file ->
                        if (store.accounts.any { it.key == file.key }) { confirmedAccount = file; confirmation = "replace" }
                        else import(file, false)
                    }
                },
                enabled = !busy && if (tab == 0) account != null else ready && documents.preview != null,
                modifier = Modifier.weight(1.5f), colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
    OverlayDialog(show = confirmation != null, title = stringResource(R.string.mafile_confirm_title), largeScreen = true,
        onDismissRequest = { confirmation = null; confirmedAccount = null }) {
        Note(stringResource(when (confirmation) {
            "restore" -> R.string.mafile_restore_confirm
            "undo" -> R.string.mafile_undo_confirm
            else -> R.string.mafile_replace_confirm
        }, confirmedAccount?.accountName.orEmpty(), confirmedAccount?.steamId.orEmpty()))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.mafile_cancel), onClick = { confirmation = null; confirmedAccount = null }, modifier = Modifier.weight(1f))
            TextButton(text = stringResource(R.string.mafile_confirm), modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                val file = confirmedAccount
                val action = confirmation
                confirmation = null; confirmedAccount = null
                when (action) {
                    "restore" -> if (file != null) scope.work(0, { SteamLoginRestore.schedule(context, file) })
                    "undo" -> scope.work(0, { SteamLoginRestore.undo(context) })
                    "replace" -> if (file != null) import(file, true)
                }
            })
        }
    }
    OverlayDialog(show = detail != null, title = detail?.first.orEmpty(), largeScreen = true, onDismissRequest = { detail = null }) {
        SelectionContainer { Text(detail?.second.orEmpty(), modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.module_close), onClick = { detail = null }, modifier = Modifier.weight(1f))
            TextButton(text = stringResource(R.string.mafile_copy), modifier = Modifier.weight(1f), onClick = {
                detail?.let { SteamGuardClipboard.copy(context, it.first, it.second) }
            })
        }
    }
}

@Composable
private fun AccountDetails(account: SteamMaFile, show: (Pair<String, String>) -> Unit) {
    val root = remember(account.json) { JSONObject(account.json) }
    Column(Modifier.fillMaxWidth()) {
        listOf("account_name", "steamid", "shared_secret", "identity_secret", "device_id", "revocation_code", "access_token", "refresh_token").forEach { key ->
            val value = root.optString(key, "")
            val label = when (key) {
                "account_name" -> stringResource(R.string.guard_username)
                "steamid" -> "SteamID"
                else -> key
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable(enabled = value.isNotEmpty(), role = Role.Button, onClickLabel = stringResource(R.string.mafile_view_copy)) { show(label to value) }
                .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, modifier = Modifier.weight(0.8f), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Text(if (value.isEmpty()) stringResource(R.string.mafile_missing)
                else if (key == "account_name" || key == "steamid") value else stringResource(R.string.mafile_present),
                    modifier = Modifier.weight(1.2f), style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

@Composable
private fun Note(text: String) { Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary) }
