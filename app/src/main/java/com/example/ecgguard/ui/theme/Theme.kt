package com.example.ecgguard.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Theme.kt — Material 3 theme wrapper for ECGGuard.
 *
 * NOTE: ECGGuardTheme is defined here but NOT used as the top-level theme in MainActivity.
 * MainActivity.setContent uses MaterialTheme{} directly, not ECGGuardTheme{}.
 * This file is the Android Studio template default — kept in case you want to
 * switch to it, but the actual visual styling lives in MainActivity's color constants.
 *
 * HOW TO SWITCH TO USING THIS THEME:
 * In MainActivity.onCreate(), change:
 *   setContent { MaterialTheme { Surface(...) { MainApp() } } }
 * to:
 *   setContent { ECGGuardTheme { Surface(...) { MainApp() } } }
 * Then the colour tokens in Color.kt will drive all un-styled Material components.
 *
 * DYNAMIC COLOUR (Android 12+):
 * When dynamicColor=true and the device runs Android 12+, Material 3 uses
 * the user's wallpaper colours to generate the theme automatically.
 * The app's hardcoded plum/brown palette in MainActivity will still override this
 * for explicitly styled components, but un-styled components (dialogs, etc.) will
 * adopt the system palette.
 * HOW TO DISABLE: set dynamicColor=false to always use the Purple/Pink palette above.
 */

// ── Static dark colour scheme (Android < 12, or dynamicColor=false) ──────────
// Uses the 80-tone tokens from Color.kt (light colours for dark backgrounds)
private val DarkColorScheme = darkColorScheme(
    primary   = Purple80,
    secondary = PurpleGrey80,
    tertiary  = Pink80
)

// ── Static light colour scheme (Android < 12, or dynamicColor=false) ─────────
// Uses the 40-tone tokens from Color.kt (dark colours for light backgrounds)
private val LightColorScheme = lightColorScheme(
    primary   = Purple40,
    secondary = PurpleGrey40,
    tertiary  = Pink40
    // Other Material 3 slots you can override here:
    // background = Color(0xFFFFFBFE),
    // surface    = Color(0xFFFFFBFE),
    // onPrimary  = Color.White,
)

/**
 * Root Compose theme wrapper — wrap your entire UI in this to apply the theme.
 *
 * @param darkTheme      Whether to use the dark colour scheme.
 *                       Default: follows the device's system dark mode setting.
 *                       HOW TO FORCE DARK: pass darkTheme=true always.
 * @param dynamicColor   Whether to use Android 12+ dynamic (wallpaper-based) colours.
 *                       Default: true (use system colours if available).
 *                       HOW TO DISABLE: pass dynamicColor=false to always use Purple/Pink.
 * @param content        The composable content to theme.
 */
@Composable
fun ECGGuardTheme(
    darkTheme:    Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Priority 1: Dynamic colour on Android 12+ (S = Android 12 = API 31)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Priority 2: Static dark scheme
        darkTheme -> DarkColorScheme
        // Priority 3: Static light scheme
        else      -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,   // defined in Type.kt
        content     = content
    )
}
