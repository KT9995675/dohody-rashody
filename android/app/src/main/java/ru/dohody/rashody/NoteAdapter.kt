package ru.dohody.rashody

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox

class NoteAdapter(
    private val onOpen: (Note) -> Unit,
    private val onToggleDone: (Note, Boolean) -> Unit,
    private val onPickDate: (Note) -> Unit,
    private val onPickTime: (Note) -> Unit
) : RecyclerView.Adapter<NoteAdapter.VH>() {
    private var items: List<Note> = emptyList()

    fun submit(list: List<Note>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_note, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onOpen, onToggleDone, onPickDate, onPickTime)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val doneCheck: MaterialCheckBox = itemView.findViewById(R.id.doneCheck)
        private val text: TextView = itemView.findViewById(R.id.noteText)
        private val dueDateButton: MaterialButton = itemView.findViewById(R.id.dueDateButton)
        private val dueTimeButton: MaterialButton = itemView.findViewById(R.id.dueTimeButton)

        fun bind(
            note: Note,
            onOpen: (Note) -> Unit,
            onToggleDone: (Note, Boolean) -> Unit,
            onPickDate: (Note) -> Unit,
            onPickTime: (Note) -> Unit
        ) {
            text.text = note.text
            if (note.done) {
                text.alpha = 0.55f
                text.paint.isStrikeThruText = true
            } else {
                text.alpha = 1f
                text.paint.isStrikeThruText = false
            }
            text.invalidate()

            doneCheck.setOnCheckedChangeListener(null)
            doneCheck.isChecked = note.done
            doneCheck.setOnCheckedChangeListener { _, checked ->
                if (checked != note.done) onToggleDone(note, checked)
            }

            dueDateButton.text =
                if (note.dueDate.isNotBlank()) DatePresets.displayDate(note.dueDate)
                else itemView.context.getString(R.string.due_date)
            dueTimeButton.text =
                if (note.dueTime.isNotBlank()) note.dueTime
                else itemView.context.getString(R.string.due_time)

            text.setOnClickListener { onOpen(note) }
            dueDateButton.setOnClickListener { onPickDate(note) }
            dueTimeButton.setOnClickListener { onPickTime(note) }
        }
    }
}
