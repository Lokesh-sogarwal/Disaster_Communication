package com.example.disasterconnect.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================
// DISASTERCONNECT PALETTE
// =========================================================
// Solid colours only. Gradients are not part of this design system.

// Emergency actions only: SOS, critical alerts, errors.
val EmergencyRed = Color(0xFFBE1A1A)

// Secondary emphasis: selected states and important (non-SOS) actions.
val SecondaryRed = Color(0xFFD0311E)

// Warnings and caution states.
val WarningGold = Color(0xFFF7D87F)

// Soft backgrounds, information cards and highlighted sections.
val SoftCream = Color(0xFFF8EBAB)

// ---------------------------------------------------------
// Neutrals (chosen for WCAG AA contrast with the palette)
// ---------------------------------------------------------

val White = Color(0xFFFFFFFF)

val OffWhite = Color(0xFFFFFBF5)

// Main text on light backgrounds: 16.6:1 on OffWhite, 12.3:1 on WarningGold.
val Ink = Color(0xFF1F1B16)

// Standard (non-emergency) actions: 12.7:1 on OffWhite.
val Charcoal = Color(0xFF33302E)

// Secondary text: 7.1:1 on OffWhite, 6.1:1 on SoftCream.
val MutedInk = Color(0xFF5C5550)

val SurfaceVariantLight = Color(0xFFF3EDE4)

// Borders: 4.6:1 on OffWhite (non-text needs 3:1).
val OutlineLight = Color(0xFF7A716B)

val OutlineVariantLight = Color(0xFFD9D1C7)

// ---------------------------------------------------------
// Dark-theme neutrals
// ---------------------------------------------------------

val NightBackground = Color(0xFF1A1715)

val NightSurface = Color(0xFF24201D)

val NightSurfaceVariant = Color(0xFF2F2A27)

val NightContainer = Color(0xFF3A3431)

// Main text on dark backgrounds: 14.4:1 on NightBackground.
val DayInk = Color(0xFFECE6DF)

// Secondary text on dark: 8.3:1 on NightBackground.
val NightMutedInk = Color(0xFFB8AFA8)

val OutlineDark = Color(0xFF8F8680)

val OutlineVariantDark = Color(0xFF4A433F)
