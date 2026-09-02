package ru.dohody.rashody

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
import ru.dohody.rashody.databinding.ActivityFinanceBinding
import java.time.Instant
import java.time.ZoneOffset

class FinanceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFinanceBinding
    private lateinit var repo: Repository
    private lateinit var txAdapter: TxAdapter

    private var preset = "all"
    private var customFrom = ""
    private var customTo = ""

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) renderFromCache()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = Repository.get(this)

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

        NavHelper.bind(this, binding.bottomNav, R.id.nav_finance)

        binding.swipeRefresh.setOnRefreshListener { load(force = true) }
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
            } else renderFromCache()
        }

        lifecycleScope.launch {
            repo.errors.collect { msg ->
                Toast.makeText(this@FinanceActivity, msg, Toast.LENGTH_LONG).show()
                renderFromCache()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (repo.isConfigured()) {
            if (repo.snapshotOrNull() != null) renderFromCache()
            load(force = false)
        } else {
            binding.balanceText.text = "—"
            binding.periodText.text = "Настройки → URL и token"
            txAdapter.submit(emptyList())
        }
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
        renderFromCache()
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

    private fun load(force: Boolean) {
        if (!repo.isConfigured()) {
            binding.swipeRefresh.isRefreshing = false
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
                    binding.periodText.text = it
                    Toast.makeText(this@FinanceActivity, it, Toast.LENGTH_LONG).show()
                }
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderFromCache() {
        val (from, to) =
            if (preset == "range") customFrom to customTo else DatePresets.range(preset)
        val dash = repo.dashboard(from, to)
        binding.balanceText.text = "${DatePresets.money(dash.balance)} ₽"
        binding.periodText.text =
            "+${DatePresets.money(dash.periodIncome)} / −${DatePresets.money(dash.periodExpense)}"
        txAdapter.submit(dash.transactions)
        binding.listEmpty.visibility =
            if (dash.transactions.isEmpty()) View.VISIBLE else View.GONE
    }
}
