package com.example.disasterconnect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.disasterconnect.ui.theme.DisasterTheme
import com.example.disasterconnect.ui.theme.Sizes
import com.example.disasterconnect.ui.theme.Spacing

// =========================================================
// BUTTONS
// =========================================================
// Primary   – the main ordinary action on a screen (neutral, filled).
// Secondary – alternative actions (outlined).
// Tertiary  – low-emphasis actions such as Back (text only).
// Emergency – SOS and other life-safety actions only (red, large).
//
// Labels wrap instead of being cut off when the user enlarges text.

private val ButtonPadding =
    PaddingValues(
        horizontal = Spacing.xl,
        vertical = Spacing.md
    )

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {

    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Sizes.touchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = ButtonPadding
    ) {

        ButtonLabel(text = text, icon = icon)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Sizes.touchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border =
            BorderStroke(
                Sizes.border,
                if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
            ),
        contentPadding = ButtonPadding
    ) {

        ButtonLabel(text = text, icon = icon)
    }
}

@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {

    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Sizes.touchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding =
            PaddingValues(
                horizontal = Spacing.md,
                vertical = Spacing.sm
            )
    ) {

        ButtonLabel(text = text, icon = icon)
    }
}

/** For SOS and other life-safety actions only. Use at most one per screen. */
@Composable
fun EmergencyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {

    val colors = DisasterTheme.colors

    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Sizes.emergencyButtonMinHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = colors.emergency,
                contentColor = colors.onEmergency
            ),
        contentPadding = ButtonPadding
    ) {

        ButtonLabel(
            text = text,
            icon = icon,
            emphasized = true
        )
    }
}

@Composable
private fun ButtonLabel(
    text: String,
    icon: ImageVector?,
    emphasized: Boolean = false
) {

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {

        if (icon != null) {

            // Decorative: the text label already says what the button does.
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Sizes.iconSmall)
            )

            Spacer(modifier = Modifier.width(Spacing.sm))
        }

        Text(
            text = text,
            style =
                if (emphasized)
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                else
                    MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
    }
}
