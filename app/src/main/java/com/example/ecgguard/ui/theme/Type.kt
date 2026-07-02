package com.example.ecgguard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type.kt — Material 3 typography scale for ECGGuard.
 *
 * Defines the default text styles used by Material 3 components (e.g. Button label,
 * TextField, etc.) when no explicit TextStyle is provided.
 *
 * NOTE: Most text in the app uses explicit fontSize/fontWeight/color parameters
 * in the composables directly (e.g. Text("...", fontSize = 14.sp)), so this
 * typography scale only affects unstyled Material components.
 *
 * HOW TO ADD A CUSTOM FONT:
 * 1. Put the .ttf font file in app/src/main/res/font/ (e.g. inter_regular.ttf)
 * 2. Create a FontFamily:
 *      val InterFamily = FontFamily(Font(R.font.inter_regular, FontWeight.Normal))
 * 3. Replace FontFamily.Default with InterFamily below
 * 4. Rebuild the project
 *
 * HOW TO CHANGE BASE FONT SIZE:
 * Change 16.sp in bodyLarge below. All other Material text styles scale relative
 * to the device's system font size setting, so this is just the base.
 *
 * OTHER TEXT SLOTS YOU CAN OVERRIDE (uncomment and add to Typography() call):
 *   titleLarge   = ... (used by TopAppBar, AlertDialog title)
 *   labelSmall   = ... (used by NavigationBarItem labels, Chip labels)
 *   headlineMedium = ... (used by large card headers)
 */
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily    = FontFamily.Default,   // system default font (Roboto on most Android)
        fontWeight    = FontWeight.Normal,
        fontSize      = 16.sp,               // base body text size — HOW TO CHANGE: increase for accessibility
        lineHeight    = 24.sp,               // vertical spacing between lines
        letterSpacing = 0.5.sp              // slight character spacing for readability
    )
)
