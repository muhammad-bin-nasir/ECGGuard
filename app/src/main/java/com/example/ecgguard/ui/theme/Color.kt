package com.example.ecgguard.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color.kt — Material 3 colour tokens for the ECGGuard theme.
 *
 * NOTE: These colours are used only by the ECGGuardTheme() wrapper in Theme.kt,
 * which is applied at the root of the app in MainActivity.setContent().
 * However, most of the app's actual UI colours are defined DIRECTLY in MainActivity.kt
 * as private val fields (e.g. uiBgTop, uiAccent, uiPanel) — those override the
 * Material theme colours for every composable in the app.
 *
 * WHEN WOULD YOU USE THESE?
 * ─────────────────────────
 * If you use standard Material 3 components (e.g. Button without explicit `colors`
 * param, TopAppBar, etc.), they read from the Material theme — which reads from here.
 * Any explicitly styled component in MainActivity ignores these.
 *
 * HOW TO CHANGE THE ACCENT COLOUR:
 * Change uiAccent in MainActivity.kt (line ~92). That affects the entire app visually.
 * These tokens below only matter for un-styled Material components.
 *
 * Colour naming convention:
 *   Purple80 / PurpleGrey80 / Pink80 = dark-theme colours (light on dark backgrounds)
 *   Purple40 / PurpleGrey40 / Pink40 = light-theme colours (dark on light backgrounds)
 *   The number (80/40) refers to tone on the Material 3 tonal palette scale.
 */

// ── Dark theme colour tokens ───────────────────────────────────────────────────
// Used when the device is in Dark Mode and dynamicColor is false on Android < 12
val Purple80     = Color(0xFFD0BCFF)   // primary accent (light purple)
val PurpleGrey80 = Color(0xFFCCC2DC)   // secondary (desaturated purple)
val Pink80       = Color(0xFFEFB8C8)   // tertiary (soft pink)

// ── Light theme colour tokens ──────────────────────────────────────────────────
// Used when the device is in Light Mode and dynamicColor is false on Android < 12
val Purple40     = Color(0xFF6650a4)   // primary accent (deep purple)
val PurpleGrey40 = Color(0xFF625b71)   // secondary (muted purple-grey)
val Pink40       = Color(0xFF7D5260)   // tertiary (dark rose)
