package ru.dohody.rashody

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dohody.rashody.databinding.ActivitySettingsBinding
import ru.dohody.rashody.databinding.ItemCategoryBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var client: GasClient
    private val categories = mutableListOf<Category>()
    private lateinit var adapter: CategoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        client = GasClient(prefs)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.urlInput.setText(prefs.webAppUrl)
        binding.tokenInput.setText(prefs.token)

        binding.saveButton.setOnClickListener {
            prefs.webAppUrl = binding.urlInput.text?.toString().orEmpty()
            prefs.token = binding.tokenInput.text?.toString().orEmpty()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            loadCategories()
        }

        binding.pingButton.setOnClickListener {
            prefs.webAppUrl = binding.urlInput.text?.toString().orEmpty()
            prefs.token = binding.tokenInput.text?.toString().orEmpty()
            binding.pingResult.text = "Проверяю…"
            lifecycleScope.launch {
                try {
                    val json = withContext(Dispatchers.IO) { GasClient(prefs).ping() }
                    binding.pingResult.text =
                        if (json.optBoolean("ok")) {
                            "OK. Gemini: ${json.optBoolean("gemini")}"
                        } else {
                            json.optString("error", "Ошибка")
                        }
                } catch (e: Exception) {
                    binding.pingResult.text = e.message
                }
            }
        }

        adapter = CategoryAdapter(
            onRename = { promptRename(it) },
            onDelete = { promptDelete(it) }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = adapter
        binding.addCategoryButton.setOnClickListener { addCategory() }

        if (prefs.isConfigured()) loadCategories()
    }

    private fun loadCategories() {
        if (!prefs.isConfigured()) {
            categories.clear()
            adapter.submit(categories)
            binding.categoriesEmpty.visibility = View.VISIBLE
            return
        }
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.listCategories() }
                categories.clear()
                categories.addAll(list)
                adapter.submit(categories)
                binding.categoriesEmpty.visibility =
                    if (categories.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addCategory() {
        if (!prefs.isConfigured()) {
            Toast.makeText(this, "Сначала сохраните URL и token", Toast.LENGTH_SHORT).show()
            return
        }
        val name = binding.newCategoryInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.addCategory(name) }
                binding.newCategoryInput.setText("")
                loadCategories()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptRename(cat: Category) {
        val input = EditText(this).apply {
            setText(cat.name)
            setSelection(cat.name.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.renameCategory(cat.id, name) }
                        loadCategories()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun promptDelete(cat: Category) {
        lifecycleScope.launch {
            try {
                val (name, count) = withContext(Dispatchers.IO) { client.categoryUsage(cat.id) }
                if (count == 0) {
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("Удалить «$name»?")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> doDelete(cat.id, null) }
                        .show()
                } else {
                    val others = categories.filter { it.id != cat.id }.map { it.name }.toTypedArray()
                    if (others.isEmpty()) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Сначала добавьте категорию для замены ($count записей)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    var chosen = others[0]
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("Заменить в $count записях")
                        .setSingleChoiceItems(others, 0) { _, which -> chosen = others[which] }
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> doDelete(cat.id, chosen) }
                        .show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doDelete(id: String, replacement: String?) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.deleteCategory(id, replacement) }
                loadCategories()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private class CategoryAdapter(
        private val onRename: (Category) -> Unit,
        private val onDelete: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {
        private val data = mutableListOf<Category>()

        fun submit(list: List<Category>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

        inner class VH(private val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(cat: Category) {
                b.categoryName.text = cat.name
                b.renameButton.setOnClickListener { onRename(cat) }
                b.deleteButton.setOnClickListener { onDelete(cat) }
            }
        }
    }
}
