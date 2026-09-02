package ru.dohody.rashody

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import ru.dohody.rashody.databinding.ItemTxBinding

class TxAdapter(
    private val onClick: (Tx) -> Unit
) : RecyclerView.Adapter<TxAdapter.VH>() {
    private val items = mutableListOf<Tx>()

    fun submit(list: List<Tx>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemTxBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(tx: Tx) {
            val sign = if (tx.type == "income") "+" else "−"
            val color = if (tx.type == "income") "#2F5D4A".toColorInt() else "#9B3A2F".toColorInt()
            b.txAmount.text = "$sign${DatePresets.money(tx.amount)} ₽"
            b.txAmount.setTextColor(color)
            b.txDate.text = DatePresets.displayDate(tx.date)
            val typeRu = if (tx.type == "income") "доход" else "расход"
            val cat = tx.category?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            val comment = tx.comment?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
            b.txMeta.text = "$typeRu$cat$comment"
            b.root.setOnClickListener { onClick(tx) }
        }
    }
}
