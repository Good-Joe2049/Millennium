package com.millennium.app.features.ui.settings

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
    steamDbEnabled: Boolean,
    onSteamDbEnabledChange: (Boolean) -> Unit,
    liquidGlassEnabled: Boolean,
    onLiquidGlassEnabledChange: (Boolean) -> Unit,
) {
    val tabs = remember {
        listOf(
            "常规" to R.drawable.ic_module_nav_general,
            "主题" to R.drawable.ic_module_nav_themes,
            "日志" to R.drawable.ic_module_nav_logs,
            "设置" to R.drawable.ic_module_nav_settings,
        )
    }

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
                            title = "主题",
                            summary = "Steam 主题功能将在这里提供。",
                        )
                        2 -> PlaceholderPage(
                            title = "日志",
                            summary = "日志查看功能将在这里提供。",
                        )
                        else -> SettingsPage()
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
    SmallTitle(text = "常规")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SwitchPreference(
            title = "SteamDB 悬浮面板",
            summary = "在游戏商店页面显示 Millennium 悬浮球和数据面板",
            checked = steamDbEnabled,
            onCheckedChange = onSteamDbEnabledChange,
        )
        SteamGuardExportEntry()
    }
    SmallTitle(text = "界面美化")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SwitchPreference(
            title = "液态玻璃底栏",
            summary = if (glassSupported) "仅应用于模块设置页面的底栏"
            else "需要 Android 13 或更高版本",
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
        BasicComponent(title = "敬请期待", summary = summary)
    }
}

@Composable
private fun SettingsPage() {
    SmallTitle(text = "设置")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        BasicComponent(
            title = "敬请期待",
            summary = "模块设置页面语言、配置备份与恢复，以及深色、浅色、莫奈主题将在这里提供。",
        )
    }
}
