package com.millennium.app.features.steammanagement.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization
import com.millennium.app.features.steammanagement.SteamKeepDataUninstall
import com.millennium.app.features.steammanagement.SteamPrivilegeAccess
import com.millennium.app.features.ui.settings.SteamWorkingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SteamWorkingModePreference() {
    val access = SteamPrivilegeAccess
    val status = when {
        access.requestingMode == SteamWorkingMode.Root -> null
        access.requesting -> R.string.management_permission_waiting
        access.message != null -> access.message
        access.mode == SteamWorkingMode.Root && !access.ready -> R.string.management_root_unavailable
        access.enabled && !access.ready -> R.string.management_service_unavailable
        else -> null
    }
    OverlayDropdownPreference(
        title = stringResource(R.string.module_working_mode),
        summary = status?.let { stringResource(it) },
        items = listOf(
            stringResource(R.string.module_working_mode_disabled),
            stringResource(R.string.module_working_mode_shizuku),
            stringResource(R.string.module_working_mode_root),
        ),
        selectedIndex = access.mode.ordinal,
        onSelectedIndexChange = { access.selectMode(SteamWorkingMode.entries[it]) },
    )
}

@Composable
internal fun SteamManagementCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var holdDown by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SmallTitle(text = stringResource(R.string.management_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ArrowPreference(
            title = stringResource(R.string.management_uninstall),
            summary = stringResource(R.string.management_uninstall_summary),
            holdDownState = holdDown,
            onClick = {
                if (SteamPrivilegeAccess.ready && !SteamPrivilegeAccess.requesting) {
                    error = null
                    showDialog = true
                    holdDown = true
                } else {
                    Toast.makeText(
                        context,
                        ModuleLocalization.text(context, R.string.management_enable_mode),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
    OverlayDialog(
        show = showDialog,
        title = stringResource(R.string.management_uninstall),
        largeScreen = true,
        onDismissRequest = { if (!running) showDialog = false },
        onDismissFinished = { holdDown = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.management_uninstall_confirmation),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                error?.let {
                    Text(text = it, style = MiuixTheme.textStyles.body2)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.management_cancel),
                    onClick = { showDialog = false },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(
                        if (running) R.string.management_uninstalling else R.string.management_confirm,
                    ),
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        if (!running) {
                            running = true
                            error = null
                            scope.launch {
                                try {
                                    SteamKeepDataUninstall.execute(context)
                                    showDialog = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = ModuleLocalization.text(
                                        context, R.string.management_uninstall_error,
                                        failure.message.orEmpty().take(500),
                                    )
                                    SteamPrivilegeAccess.refresh()
                                } finally {
                                    running = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
