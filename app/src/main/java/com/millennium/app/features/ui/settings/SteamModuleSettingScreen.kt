package com.millennium.app.features.ui.settings

import androidx.compose.ui.res.stringResource
import com.millennium.app.core.ModuleLanguage
import com.millennium.app.core.ModuleLocalization
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.millennium.app.R
import com.millennium.app.features.steamguard.ui.SteamGuardExportEntry
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
) {
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
) {
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
    }
    SmallTitle(text = stringResource(R.string.module_appearance))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.module_glass),
            summary = if (glassSupported) stringResource(R.string.module_glass_summary)
            else stringResource(R.string.module_glass_unsupported),
            checked = liquidGlassEnabled,
            enabled = glassSupported,
            onCheckedChange = onLiquidGlassEnabledChange,
        )
    }
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
