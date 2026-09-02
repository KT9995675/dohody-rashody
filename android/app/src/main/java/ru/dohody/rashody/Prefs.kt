package ru.dohody.rashody

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dohody", Context.MODE_PRIVATE)

    var webAppUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_URL, value.trim()).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value.trim()).apply()

    fun isConfigured(): Boolean = webAppUrl.isNotBlank() && token.isNotBlank()

    companion object {
        private const val KEY_URL = "web_app_url"
        private const val KEY_TOKEN = "token"
    }
}
