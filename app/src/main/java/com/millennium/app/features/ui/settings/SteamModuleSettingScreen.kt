package com.millennium.app.features.ui.settings

import androidx.compose.ui.res.stringResource
import com.millennium.app.core.ModuleLanguage
import com.millennium.app.core.ModuleLocalization
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.millennium.app.R
import com.millennium.app.features.steamguard.ui.SteamGuardExportEntry
import com.millennium.app.features.steamguard.ui.SteamMaFileEntry
import com.millennium.app.features.steammanagement.SteamPrivilegeAccess
import com.millennium.app.features.steammanagement.ui.SteamManagementCard
import com.millennium.app.features.steammanagement.ui.SteamWorkingModePreference
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
import android.widget.Toast
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.horizontalPagerSwipeOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/** UI state retained while backgrounded and saved as primitives when the host is recreated. */
internal class SteamModuleSettingState(selectedTab: Int = 0, scrollPosition: Int = 0) {
    val pagerState = PagerState(currentPage = selectedTab.coerceIn(0, 3), pageCount = { 4 })
    val scrollStates = List(4) { page ->
        ScrollState(if (page == pagerState.currentPage) scrollPosition.coerceAtLeast(0) else 0)
    }
    val selectedTab: Int get() = pagerState.targetPage
    val scroll: ScrollState get() = scrollStates[selectedTab]
}

