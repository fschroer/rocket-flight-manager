package com.steampigeon.flightmanager.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.steampigeon.flightmanager.R

// -----------------------------
//  Primary UI Font Families
// -----------------------------

val bodyFontFamily = FontFamily(
    Font(R.font.poppins_regular),
    Font(R.font.poppins_bold, FontWeight.Bold)
)

val displayFontFamily = FontFamily(
    Font(R.font.roboto_regular),
    Font(R.font.roboto_bold, FontWeight.Bold)
)

// -----------------------------
//  Telemetry Font Family
// -----------------------------

val monoFontFamily = FontFamily(
    Font(R.font.robotomono_regular),
    Font(R.font.robotomono_bold, FontWeight.Bold)
)

// -----------------------------
//  Material Baseline
// -----------------------------

val baseline = Typography()

// -----------------------------
//  App Typography (UI Only)
// -----------------------------

val AppTypography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = displayFontFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = displayFontFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = displayFontFamily),

    headlineLarge = baseline.headlineLarge.copy(fontFamily = displayFontFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = displayFontFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = displayFontFamily),

    titleLarge = baseline.titleLarge.copy(fontFamily = displayFontFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = displayFontFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = displayFontFamily),

    bodyLarge = baseline.bodyLarge.copy(fontFamily = bodyFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = bodyFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = bodyFontFamily),

    labelLarge = baseline.labelLarge.copy(fontFamily = bodyFontFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = bodyFontFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = bodyFontFamily),
)

// -----------------------------
//  Telemetry Text Style
// -----------------------------

val TelemetryTextStyle = TextStyle(
    fontFamily = monoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    letterSpacing = 0.sp,
    lineHeight = 30.sp
)
