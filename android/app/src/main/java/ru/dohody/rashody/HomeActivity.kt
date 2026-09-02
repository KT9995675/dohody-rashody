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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dohody.rashody.databinding.ActivityHomeBinding
import java.io.File

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: Prefs
    private lateinit var client: GasClient

    private enum class InputTarget { FINANCE, TODO }

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recording = false
    private var busy = false
    private var recordTarget: InputTarget? = null

    private val askMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording(recordTarget ?: InputTarget.FINANCE)
        else Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
    }

    private val editTxLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            startActivity(Intent(this, FinanceActivity::class.java))
            finish()
        }
    }

    private val editNoteLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            startActivity(Intent(this, NotesActivity::class.java))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        client = GasClient(prefs)

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else false
        }

        NavHelper.bind(this, binding.bottomNav, R.id.nav_home)

        binding.financeSend.setOnClickListener { sendFinanceText() }
        binding.financeDictate.setOnClickListener { onDictate(InputTarget.FINANCE) }
        binding.financeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFinanceText()
                true
            } else false
        }

        binding.todoSend.setOnClickListener { sendTodoText() }
        binding.todoDictate.setOnClickListener { onDictate(InputTarget.TODO) }
        binding.todoInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTodoText()
                true
            } else false
        }

        updateUi()

        if (prefs.isConfigured()) {
            lifecycleScope.launch {
                try {
                    Repository.get(this@HomeActivity).ensureLoaded()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun requireConfigured(): Boolean {
        if (prefs.isConfigured()) return true
        Toast.makeText(this, "Сначала URL и token в настройках", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SettingsActivity::class.java))
        return false
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

    private fun updateUi() {
        val rec = recording
        binding.financeDictate.setBackgroundColor(
            if (rec && recordTarget == InputTarget.FINANCE) "#9B3A2F".toColorInt()
            else "#2F5D4A".toColorInt()
        )
        binding.todoDictate.setBackgroundColor(
            if (rec && recordTarget == InputTarget.TODO) "#9B3A2F".toColorInt()
            else "#2F5D4A".toColorInt()
        )
        val enabled = !recording && !busy
        binding.financeInput.isEnabled = enabled
        binding.financeSend.isEnabled = enabled
        binding.financeDictate.isEnabled = !busy
        binding.todoInput.isEnabled = enabled
        binding.todoSend.isEnabled = enabled
        binding.todoDictate.isEnabled = !busy
    }

    private fun onDictate(target: InputTarget) {
        if (!requireConfigured() || busy) return
        recordTarget = target
        when {
            recording -> stopAndUpload()
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startRecording(target)
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording(target: InputTarget) {
        recordTarget = target
        try {
            val file = File(cacheDir, "note_${System.currentTimeMillis()}.m4a")
            recordFile = file
            val mr = MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Speech-sized AAC: smaller upload → faster GAS/Gemini round-trip.
            mr.setAudioEncodingBitRate(48000)
            mr.setAudioSamplingRate(16000)
            mr.setAudioChannels(1)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            recording = true
            setStatus(getString(R.string.recording))
            updateUi()
        } catch (e: Exception) {
            recording = false
            recorder?.release()
            recorder = null
            setStatus("Не удалось записать: ${e.message}")
            updateUi()
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
        val target = recordTarget ?: InputTarget.FINANCE
        if (file == null || !file.exists() || file.length() < 100) {
            setStatus("Пустая запись")
            updateUi()
            return
        }

        busy = true
        setStatus("Разбираю…")
        updateUi()

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                when (target) {
                    InputTarget.FINANCE -> {
                        val result = withContext(Dispatchers.IO) {
                            client.parseAudio(b64, "audio/mp4")
                        }
                        openFinanceConfirm(result, "android_voice")
                    }
                    InputTarget.TODO -> {
                        val result = withContext(Dispatchers.IO) {
                            client.parseNoteAudio(b64, "audio/mp4")
                        }
                        openTodoConfirm(result, "android_voice")
                    }
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                e.userMessage()?.let {
                    setStatus(it)
                    Toast.makeText(this@HomeActivity, it, Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = false
                file.delete()
                setStatus(null)
                updateUi()
            }
        }
    }

    private fun sendFinanceText() {
        if (!requireConfigured() || busy || recording) return
        val text = binding.financeInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Введите фразу", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setStatus("Разбираю…")
        updateUi()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.parseText(text) }
                binding.financeInput.setText("")
                openFinanceConfirm(result, "android_text")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                e.userMessage()?.let {
                    setStatus(it)
                    Toast.makeText(this@HomeActivity, it, Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = false
                setStatus(null)
                updateUi()
            }
        }
    }

    private fun sendTodoText() {
        if (!requireConfigured() || busy || recording) return
        val text = binding.todoInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Введите заметку", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setStatus("Разбираю…")
        updateUi()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.parseNoteText(text) }
                binding.todoInput.setText("")
                openTodoConfirm(result, "android_text")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                e.userMessage()?.let {
                    setStatus(it)
                    Toast.makeText(this@HomeActivity, it, Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = false
                setStatus(null)
                updateUi()
            }
        }
    }

    private fun openFinanceConfirm(result: ParseResult, source: String) {
        val draft = result.draft
        if (draft == null) {
            setStatus(result.error ?: "Не удалось разобрать")
            return
        }
        editTxLauncher.launch(EditTxActivity.intentConfirm(this, draft, source = source))
    }

    private fun openTodoConfirm(result: NoteParseResult, source: String) {
        val draft = result.draft
        if (draft == null) {
            setStatus(result.error ?: "Не удалось разобрать")
            return
        }
        editNoteLauncher.launch(EditNoteActivity.intentConfirm(this, draft, source))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
    }
}