@Composable
internal fun SteamModuleSettingScreen(
    state: SteamModuleSettingState,
    onLanguageChange: (ModuleLanguage) -> Unit,
    steamDbEnabled: Boolean,
    onSteamDbEnabledChange: (Boolean) -> Unit,
    liquidGlassEnabled: Boolean,
    onLiquidGlassEnabledChange: (Boolean) -> Unit,
    steamGuardDefaultPage: SteamGuardDefaultPage,
    onSteamGuardDefaultPageChange: (SteamGuardDefaultPage) -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener(SteamPrivilegeAccess::onWindowFocusChanged)
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            SteamPrivilegeAccess.cancelRequest()
        }
    }
    val tabs = listOf(
        stringResource(R.string.module_general) to R.drawable.ic_module_nav_general,
        stringResource(R.string.module_themes) to R.drawable.ic_module_nav_themes,
        stringResource(R.string.module_logs) to R.drawable.ic_module_nav_logs,
        stringResource(R.string.module_settings) to R.drawable.ic_module_nav_settings,
    )

    val scope = rememberCoroutineScope()
    val selectTab: (Int) -> Unit = { index ->
        if (index != state.selectedTab) {
            scope.launch { state.pagerState.springAnimateToPage(index) }
        }
    }

    val dark = isSystemInDarkTheme()
    val colors = remember(dark) { if (dark) darkColorScheme() else lightColorScheme() }
    val glassSupported = SteamModuleSettingLiquidGlassFeature.isSupported
    val useGlass = liquidGlassEnabled && glassSupported
    // Only capture the page, never the bar that reads it (avoids recursive glass capture).
    val backdrop = if (useGlass) rememberLayerBackdrop() else null
    val layoutDirection = LocalLayoutDirection.current
    MiuixTheme(colors = colors) {
        Scaffold(
            topBar = {
                SmallTopAppBar(title = "Millennium")
            },
            bottomBar = {
                val items: @Composable RowScope.() -> Unit = {
                    tabs.forEachIndexed { index, (label, iconRes) ->
                        NavigationBarItem(
                            selected = state.selectedTab == index,
                            icon = ImageVector.vectorResource(iconRes),
                            label = label,
                            onClick = { selectTab(index) },
                        )
                    }
                }
                if (backdrop != null) {
                    SteamModuleSettingLiquidGlassFeature.BottomBar(
                        backdrop = backdrop,
                        selectedTab = state.selectedTab,
                        tabs = tabs,
                        onTabSelected = selectTab,
                    )
                } else {
                    NavigationBar(content = items)
                }
            },
        ) { paddingValues ->
            HorizontalPager(
                state = state.pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = paddingValues.calculateStartPadding(layoutDirection),
                        top = paddingValues.calculateTopPadding(),
                        end = paddingValues.calculateEndPadding(layoutDirection),
                        bottom = if (useGlass) 0.dp else paddingValues.calculateBottomPadding(),
                    )
                    .consumeWindowInsets(paddingValues)
                    .then(
                        if (backdrop != null) Modifier.layerBackdrop(backdrop).background(colors.surface)
                        else Modifier,
                    )
                    .horizontalPagerSwipeOverride(state.pagerState),
                // Miuix supplies the touch recognizer; disable the competing native one.
                userScrollEnabled = false,
                pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                verticalAlignment = Alignment.Top,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = state.pagerState,
                    snapAnimationSpec = PagerNavigationSpringSpec,
                ),
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(state.scrollStates[page])
                        // Scroll underneath the glass, but allow the last control to clear the bar.
                        .padding(bottom = if (useGlass) paddingValues.calculateBottomPadding() + 16.dp else 16.dp),
                ) {
                    when (page) {
                        0 -> GeneralPage(
                            steamDbEnabled = steamDbEnabled,
                            onSteamDbEnabledChange = onSteamDbEnabledChange,
                            liquidGlassEnabled = useGlass,
                            glassSupported = glassSupported,
                            onLiquidGlassEnabledChange = onLiquidGlassEnabledChange,
                            steamGuardDefaultPage = steamGuardDefaultPage,
                            onSteamGuardDefaultPageChange = onSteamGuardDefaultPageChange,
                        )
                        1 -> PlaceholderPage(
                            title = stringResource(R.string.module_themes),
                            summary = stringResource(R.string.module_themes_summary),
                        )
                        2 -> PlaceholderPage(
                            title = stringResource(R.string.module_logs),
                            summary = stringResource(R.string.module_logs_summary),
                        )
                        else -> SettingsPage(onLanguageChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralPage(
    steamDbEnabled: Boolean,
    onSteamDbEnabledChange: (Boolean) -> Unit,
    liquidGlassEnabled: Boolean,
    glassSupported: Boolean,
    onLiquidGlassEnabledChange: (Boolean) -> Unit,
    steamGuardDefaultPage: SteamGuardDefaultPage,
    onSteamGuardDefaultPageChange: (SteamGuardDefaultPage) -> Unit,
) {
    val context = LocalView.current.context
    val restartMessage = stringResource(R.string.module_restart_required)
    var steamGlassEnabled by remember { mutableStateOf(SteamModuleSettings.isSteamBottomGlassEnabled(context)) }
    var nextCodeEnabled by remember { mutableStateOf(SteamModuleSettings.isSteamGuardNextCodeEnabled(context)) }
    var libraryPinchZoomEnabled by remember { mutableStateOf(SteamModuleSettings.isSteamLibraryPinchZoomEnabled(context)) }
    var showGlassDialog by rememberSaveable { mutableStateOf(false) }
    var glassHoldDown by rememberSaveable { mutableStateOf(false) }
    SmallTitle(text = stringResource(R.string.module_general))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.module_steamdb),
            summary = stringResource(R.string.module_steamdb_summary),
            checked = steamDbEnabled,
            onCheckedChange = onSteamDbEnabledChange,
        )
        SteamGuardExportEntry()
        OverlayDropdownPreference(
            items = listOf(
                stringResource(R.string.module_steam_guard_code),
                stringResource(R.string.module_steam_scan_qr),
            ),
            selectedIndex = if (steamGuardDefaultPage == SteamGuardDefaultPage.GuardCode) 0 else 1,
            title = stringResource(R.string.module_steam_guard_default_page),
            summary = stringResource(R.string.module_steam_guard_default_page_summary),
            onSelectedIndexChange = { index ->
                val page = if (index == 0) SteamGuardDefaultPage.GuardCode else SteamGuardDefaultPage.ScanQrCode
                if (page != steamGuardDefaultPage) {
                    onSteamGuardDefaultPageChange(page)
                    Toast.makeText(context, restartMessage, Toast.LENGTH_SHORT).show()
                }
            },
        )
        SwitchPreference(
            title = stringResource(R.string.module_steam_guard_next_code),
            summary = stringResource(R.string.module_steam_guard_next_code_summary),
            checked = nextCodeEnabled,
            onCheckedChange = { enabled ->
                SteamModuleSettings.setSteamGuardNextCodeEnabled(context, enabled)
                nextCodeEnabled = enabled
                Toast.makeText(context, restartMessage, Toast.LENGTH_SHORT).show()
            },
        )
        SteamMaFileEntry()
        BasicComponent(
            title = stringResource(R.string.module_guest_browsing),
            summary = stringResource(R.string.module_guest_browsing_summary),
        )
    }
    SmallTitle(text = stringResource(R.string.module_appearance))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.module_glass),
            summary = if (glassSupported) stringResource(R.string.module_glass_settings_summary)
            else stringResource(R.string.module_glass_unsupported),
            holdDownState = glassHoldDown,
            onClick = {
                showGlassDialog = true
                glassHoldDown = true
            },
        )
        SwitchPreference(
            title = stringResource(R.string.module_library_pinch_zoom),
            summary = stringResource(R.string.module_library_pinch_zoom_summary),
            checked = libraryPinchZoomEnabled,
            onCheckedChange = { enabled ->
                SteamModuleSettings.setSteamLibraryPinchZoomEnabled(context, enabled)
                libraryPinchZoomEnabled = enabled
                Toast.makeText(context, restartMessage, Toast.LENGTH_SHORT).show()
            },
        )
    }
    OverlayDialog(
        show = showGlassDialog,
        title = stringResource(R.string.module_glass),
        largeScreen = true,
        onDismissRequest = { showGlassDialog = false },
        onDismissFinished = { glassHoldDown = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.module_settings_glass),
                    summary = stringResource(if (glassSupported) R.string.module_glass_summary else R.string.module_glass_unsupported),
                    checked = liquidGlassEnabled,
                    enabled = glassSupported,
                    insideMargin = PaddingValues(vertical = 12.dp),
                    onCheckedChange = onLiquidGlassEnabledChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.module_steam_glass),
                    summary = stringResource(if (glassSupported) R.string.module_steam_glass_summary else R.string.module_glass_unsupported),
                    checked = steamGlassEnabled && glassSupported,
                    enabled = glassSupported,
                    insideMargin = PaddingValues(vertical = 12.dp),
                    onCheckedChange = {
                        SteamModuleSettings.setSteamBottomGlassEnabled(context, it)
                        steamGlassEnabled = it
                    },
                )
            }
            TextButton(
                text = stringResource(R.string.module_close),
                onClick = { showGlassDialog = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    SteamManagementCard()
}

@Composable
private fun PlaceholderPage(title: String, summary: String) {
    SmallTitle(text = title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        BasicComponent(title = stringResource(R.string.module_coming_soon), summary = summary)
    }
}

@Composable
private fun SettingsPage(onLanguageChange: (ModuleLanguage) -> Unit) {
    SmallTitle(text = stringResource(R.string.module_settings))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SteamWorkingModePreference()
        OverlayDropdownPreference(
            title = stringResource(R.string.module_language),
            items = listOf(
                stringResource(R.string.module_language_system),
                stringResource(R.string.module_language_english),
                stringResource(R.string.module_language_chinese),
            ),
            selectedIndex = ModuleLocalization.language.ordinal,
            onSelectedIndexChange = { onLanguageChange(ModuleLanguage.entries[it]) },
        )
        BasicComponent(
            title = stringResource(R.string.module_coming_soon),
            summary = stringResource(R.string.module_settings_summary),
        )
    }
}
