package com.example.disasterconnect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.disasterconnect.ui.theme.DisasterTheme
import com.example.disasterconnect.ui.theme.Sizes
import com.example.disasterconnect.ui.theme.Spacing

// =========================================================
// CARDS
// =========================================================
// Flat, bordered cards (no heavy shadows). The tone picks a solid background:
//   DEFAULT   – plain surface
//   INFO      – soft cream, for helpful information
//   WARNING   – warning gold, for caution
//   EMERGENCY – emergency red, for real emergencies only

enum class CardTone {
    DEFAULT,
    INFO,
    WARNING,
    EMERGENCY
}

@Composable
fun DisasterCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.DEFAULT,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {

    val (container, contentColor) = tone.colors()

    val border =
        BorderStroke(
            if (tone == CardTone.DEFAULT) Sizes.border else Sizes.emphasisBorder,
            if (tone == CardTone.DEFAULT) MaterialTheme.colorScheme.outlineVariant else contentColor.copy(alpha = 0.24f)
        )

    val colors =
        CardDefaults.cardColors(
            containerColor = container,
            contentColor = contentColor
        )

    val elevation =
        CardDefaults.cardElevation(defaultElevation = 0.dp)

    val padded: @Composable ColumnScope.() -> Unit = {

        Column(
            modifier = Modifier.padding(Spacing.lg),
            content = content
        )
    }

    if (onClick != null) {

        Card(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            elevation = elevation,
            border = border,
            content = padded
        )

    } else {

        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            elevation = elevation,
            border = border,
            content = padded
        )
    }
}

/** A short message with an icon and title, e.g. "Bluetooth is off". */
@Composable
fun NoticeCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.INFO,
    icon: ImageVector = tone.defaultIcon()
) {

    DisasterCard(
        modifier = modifier.semantics(mergeDescendants = true) {},
        tone = tone
    ) {

        Row(
            verticalAlignment = Alignment.Top
        ) {

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Sizes.icon)
            )

            Spacer(modifier = Modifier.width(Spacing.md))

            Column {

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(Spacing.xs))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ---------------------------------------------------------
// Tone helpers
// ---------------------------------------------------------

@Composable
private fun CardTone.colors(): Pair<Color, Color> {

    val semantic = DisasterTheme.colors

    return when (this) {
        CardTone.DEFAULT -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
        CardTone.INFO -> semantic.info to semantic.onInfo
        CardTone.WARNING -> semantic.warning to semantic.onWarning
        CardTone.EMERGENCY -> semantic.emergency to semantic.onEmergency
    }
}

private fun CardTone.defaultIcon(): ImageVector =
    when (this) {
        CardTone.DEFAULT, CardTone.INFO -> Icons.Outlined.Info
        CardTone.WARNING -> Icons.Outlined.Warning
        CardTone.EMERGENCY -> Icons.Filled.Warning
    }
