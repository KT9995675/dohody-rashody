package ru.dohody.rashody

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Snapshot(
    val balance: Double,
    val transactions: List<Tx>,
    val categories: List<Category>,
    val notes: List<Note>,
    val loadedAtMs: Long = System.currentTimeMillis()
)

/**
 * In-memory cache of sheet data. Reads from cache; mutations apply locally
 * and enqueue GAS writes in the background.
 */
class Repository private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val writeQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile
    private var snap: Snapshot? = null

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    init {
        scope.launch {
            for (job in writeQueue) {
                try {
                    job()
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    val msg = e.userMessage()
                    if (msg != null) {
                        _errors.emit(msg)
                        // Re-sync so UI matches server after a failed write.
                        try {
                            refreshLocked(force = true)
                        } catch (ex: Exception) {
                            ex.rethrowIfCancellation()
                        }
                    }
                }
            }
        }
    }

    fun isConfigured(): Boolean = prefs.isConfigured()

    fun client(): GasClient = GasClient(prefs)

    fun snapshotOrNull(): Snapshot? = snap

    fun categories(): List<Category> = snap?.categories.orEmpty()

    fun notes(includeDone: Boolean): List<Note> {
        val all = snap?.notes.orEmpty()
        val filtered = if (includeDone) all else all.filter { !it.done }
        return filtered.sortedWith(noteComparator)
    }

    fun dashboard(from: String, to: String): Dashboard {
        val s = snap ?: return Dashboard(0.0, 0.0, 0.0, emptyList(), emptyList())
        val filtered = s.transactions.filter { tx -> inRange(tx.date, from, to) }
            .sortedByDescending { it.date.orEmpty() }
        var periodIncome = 0.0
        var periodExpense = 0.0
        filtered.forEach { tx ->
            if (tx.type == "income") periodIncome += tx.amount
            else periodExpense += tx.amount
        }
        return Dashboard(
            balance = s.balance,
            periodIncome = periodIncome,
            periodExpense = periodExpense,
            transactions = filtered,
            categories = s.categories
        )
    }

    suspend fun ensureLoaded(force: Boolean = false): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock { refreshLocked(force) }
    }

    private suspend fun refreshLocked(force: Boolean): Snapshot {
        val current = snap
        if (!force && current != null &&
            System.currentTimeMillis() - current.loadedAtMs < STALE_MS
        ) {
            return current
        }
        if (!prefs.isConfigured()) {
            val empty = Snapshot(0.0, emptyList(), emptyList(), emptyList())
            snap = empty
            return empty
        }
        val boot = GasClient(prefs).bootstrap()
        snap = boot
        return boot
    }

    fun enqueue(block: suspend () -> Unit) {
        writeQueue.trySend(block)
    }

    fun patchNote(
        note: Note,
        done: Boolean = note.done,
        dueDate: String = note.dueDate,
        dueTime: String = note.dueTime,
        text: String = note.text
    ) {
        val draft = NoteDraft(
            text = text,
            dueDate = dueDate,
            dueTime = dueTime,
            done = done,
            rawText = note.rawText
        )
        applyLocal { s ->
            s.copy(
                notes = s.notes.map {
                    if (it.id == note.id) {
                        it.copy(
                            text = text,
                            dueDate = dueDate,
                            dueTime = dueTime,
                            done = done
                        )
                    } else it
                }
            )
        }
        enqueue { GasClient(prefs).updateNote(note.id, draft) }
    }

    fun createNote(draft: NoteDraft, source: String, tempId: String = "local-${System.currentTimeMillis()}") {
        val local = Note(
            id = tempId,
            text = draft.text,
            dueDate = draft.dueDate,
            dueTime = draft.dueTime,
            done = draft.done,
            rawText = draft.rawText
        )
        applyLocal { s -> s.copy(notes = s.notes + local) }
        enqueue {
            val json = GasClient(prefs).createNote(draft, source)
            val created = JsonMap.note(json.optJSONObject("note"))
            if (created != null) {
                applyLocal { s ->
                    s.copy(notes = s.notes.map { if (it.id == tempId) created else it })
                }
            } else {
                mutex.withLock { refreshLocked(true) }
            }
        }
    }

    fun deleteNote(id: String) {
        applyLocal { s -> s.copy(notes = s.notes.filter { it.id != id }) }
        enqueue { GasClient(prefs).deleteNote(id) }
    }

    fun createTx(draft: Draft, confirmNewCategory: Boolean, source: String) {
        val tempId = "local-tx-${System.currentTimeMillis()}"
        val local = Tx(
            id = tempId,
            date = draft.date ?: java.time.LocalDate.now().toString(),
            type = draft.type,
            amount = draft.amount ?: 0.0,
            category = draft.category,
            comment = draft.comment,
            rawText = draft.rawText
        )
        applyLocal { s ->
            var cats = s.categories
            if (confirmNewCategory && !draft.category.isNullOrBlank() &&
                cats.none { it.name.equals(draft.category, true) }
            ) {
                cats = cats + Category(id = "local-cat", name = draft.category)
            }
            s.copy(
                transactions = listOf(local) + s.transactions,
                categories = cats.sortedBy { it.name.lowercase() },
                balance = recomputeBalance(listOf(local) + s.transactions)
            )
        }
        enqueue {
            val json = GasClient(prefs).create(draft, confirmNewCategory, source)
            val created = JsonMap.tx(json.optJSONObject("transaction"))
            applyLocal { s ->
                val txs = if (created != null) {
                    s.transactions.map { if (it.id == tempId) created else it }
                } else s.transactions
                s.copy(
                    transactions = txs,
                    balance = recomputeBalance(txs),
                    categories = mergeCategory(s.categories, draft)
                )
            }
            if (created == null) mutex.withLock { refreshLocked(true) }
        }
    }

    fun updateTx(id: String, draft: Draft, confirmNewCategory: Boolean) {
        applyLocal { s ->
            val txs = s.transactions.map {
                if (it.id == id) {
                    it.copy(
                        type = draft.type,
                        amount = draft.amount ?: it.amount,
                        category = draft.category,
                        comment = draft.comment,
                        date = draft.date ?: it.date
                    )
                } else it
            }
            s.copy(
                transactions = txs,
                balance = recomputeBalance(txs),
                categories = mergeCategory(s.categories, draft, confirmNewCategory)
            )
        }
        enqueue { GasClient(prefs).update(id, draft, confirmNewCategory) }
    }

    fun deleteTx(id: String) {
        applyLocal { s ->
            val txs = s.transactions.filter { it.id != id }
            s.copy(transactions = txs, balance = recomputeBalance(txs))
        }
        enqueue { GasClient(prefs).delete(id) }
    }

    fun addCategory(name: String) {
        applyLocal { s ->
            if (s.categories.any { it.name.equals(name, true) }) s
            else s.copy(
                categories = (s.categories + Category("local-${System.currentTimeMillis()}", name))
                    .sortedBy { it.name.lowercase() }
            )
        }
        enqueue {
            GasClient(prefs).addCategory(name)
            mutex.withLock { refreshLocked(true) }
        }
    }

    fun renameCategory(id: String, name: String) {
        val old = snap?.categories?.find { it.id == id }?.name
        applyLocal { s ->
            val cats = s.categories.map { if (it.id == id) it.copy(name = name) else it }
            val txs = if (old == null) s.transactions else s.transactions.map {
                if (it.category.equals(old, true)) it.copy(category = name) else it
            }
            s.copy(categories = cats.sortedBy { it.name.lowercase() }, transactions = txs)
        }
        enqueue {
            GasClient(prefs).renameCategory(id, name)
            mutex.withLock { refreshLocked(true) }
        }
    }

    fun deleteCategory(id: String, replacement: String?) {
        val old = snap?.categories?.find { it.id == id }?.name
        applyLocal { s ->
            val cats = s.categories.filter { it.id != id }
            val txs = if (old == null || replacement.isNullOrBlank()) s.transactions
            else s.transactions.map {
                if (it.category.equals(old, true)) it.copy(category = replacement) else it
            }
            s.copy(categories = cats, transactions = txs)
        }
        enqueue {
            GasClient(prefs).deleteCategory(id, replacement)
            mutex.withLock { refreshLocked(true) }
        }
    }

    private fun applyLocal(transform: (Snapshot) -> Snapshot) {
        val cur = snap ?: Snapshot(0.0, emptyList(), emptyList(), emptyList())
        snap = transform(cur)
    }

    private fun mergeCategory(
        cats: List<Category>,
        draft: Draft,
        confirmNew: Boolean = draft.categoryIsNew
    ): List<Category> {
        val name = draft.category?.trim().orEmpty()
        if (!confirmNew || name.isBlank()) return cats
        if (cats.any { it.name.equals(name, true) }) return cats
        return (cats + Category("local-${System.currentTimeMillis()}", name))
            .sortedBy { it.name.lowercase() }
    }

    fun clear() {
        snap = null
    }

    companion object {
        private const val STALE_MS = 60_000L

        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository {
            return instance ?: synchronized(this) {
                instance ?: Repository(context).also { instance = it }
            }
        }

        fun invalidate() {
            instance?.clear()
        }

        val noteComparator = Comparator<Note> { a, b ->
            val aHas = a.dueDate.isNotBlank()
            val bHas = b.dueDate.isNotBlank()
            when {
                aHas && !bHas -> -1
                !aHas && bHas -> 1
                aHas && bHas -> {
                    val d = a.dueDate.compareTo(b.dueDate)
                    if (d != 0) d
                    else {
                        val at = a.dueTime.ifBlank { "99:99" }
                        val bt = b.dueTime.ifBlank { "99:99" }
                        at.compareTo(bt)
                    }
                }
                else -> 0
            }
        }

        fun inRange(dateIso: String?, from: String, to: String): Boolean {
            if (from.isBlank() && to.isBlank()) return true
            val day = extractYmd(dateIso) ?: return true
            if (from.isNotBlank() && day < from) return false
            if (to.isNotBlank() && day > to) return false
            return true
        }

        fun extractYmd(dateIso: String?): String? {
            if (dateIso.isNullOrBlank()) return null
            val s = dateIso.trim()
            if (s.length >= 10 && s[4] == '-' && s[7] == '-') return s.substring(0, 10)
            return try {
                OffsetDateTime.parse(s).toLocalDate().toString()
            } catch (_: Exception) {
                try {
                    LocalDate.parse(s.take(10), DateTimeFormatter.ISO_LOCAL_DATE).toString()
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun recomputeBalance(txs: List<Tx>): Double {
            var b = 0.0
            txs.forEach {
                if (it.type == "income") b += it.amount
                else if (it.type == "expense") b -= it.amount
            }
            return Math.round(b * 100.0) / 100.0
        }

        /** Dropdown adapter that always shows the full category list. */
        fun categoryAdapter(context: Context, names: List<String>): ArrayAdapter<String> {
            return object : ArrayAdapter<String>(
                context,
                android.R.layout.simple_dropdown_item_1line,
                names.toMutableList()
            ) {
                override fun getFilter(): Filter {
                    return object : Filter() {
                        override fun performFiltering(constraint: CharSequence?): FilterResults {
                            return FilterResults().apply {
                                values = names
                                count = names.size
                            }
                        }

                        @Suppress("UNCHECKED_CAST")
                        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                            clear()
                            addAll((results?.values as? List<String>) ?: names)
                            notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }
}
