package nz.kaimahi.app.ui.dynamic

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Drop-in host for the daily-todo screen — owns a DailyTodoViewModel,
 * routes its state into [DailyTodoScreen], and provides a simple
 * AlertDialog for "+ Add". The dialog is the v1 affordance for adding
 * an item; the agent's `screen_data_put` tool-call path will use the
 * same store directly.
 */
@Composable
fun DailyTodoHost(
    onDrawerOpen: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DailyTodoViewModel(appContext) as T
        }
    }
    val vm: DailyTodoViewModel = viewModel(factory = factory)
    val items by vm.items.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    DailyTodoScreen(
        dateLine = vm.dateLine(),
        summaryLine = vm.summaryLine(),
        items = items,
        builtNote = vm.builtCopy(),
        onDrawerOpen = onDrawerOpen,
        onOverflow = { showOverflow = true },
        onToggleDone = vm::toggleDone,
        onAdd = { showAdd = true },
        onEditSchema = { /* schema editor lands when the dynamic-screen tool wires */ },
    )

    if (showAdd) {
        AddTodoDialog(
            onDismiss = { showAdd = false },
            onConfirm = { text ->
                vm.add(text)
                showAdd = false
            },
        )
    }
    if (showOverflow) {
        AlertDialog(
            onDismissRequest = { showOverflow = false },
            title = { Text("Daily todo") },
            text = {
                Text(
                    "Schema editing arrives with the dynamic-screen tool wiring. " +
                        "For now you can clear everything below.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); showOverflow = false }) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverflow = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun AddTodoDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to daily todo") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("What needs doing?") },
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
