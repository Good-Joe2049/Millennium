package com.millennium.app.features.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Uses the same NavDisplay preset and effects as the Miuix example's Settings -> About. */
@Composable
internal fun SteamModuleSettingNavigation(
    menuSnapshot: Bitmap?,
    pageState: SteamModuleSettingState,
    animateEntrance: Boolean,
    closing: Boolean,
    steamDbEnabled: Boolean,
    onSteamDbEnabledChange: (Boolean) -> Unit,
    liquidGlassEnabled: Boolean,
    onLiquidGlassEnabledChange: (Boolean) -> Unit,
    onRequestClose: () -> Unit,
    onClosed: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val colors = remember(dark) { if (dark) darkColorScheme() else lightColorScheme() }
    val backStack = remember {
        if (animateEntrance) navBackStackOf(Menu)
        else navBackStackOf(Menu, Settings)
    }
    LaunchedEffect(closing) {
        if (closing) {
            backStack.remove(Settings)
        } else if (backStack.last() == Menu) {
            // Present the original menu first so the new entry is a push, not the initial route.
            withFrameNanos { }
            backStack.add(Settings)
        }
    }

    // NavDisplay handles Back while Settings is on the stack. Consume repeated Back presses
    // during the exit too, so the dialog stays alive until the native menu is fully revealed.
    BackHandler(enabled = backStack.size == 1, onBack = onRequestClose)
    NavDisplay(
        backStack = backStack,
        onBack = onRequestClose,
        transition = NavTransitions.MiuixDefault,
        effects = NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            // Match AppContent in the example. The covered page fades slightly; an opaque
            // backdrop prevents the real host underneath from showing through as a double image.
            backdropColor = colors.surface,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<Menu> {
            val scope = LocalNavTransitionScope.current
            LaunchedEffect(closing) {
                if (closing) {
                    // Observe completion instead of guessing an animation duration. Compose's
                    // animator scale (including zero) and interrupted transitions remain valid.
                    snapshotFlow { !scope.isRunning && scope.relativeDepth == 0f }.first { it }
                    onClosed()
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (menuSnapshot != null) {
                    Image(
                        bitmap = remember(menuSnapshot) { menuSnapshot.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        entry<Settings> {
            SteamModuleSettingScreen(
                state = pageState,
                steamDbEnabled = steamDbEnabled,
                onSteamDbEnabledChange = onSteamDbEnabledChange,
                liquidGlassEnabled = liquidGlassEnabled,
                onLiquidGlassEnabledChange = onLiquidGlassEnabledChange,
            )
        }
    }
}

private data object Menu : NavKey
private data object Settings : NavKey
