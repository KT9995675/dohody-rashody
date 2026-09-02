package ru.dohody.rashody

data class Tx(
    val id: String,
    val date: String?,
    val type: String?,
    val amount: Double,
    val category: String?,
    val comment: String?,
    val rawText: String?
)

data class Category(
    val id: String,
    val name: String
)

data class Dashboard(
    val balance: Double,
    val periodIncome: Double,
    val periodExpense: Double,
    val transactions: List<Tx>,
    val categories: List<Category>
)

data class Note(
    val id: String,
    val text: String,
    val dueDate: String,
    val dueTime: String,
    val done: Boolean,
    val rawText: String?
)

data class NoteDraft(
    val text: String,
    val dueDate: String = "",
    val dueTime: String = "",
    val done: Boolean = false,
    val rawText: String? = null
)

data class NoteParseResult(
    val ok: Boolean,
    val error: String?,
    val draft: NoteDraft?
)

object JsonMap {
    fun tx(o: org.json.JSONObject?): Tx? {
        if (o == null) return null
        return Tx(
            id = o.optString("id"),
            date = o.optString("date").takeIf { it.isNotBlank() && it != "null" },
            type = o.optString("type").takeIf { it.isNotBlank() && it != "null" },
            amount = o.optDouble("amount", 0.0),
            category = o.optString("category").takeIf { it.isNotBlank() && it != "null" },
            comment = o.optString("comment").takeIf { it.isNotBlank() && it != "null" },
            rawText = o.optString("rawText").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun category(o: org.json.JSONObject?): Category? {
        if (o == null) return null
        return Category(id = o.optString("id"), name = o.optString("name"))
    }

    fun dashboard(o: org.json.JSONObject?): Dashboard? {
        if (o == null) return null
        val txs = mutableListOf<Tx>()
        val arr = o.optJSONArray("transactions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                tx(arr.optJSONObject(i))?.let { txs.add(it) }
            }
        }
        val cats = mutableListOf<Category>()
        val carr = o.optJSONArray("categories")
        if (carr != null) {
            for (i in 0 until carr.length()) {
                category(carr.optJSONObject(i))?.let { cats.add(it) }
            }
        }
        return Dashboard(
            balance = o.optDouble("balance", 0.0),
            periodIncome = o.optDouble("periodIncome", 0.0),
            periodExpense = o.optDouble("periodExpense", 0.0),
            transactions = txs,
            categories = cats
        )
    }

    fun note(o: org.json.JSONObject?): Note? {
        if (o == null) return null
        return Note(
            id = o.optString("id"),
            text = o.optString("text"),
            dueDate = o.optString("dueDate").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            dueTime = o.optString("dueTime").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            done = o.optBoolean("done", false),
            rawText = o.optString("rawText").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
