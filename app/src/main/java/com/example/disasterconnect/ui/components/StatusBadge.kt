package com.example.disasterconnect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
// STATUS INDICATORS
// =========================================================
// Every status has its own icon AND a word, so it never depends on colour alone.

enum class StatusType(
    val defaultLabel: String
) {
    CONNECTED("Connected"),
    DISCONNECTED("Not connected"),
    PENDING("Waiting to send"),
    DELIVERED("Delivered"),
    WARNING("Warning"),
    EMERGENCY("Emergency")
}

@Composable
fun StatusBadge(
    status: StatusType,
    modifier: Modifier = Modifier,
    label: String = status.defaultLabel
) {

    val style = status.style()

    Surface(
        modifier =
            modifier
                .heightIn(min = 32.dp)
                .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.small,
        color = style.container,
        contentColor = style.content,
        border = style.border
    ) {

        Row(
            modifier =
                Modifier.padding(
                    horizontal = Spacing.md,
                    vertical = Spacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = style.icon,
                contentDescription = null,
                modifier = Modifier.size(Sizes.iconSmall)
            )

            Spacer(modifier = Modifier.width(Spacing.sm))

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

// ---------------------------------------------------------
// Visual style per status
// ---------------------------------------------------------

private class StatusStyle(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
    val border: BorderStroke? = null
)

@Composable
private fun StatusType.style(): StatusStyle {

    val scheme = MaterialTheme.colorScheme

    val semantic = DisasterTheme.colors

    return when (this) {
        StatusType.CONNECTED ->
            StatusStyle(scheme.surfaceVariant, scheme.onSurface, Icons.Filled.CheckCircle)

        StatusType.DELIVERED ->
            StatusStyle(scheme.surfaceVariant, scheme.onSurface, Icons.Filled.Done)

        StatusType.DISCONNECTED ->
            StatusStyle(Color.Transparent, scheme.onSurfaceVariant, Icons.Outlined.Close, BorderStroke(Sizes.border, scheme.outline))

        StatusType.PENDING ->
            StatusStyle(semantic.info, semantic.onInfo, Icons.Outlined.Refresh)

        StatusType.WARNING ->
            StatusStyle(semantic.warning, semantic.onWarning, Icons.Outlined.Warning)

        StatusType.EMERGENCY ->
            StatusStyle(semantic.emergency, semantic.onEmergency, Icons.Filled.Warning)
    }
}
