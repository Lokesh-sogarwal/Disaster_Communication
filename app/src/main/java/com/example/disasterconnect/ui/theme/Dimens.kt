package com.example.disasterconnect.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// =========================================================
// SPACING
// =========================================================
// 4dp grid. Mirrored in res/values/dimens.xml for View-based screens.

object Spacing {

    val xs = 4.dp

    val sm = 8.dp

    val md = 12.dp

    val lg = 16.dp

    val xl = 24.dp

    val xxl = 32.dp

    // Side margin for screen content.
    val screen = 20.dp
}

// =========================================================
// SIZES
// =========================================================

object Sizes {

    // Minimum touch target for anything tappable.
    val touchTarget = 48.dp

    // Rows in lists and menus.
    val listItemMinHeight = 56.dp

    // The SOS / emergency action is larger than every other button.
    val emergencyButtonMinHeight = 64.dp

    val icon = 24.dp

    val iconSmall = 20.dp

    val border = 1.dp

    val emphasisBorder = 2.dp
}

// =========================================================
// CORNER RADII
// =========================================================

object Radius {

    val small = 8.dp

    val medium = 12.dp

    val large = 16.dp

    val pill = 50.dp
}

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.small / 2),
    small = RoundedCornerShape(Radius.small),
    medium = RoundedCornerShape(Radius.medium),
    large = RoundedCornerShape(Radius.large),
    extraLarge = RoundedCornerShape(Radius.large + Radius.small)
)
