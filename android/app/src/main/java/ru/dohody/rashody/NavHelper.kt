package ru.dohody.rashody

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

object NavHelper {
    fun bind(activity: AppCompatActivity, bottomNav: BottomNavigationView, selectedId: Int) {
        bottomNav.selectedItemId = selectedId
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    if (activity !is HomeActivity) {
                        activity.startActivity(
                            Intent(activity, HomeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        activity.finish()
                    }
                    true
                }
                R.id.nav_finance -> {
                    if (activity !is FinanceActivity) {
                        activity.startActivity(
                            Intent(activity, FinanceActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        activity.finish()
                    }
                    true
                }
                R.id.nav_notes -> {
                    if (activity !is NotesActivity) {
                        activity.startActivity(
                            Intent(activity, NotesActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        activity.finish()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
