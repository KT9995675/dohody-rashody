package ru.dohody.rashody

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ru.dohody.rashody.databinding.ActivityEditNoteBinding
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class EditNoteActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEditNoteBinding
    private lateinit var repo: Repository
    private var noteId: String = ""
    private var mode: String = MODE_EDIT
    private var rawText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_EDIT
        noteId = intent.getStringExtra(EXTRA_ID).orEmpty()
        rawText = intent.getStringExtra(EXTRA_RAW)

        if (mode == MODE_EDIT && noteId.isBlank()) {
            finish()
            return
        }

        repo = Repository.get(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title =
            if (mode == MODE_CREATE) getString(R.string.confirm_note) else getString(R.string.edit_note)
        binding.saveButton.text =
            if (mode == MODE_CREATE) getString(R.string.save_note) else getString(R.string.save)
        binding.deleteButton.visibility = if (mode == MODE_CREATE) View.GONE else View.VISIBLE

        binding.dueDateInput.setOnClickListener { pickDate() }
        binding.dueTimeInput.setOnClickListener { pickTime() }
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }

        if (mode == MODE_CREATE) loadForCreate()
        else {
            lifecycleScope.launch {
                try {
                    repo.ensureLoaded()
                    val note = repo.snapshotOrNull()?.notes?.find { it.id == noteId }
                    if (note == null) {
                        Toast.makeText(this@EditNoteActivity, "Заметка не найдена", Toast.LENGTH_LONG)
                            .show()
                        finish()
                        return@launch
                    }
                    bind(note)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    e.userMessage()?.let {
                        Toast.makeText(this@EditNoteActivity, it, Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }

    private fun loadForCreate() {
        binding.textInput.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        binding.dueDateInput.setText(intent.getStringExtra(EXTRA_DUE_DATE).orEmpty())
        binding.dueTimeInput.setText(intent.getStringExtra(EXTRA_DUE_TIME).orEmpty())
        binding.doneCheck.isChecked = false
        val raw = rawText?.takeIf { it.isNotBlank() }
        if (raw != null) {
            binding.rawHint.visibility = View.VISIBLE
            binding.rawHint.text = "«$raw»"
        }
    }

    private fun bind(note: Note) {
        binding.textInput.setText(note.text)
        binding.dueDateInput.setText(note.dueDate)
        binding.dueTimeInput.setText(note.dueTime)
        binding.doneCheck.isChecked = note.done
    }

    private fun pickDate() {
        val current = DatePresets.parseOrNull(binding.dueDateInput.text?.toString())
            ?: java.time.LocalDate.now()
        val utcMillis = current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.due_date)
            .setSelection(utcMillis)
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val ymd = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
            binding.dueDateInput.setText(ymd)
        }
        picker.show(supportFragmentManager, "dueDate")
    }

    private fun pickTime() {
        val hm = binding.dueTimeInput.text?.toString()?.trim().orEmpty()
        val parsed = if (hm.matches(Regex("\\d{1,2}:\\d{2}"))) {
            val p = hm.split(":")
            LocalTime.of(p[0].toInt(), p[1].toInt())
        } else LocalTime.of(12, 0)
        TimePickerDialog(
            this,
            { _, h, m ->
                binding.dueTimeInput.setText(
                    DateTimeFormatter.ofPattern("HH:mm").format(LocalTime.of(h, m))
                )
            },
            parsed.hour,
            parsed.minute,
            true
        ).show()
    }

    private fun readDraft(): NoteDraft? {
        val text = binding.textInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Укажите текст", Toast.LENGTH_SHORT).show()
            return null
        }
        return NoteDraft(
            text = text,
            dueDate = binding.dueDateInput.text?.toString()?.trim().orEmpty(),
            dueTime = binding.dueTimeInput.text?.toString()?.trim().orEmpty(),
            done = binding.doneCheck.isChecked,
            rawText = rawText
        )
    }

    private fun save() {
        val draft = readDraft() ?: return
        if (mode == MODE_CREATE) {
            val source = intent.getStringExtra(EXTRA_SOURCE) ?: "android_text"
            repo.createNote(draft, source)
        } else {
            val existing = repo.snapshotOrNull()?.notes?.find { it.id == noteId }
            if (existing != null) {
                repo.patchNote(
                    existing,
                    done = draft.done,
                    dueDate = draft.dueDate,
                    dueTime = draft.dueTime,
                    text = draft.text
                )
            } else {
                repo.enqueue {
                    repo.client().updateNote(noteId, draft)
                }
            }
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить заметку?")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteNote(noteId)
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "note_id"
        const val EXTRA_MODE = "mode"
        const val MODE_EDIT = "edit"
        const val MODE_CREATE = "create"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DUE_DATE = "due_date"
        const val EXTRA_DUE_TIME = "due_time"
        const val EXTRA_RAW = "raw"
        const val EXTRA_SOURCE = "source"

        fun intentEdit(context: Context, id: String): Intent =
            Intent(context, EditNoteActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_EDIT)
                .putExtra(EXTRA_ID, id)

        fun intentConfirm(context: Context, draft: NoteDraft, source: String): Intent =
            Intent(context, EditNoteActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_CREATE)
                .putExtra(EXTRA_TEXT, draft.text)
                .putExtra(EXTRA_DUE_DATE, draft.dueDate)
                .putExtra(EXTRA_DUE_TIME, draft.dueTime)
                .putExtra(EXTRA_RAW, draft.rawText)
                .putExtra(EXTRA_SOURCE, source)
    }
}
