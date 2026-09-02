package ru.dohody.rashody

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dohody.rashody.databinding.ActivityEditTxBinding
import java.time.LocalDate

class EditTxActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEditTxBinding
    private lateinit var client: GasClient
    private var txId: String = ""
    private var mode: String = MODE_EDIT
    private var rawText: String? = null
    private var categories: List<Category> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditTxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_EDIT
        txId = intent.getStringExtra(EXTRA_ID).orEmpty()
        rawText = intent.getStringExtra(EXTRA_RAW)

        if (mode == MODE_EDIT && txId.isBlank()) {
            finish()
            return
        }

        client = GasClient(Prefs(this))
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title =
            if (mode == MODE_CREATE) getString(R.string.confirm_tx) else getString(R.string.edit_tx)
        binding.saveButton.text =
            if (mode == MODE_CREATE) getString(R.string.save_tx) else getString(R.string.save)
        binding.deleteButton.visibility = if (mode == MODE_CREATE) View.GONE else View.VISIBLE

        binding.typeGroup.setOnCheckedChangeListener { _, _ ->
            binding.categoryLayout.visibility =
                if (binding.typeExpense.isChecked) View.VISIBLE else View.GONE
        }

        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }

        if (mode == MODE_CREATE) {
            loadForCreate()
        } else {
            loadForEdit()
        }
    }

    private fun loadForCreate() {
        lifecycleScope.launch {
            try {
                categories = withContext(Dispatchers.IO) { client.listCategories() }
                setupCategoryDropdown()
                val type = intent.getStringExtra(EXTRA_TYPE)
                if (type == "income") binding.typeIncome.isChecked = true
                else binding.typeExpense.isChecked = true
                intent.getDoubleExtra(EXTRA_AMOUNT, Double.NaN).takeIf { !it.isNaN() }?.let {
                    binding.amountInput.setText(DatePresets.money(it))
                }
                binding.categoryInput.setText(intent.getStringExtra(EXTRA_CATEGORY).orEmpty(), false)
                binding.commentInput.setText(intent.getStringExtra(EXTRA_COMMENT).orEmpty())
                binding.dateInput.setText(
                    intent.getStringExtra(EXTRA_DATE)?.takeIf { it.isNotBlank() }
                        ?: LocalDate.now().toString()
                )
                binding.categoryLayout.visibility =
                    if (binding.typeExpense.isChecked) View.VISIBLE else View.GONE
                val raw = rawText?.takeIf { it.isNotBlank() }
                if (raw != null) {
                    binding.rawHint.visibility = View.VISIBLE
                    binding.rawHint.text = "«$raw»"
                }
            } catch (e: Exception) {
                Toast.makeText(this@EditTxActivity, e.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun loadForEdit() {
        lifecycleScope.launch {
            try {
                val tx = withContext(Dispatchers.IO) { client.getTransaction(txId) }
                categories = withContext(Dispatchers.IO) { client.listCategories() }
                bind(tx)
            } catch (e: Exception) {
                Toast.makeText(this@EditTxActivity, e.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun bind(tx: Tx) {
        if (tx.type == "income") binding.typeIncome.isChecked = true
        else binding.typeExpense.isChecked = true
        binding.amountInput.setText(DatePresets.money(tx.amount))
        binding.commentInput.setText(tx.comment.orEmpty())
        binding.dateInput.setText(DatePresets.displayDate(tx.date))
        binding.categoryLayout.visibility =
            if (binding.typeExpense.isChecked) View.VISIBLE else View.GONE
        setupCategoryDropdown()
        binding.categoryInput.setText(tx.category.orEmpty(), false)
    }

    private fun setupCategoryDropdown() {
        val names = categories.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        binding.categoryInput.setAdapter(adapter)
        binding.categoryInput.threshold = 0
        binding.categoryInput.setOnClickListener { binding.categoryInput.showDropDown() }
        binding.categoryInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.categoryInput.showDropDown()
        }
    }

    private fun readDraft(): Draft? {
        val type = if (binding.typeIncome.isChecked) "income" else "expense"
        val amount = binding.amountInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "Укажите сумму", Toast.LENGTH_SHORT).show()
            return null
        }
        val category = binding.categoryInput.text?.toString()?.trim().orEmpty()
        if (type == "expense" && category.isBlank()) {
            Toast.makeText(this, getString(R.string.pick_category), Toast.LENGTH_SHORT).show()
            return null
        }
        val known = categories.any { it.name.equals(category, ignoreCase = true) }
        return Draft(
            type = type,
            amount = amount,
            category = category,
            categoryIsNew = type == "expense" && category.isNotBlank() && !known,
            comment = binding.commentInput.text?.toString().orEmpty(),
            rawText = rawText,
            date = binding.dateInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun save() {
        val draft = readDraft() ?: return

        fun doSave(confirmNew: Boolean) {
            binding.saveButton.isEnabled = false
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (mode == MODE_CREATE) {
                            val source = intent.getStringExtra(EXTRA_SOURCE) ?: "android_voice"
                            client.create(draft, confirmNew, source = source)
                        } else {
                            client.update(txId, draft, confirmNew)
                        }
                    }
                    setResult(RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@EditTxActivity, e.message, Toast.LENGTH_LONG).show()
                    binding.saveButton.isEnabled = true
                }
            }
        }

        if (draft.categoryIsNew) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Новая категория")
                .setMessage("Добавить «${draft.category}»?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save) { _, _ -> doSave(true) }
                .show()
        } else {
            doSave(false)
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить запись?")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.delete(txId) }
                        setResult(RESULT_OK)
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@EditTxActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "tx_id"
        const val EXTRA_MODE = "mode"
        const val MODE_EDIT = "edit"
        const val MODE_CREATE = "create"
        const val EXTRA_TYPE = "type"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_COMMENT = "comment"
        const val EXTRA_DATE = "date"
        const val EXTRA_RAW = "raw"
        const val EXTRA_SOURCE = "source"

        fun intentEdit(context: Context, id: String): Intent =
            Intent(context, EditTxActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_EDIT)
                .putExtra(EXTRA_ID, id)

        fun intentConfirm(context: Context, draft: Draft, source: String = "android_voice"): Intent =
            Intent(context, EditTxActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_CREATE)
                .putExtra(EXTRA_TYPE, draft.type)
                .putExtra(EXTRA_AMOUNT, draft.amount ?: Double.NaN)
                .putExtra(EXTRA_CATEGORY, draft.category)
                .putExtra(EXTRA_COMMENT, draft.comment)
                .putExtra(EXTRA_DATE, draft.date)
                .putExtra(EXTRA_RAW, draft.rawText)
                .putExtra(EXTRA_SOURCE, source)
    }
}
