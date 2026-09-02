package ru.dohody.rashody

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dohody.rashody.databinding.ActivityMainBinding
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var client: GasClient
    private lateinit var txAdapter: TxAdapter

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recording = false
    private var busy = false
    private var preset = "all"
    private var customFrom = ""
    private var customTo = ""

    private val askMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
    }

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) loadDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        client = GasClient(prefs)

        txAdapter = TxAdapter { tx ->
            editLauncher.launch(EditTxActivity.intentEdit(this, tx.id))
        }
        binding.txList.layoutManager = LinearLayoutManager(this)
        binding.txList.adapter = txAdapter

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else false
        }

        binding.swipeRefresh.setOnRefreshListener { loadDashboard() }
        binding.chipAll.setOnClickListener { setPreset("all") }
        binding.chipToday.setOnClickListener { setPreset("today") }
        binding.chipWeek.setOnClickListener { setPreset("week") }
        binding.chipMonth.setOnClickListener { setPreset("month") }
        binding.chipRange.setOnClickListener { setPreset("range") }
        binding.fromDateButton.setOnClickListener { pickDate(true) }
        binding.toDateButton.setOnClickListener { pickDate(false) }
        binding.applyRangeButton.setOnClickListener {
            if (customFrom.isBlank() || customTo.isBlank()) {
                Toast.makeText(this, "Укажите обе даты", Toast.LENGTH_SHORT).show()
            } else if (customFrom > customTo) {
                Toast.makeText(this, "«С» не может быть позже «По»", Toast.LENGTH_SHORT).show()
            } else loadDashboard()
        }

        binding.sendButton.setOnClickListener { sendTextNote() }
        binding.dictateButton.setOnClickListener { onDictateClick() }
        binding.noteInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextNote()
                true
            } else false
        }

        updateDictateUi()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isConfigured()) loadDashboard()
        else {
            binding.balanceText.text = "—"
            binding.periodText.text = "Настройки → URL и token"
            txAdapter.submit(emptyList())
        }
    }

    private fun requireConfigured(): Boolean {
        if (prefs.isConfigured()) return true
        Toast.makeText(this, "Сначала URL и token в настройках", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SettingsActivity::class.java))
        return false
    }

    private fun setPreset(name: String) {
        preset = name
        binding.rangePanel.visibility = if (name == "range") View.VISIBLE else View.GONE
        if (name == "range") {
            if (customFrom.isBlank()) customFrom = DatePresets.range("month").first
            if (customTo.isBlank()) customTo = DatePresets.today()
            updateRangeButtons()
            return
        }
        loadDashboard()
    }

    private fun updateRangeButtons() {
        binding.fromDateButton.text = "${getString(R.string.date_from)} ${customFrom.ifBlank { "…" }}"
        binding.toDateButton.text = "${getString(R.string.date_to)} ${customTo.ifBlank { "…" }}"
    }

    private fun pickDate(isFrom: Boolean) {
        val initial = DatePresets.parseOrNull(if (isFrom) customFrom else customTo)
            ?: java.time.LocalDate.now()
        val utcMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(if (isFrom) R.string.date_from else R.string.date_to)
            .setSelection(utcMillis)
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val ymd = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
            if (isFrom) customFrom = ymd else customTo = ymd
            updateRangeButtons()
        }
        picker.show(supportFragmentManager, if (isFrom) "from" else "to")
    }

    private fun loadDashboard() {
        if (!prefs.isConfigured()) {
            binding.swipeRefresh.isRefreshing = false
            return
        }
        binding.swipeRefresh.isRefreshing = true
        val (from, to) =
            if (preset == "range") customFrom to customTo else DatePresets.range(preset)
        lifecycleScope.launch {
            try {
                val dash = withContext(Dispatchers.IO) { client.dashboard(from, to) }
                binding.balanceText.text = "${DatePresets.money(dash.balance)} ₽"
                binding.periodText.text =
                    "+${DatePresets.money(dash.periodIncome)} / −${DatePresets.money(dash.periodExpense)}"
                txAdapter.submit(dash.transactions)
                binding.listEmpty.visibility =
                    if (dash.transactions.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                binding.periodText.text = e.message
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun setStatus(text: String?) {
        if (text.isNullOrBlank()) {
            binding.status.visibility = View.GONE
            binding.status.text = ""
        } else {
            binding.status.visibility = View.VISIBLE
            binding.status.text = text
        }
    }

    private fun onDictateClick() {
        if (!requireConfigured() || busy) return
        when {
            recording -> stopAndUpload()
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startRecording()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateDictateUi() {
        if (recording) {
            binding.dictateButton.setBackgroundColor("#9B3A2F".toColorInt())
            binding.dictateButton.contentDescription = getString(R.string.stop_recording)
        } else {
            binding.dictateButton.setBackgroundColor("#2F5D4A".toColorInt())
            binding.dictateButton.contentDescription = getString(R.string.dictate)
        }
        binding.noteInput.isEnabled = !recording && !busy
        binding.sendButton.isEnabled = !recording && !busy
    }

    private fun startRecording() {
        try {
            val file = File(cacheDir, "note_${System.currentTimeMillis()}.m4a")
            recordFile = file
            val mr = MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            recording = true
            setStatus(getString(R.string.recording))
            updateDictateUi()
        } catch (e: Exception) {
            recording = false
            recorder?.release()
            recorder = null
            setStatus("Не удалось записать: ${e.message}")
            updateDictateUi()
        }
    }

    private fun stopAndUpload() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        recorder = null
        recording = false

        val file = recordFile
        if (file == null || !file.exists() || file.length() < 100) {
            setStatus("Пустая запись")
            updateDictateUi()
            return
        }

        busy = true
        setStatus("Разбираю…")
        updateDictateUi()

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val result = withContext(Dispatchers.IO) {
                    client.parseAudio(b64, "audio/mp4")
                }
                openConfirm(result, "android_voice")
            } catch (e: Exception) {
                setStatus(e.message)
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                file.delete()
                updateDictateUi()
            }
        }
    }

    private fun sendTextNote() {
        if (!requireConfigured() || busy || recording) return
        val text = binding.noteInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Введите заметку", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setStatus("Разбираю…")
        updateDictateUi()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.parseText(text) }
                binding.noteInput.setText("")
                openConfirm(result, "android_text")
            } catch (e: Exception) {
                setStatus(e.message)
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                updateDictateUi()
            }
        }
    }

    private fun openConfirm(result: ParseResult, source: String) {
        val draft = result.draft
        if (draft == null) {
            setStatus(result.error ?: "Не удалось разобрать")
            return
        }
        setStatus(null)
        editLauncher.launch(EditTxActivity.intentConfirm(this, draft, source = source))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
    }
}
