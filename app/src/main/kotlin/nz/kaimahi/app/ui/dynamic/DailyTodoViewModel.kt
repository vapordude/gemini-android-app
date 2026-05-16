package nz.kaimahi.app.ui.dynamic

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nz.kaimahi.bridge.storage.TodoStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Holds the daily-todo screen state. Reads + writes go through TodoStore.
 * On first construction the store gets a `builtAt` stamp so the "Built
 * by Kaimahi on N May" footer card can render with a real date.
 */
class DailyTodoViewModel(context: Context) : ViewModel() {

    private val store = TodoStore(context.applicationContext)

    private val _items = MutableStateFlow<List<TodoItem>>(emptyList())
    val items: StateFlow<List<TodoItem>> = _items.asStateFlow()

    private val _builtAt = MutableStateFlow<Long?>(null)
    val builtAt: StateFlow<Long?> = _builtAt.asStateFlow()

    init {
        val now = System.currentTimeMillis()
        // Materialise the screen the first time it's opened so the
        // footer has a date to show. The agent (when wired) will reset
        // this through a `create_screen` tool call.
        _builtAt.value = store.ensureBuilt().takeIf { it > 0 } ?: now
        refresh()
    }

    private fun refresh() {
        val snap = store.load()
        _items.value = snap.items.toUi()
        _builtAt.value = snap.builtAt
    }

    fun add(text: String, meta: String = "added by Kaimahi") {
        if (text.isBlank()) return
        store.add(text, meta)
        refresh()
    }

    fun toggleDone(item: TodoItem) {
        store.toggleDone(item.id)
        refresh()
    }

    fun delete(item: TodoItem) {
        store.delete(item.id)
        refresh()
    }

    fun clearAll() {
        store.clear()
        refresh()
    }

    /** Returns a date line for the header (e.g. "Saturday, 16 May"). */
    fun dateLine(): String =
        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())

    /**
     * Returns the summary line for the header strip, e.g.
     * "3 open · 2 done · 1 overdue". Items with `overdue = true` are
     * counted as open AND overdue.
     */
    fun summaryLine(): String {
        val list = _items.value
        val total = list.size
        val done = list.count { it.done }
        val open = total - done
        val overdue = list.count { it.overdue && !it.done }
        return buildList {
            if (open > 0) add("$open open")
            if (done > 0) add("$done done")
            if (overdue > 0) add("$overdue overdue")
            if (isEmpty()) add("nothing yet")
        }.joinToString(" · ")
    }

    /** Returns the footer card copy ("Built by Kaimahi on 16 May."). */
    fun builtCopy(): String? {
        val at = _builtAt.value ?: return null
        val day = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
        return "Built by Kaimahi on $day."
    }

    private fun List<TodoStore.Item>.toUi(): List<TodoItem> = map { it ->
        TodoItem(
            id = it.id,
            text = it.text,
            meta = it.meta,
            done = it.done,
            overdue = it.overdue,
        )
    }
}
