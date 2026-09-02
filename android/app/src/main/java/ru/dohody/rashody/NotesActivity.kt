package ru.dohody.rashody

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.launch
import ru.dohody.rashody.databinding.ActivityNotesBinding
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class NotesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNotesBinding
    private lateinit var repo: Repository
    private lateinit var adapter: NoteAdapter

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) renderFromCache()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = Repository.get(this)

        adapter = NoteAdapter(
            onOpen = { note ->
                editLauncher.launch(EditNoteActivity.intentEdit(this, note.id))
            },
            onToggleDone = { note, done ->
                repo.patchNote(note, done = done)
                renderFromCache()
            },
            onPickDate = { note -> pickDate(note) },
            onPickTime = { note -> pickTime(note) }
        )
        binding.notesList.layoutManager = LinearLayoutManager(this)
        binding.notesList.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else false
        }

        NavHelper.bind(this, binding.bottomNav, R.id.nav_notes)

        binding.showDoneCheck.setOnCheckedChangeListener { _, _ -> renderFromCache() }
        binding.swipeRefresh.setOnRefreshListener { load(force = true) }

        lifecycleScope.launch {
            repo.errors.collect { msg ->
                Toast.makeText(this@NotesActivity, msg, Toast.LENGTH_LONG).show()
                renderFromCache()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (repo.snapshotOrNull() != null) renderFromCache()
        load(force = false)
    }

    private fun load(force: Boolean) {
        if (!repo.isConfigured()) {
            binding.swipeRefresh.isRefreshing = false
            adapter.submit(emptyList())
            binding.listEmpty.visibility = View.VISIBLE
            binding.listEmpty.text = "Настройки → URL и token"
            return
        }
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                repo.ensureLoaded(force)
                renderFromCache()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                e.userMessage()?.let {
                    Toast.makeText(this@NotesActivity, it, Toast.LENGTH_LONG).show()
                }
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderFromCache() {
        val notes = repo.notes(binding.showDoneCheck.isChecked)
        adapter.submit(notes)
        binding.listEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        binding.listEmpty.text = getString(R.string.empty_notes)
    }

    private fun pickDate(note: Note) {
        val current = DatePresets.parseOrNull(note.dueDate) ?: java.time.LocalDate.now()
        val utcMillis = current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.due_date)
            .setSelection(utcMillis)
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val ymd = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
            repo.patchNote(note, dueDate = ymd)
            renderFromCache()
        }
        picker.show(supportFragmentManager, "noteDate_${note.id}")
    }

    private fun pickTime(note: Note) {
        val hm = note.dueTime.trim()
        val parsed = if (hm.matches(Regex("\\d{1,2}:\\d{2}"))) {
            val p = hm.split(":")
            LocalTime.of(p[0].toInt(), p[1].toInt())
        } else LocalTime.of(12, 0)
        TimePickerDialog(
            this,
            { _, h, m ->
                val value = DateTimeFormatter.ofPattern("HH:mm").format(LocalTime.of(h, m))
                repo.patchNote(note, dueTime = value)
                renderFromCache()
            },
            parsed.hour,
            parsed.minute,
            true
        ).show()
    }
}
