package nz.kaimahi.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Shared title + single-text-field + confirm/cancel dialog. Replaces
 * the duplicate AddTodoDialog / RenameProjectDialog implementations.
 *
 * The confirm action is enabled when the trimmed text is non-blank
 * AND, if `requireChange` is true (rename UX), differs from the
 * initial value.
 */
@Composable
fun KaimahiTextDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String,
    cancelLabel: String = "Cancel",
    singleLine: Boolean = true,
    requireChange: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val trimmed = text.trim()
    val enabled = trimmed.isNotEmpty() && (!requireChange || trimmed != initial)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = singleLine,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (enabled) onConfirm(trimmed) },
                enabled = enabled,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}
