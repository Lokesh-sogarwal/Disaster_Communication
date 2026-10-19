package com.example.disasterconnect.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.disasterconnect.ui.theme.DisasterTheme

// =========================================================
// DIALOG
// =========================================================
// A plain question with two clearly labelled choices.
// emergency = true makes the confirm button the red Emergency button and the icon red;
// use it only to confirm life-safety actions such as sending an SOS.

@Composable
fun DisasterDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "Cancel",
    emergency: Boolean = false,
    icon: ImageVector = if (emergency) Icons.Filled.Warning else Icons.Outlined.Info
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (emergency) DisasterTheme.colors.emergency else MaterialTheme.colorScheme.onSurface
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            if (emergency) {
                EmergencyButton(text = confirmText, onClick = onConfirm)
            } else {
                PrimaryButton(text = confirmText, onClick = onConfirm)
            }
        },
        dismissButton = {
            SecondaryButton(text = dismissText, onClick = onDismiss)
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}
