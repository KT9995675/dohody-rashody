package ru.dohody.rashody

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ParseResult(
    val ok: Boolean,
    val error: String?,
    val draft: Draft?
)

data class Draft(
    val type: String?,
    val amount: Double?,
    val category: String?,
    val categoryIsNew: Boolean,
    val comment: String?,
    val rawText: String?,
    val date: String? = null
)

class GasClient(private val prefs: Prefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun baseExecUrl(): String {
        var base = prefs.webAppUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "Укажите URL в настройках" }
        require(prefs.token.isNotBlank()) { "Укажите token в настройках" }
        if (base.contains("/macros/s/") && base.endsWith("/dev")) {
            base = base.removeSuffix("/dev") + "/exec"
        }
        return base
    }

    private fun urlWithParams(extra: Map<String, String> = emptyMap()): String {
        val http = baseExecUrl().toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalStateException("Некорректный URL веб-приложения")
        http.setQueryParameter("token", prefs.token.trim())
        extra.forEach { (k, v) -> http.setQueryParameter(k, v) }
        return http.build().toString()
    }

    private fun resolveLocation(resp: okhttp3.Response): String {
        val loc = resp.header("Location")
            ?: throw IllegalStateException("HTTP ${resp.code} без Location")
        return resp.request.url.resolve(loc)?.toString()
            ?: loc.toHttpUrlOrNull()?.toString()
            ?: loc
    }

    private fun getFollowingRedirects(startUrl: String): JSONObject {
        var url = startUrl
        repeat(8) {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code in 300..399) {
                    url = resolveLocation(resp)
                    return@repeat
                }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(explainHttp(resp.code, text))
                }
                return parseJsonBody(text)
            }
        }
        throw IllegalStateException("Слишком много редиректов от Google")
    }

    private fun post(payload: JSONObject): JSONObject {
        val withToken = JSONObject(payload.toString()).put("token", prefs.token.trim())
        val body = withToken.toString()
            .toRequestBody("text/plain;charset=utf-8".toMediaType())

        val postReq = Request.Builder()
            .url(urlWithParams())
            .post(body)
            .header("Accept", "application/json")
            .build()

        client.newCall(postReq).execute().use { resp ->
            when {
                resp.code in 300..399 -> {
                    return getFollowingRedirects(resolveLocation(resp))
                }
                resp.isSuccessful -> {
                    return parseJsonBody(resp.body?.string().orEmpty())
                }
                else -> {
                    throw IllegalStateException(explainHttp(resp.code, resp.body?.string().orEmpty()))
                }
            }
        }
    }

    private fun explainHttp(code: Int, text: String): String {
        val snippet = text.replace(Regex("\\s+"), " ").take(160)
        return when {
            code == 405 ->
                "HTTP 405: отклонено Google. Нужен деплой Anyone + URL …/exec."
            code == 401 || code == 403 ->
                "HTTP $code: нет доступа. Деплой: Anyone, URL …/exec."
            snippet.contains("<html", ignoreCase = true) ->
                "HTTP $code: HTML вместо JSON.\n$snippet"
            else -> "HTTP $code: $snippet"
        }
    }

    private fun parseJsonBody(text: String): JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalStateException(
                "Не JSON от сервера.\n" + text.replace(Regex("\\s+"), " ").take(200)
            )
        }
        return JSONObject(text.substring(start, end + 1))
    }

    private fun requireOk(json: JSONObject): JSONObject {
        if (!json.optBoolean("ok", false) && json.has("error")) {
            throw IllegalStateException(json.optString("error", "Ошибка API"))
        }
        if (json.has("error") && json.optString("error").isNotBlank() &&
            !json.optBoolean("ok", true)
        ) {
            throw IllegalStateException(json.optString("error"))
        }
        return json
    }

    fun ping(): JSONObject = getFollowingRedirects(urlWithParams(mapOf("action" to "ping")))

    fun dashboard(from: String, to: String): Dashboard {
        val json = requireOk(
            post(
                JSONObject()
                    .put("action", "dashboard")
                    .put("from", from)
                    .put("to", to)
            )
        )
        return JsonMap.dashboard(json.optJSONObject("dashboard"))
            ?: throw IllegalStateException("Пустой dashboard")
    }

    fun parseAudio(base64: String, mimeType: String): ParseResult {
        return parseResultFrom(
            post(
                JSONObject()
                    .put("action", "parseAudio")
                    .put("audioBase64", base64)
                    .put("mimeType", mimeType)
            )
        )
    }

    fun parseText(text: String): ParseResult {
        return parseResultFrom(
            post(JSONObject().put("action", "parseText").put("text", text))
        )
    }

    fun create(draft: Draft, confirmNewCategory: Boolean, source: String = "android_voice"): JSONObject {
        val d = draftJson(draft)
        return requireOk(
            post(
                JSONObject()
                    .put("action", "create")
                    .put("draft", d)
                    .put("confirmNewCategory", confirmNewCategory)
                    .put("source", source)
            )
        )
    }

    fun update(id: String, draft: Draft, confirmNewCategory: Boolean): JSONObject {
        return requireOk(
            post(
                JSONObject()
                    .put("action", "update")
                    .put("id", id)
                    .put("draft", draftJson(draft))
                    .put("confirmNewCategory", confirmNewCategory)
            )
        )
    }

    fun delete(id: String): JSONObject =
        requireOk(post(JSONObject().put("action", "delete").put("id", id)))

    fun getTransaction(id: String): Tx {
        val json = requireOk(
            post(JSONObject().put("action", "getTransaction").put("id", id))
        )
        return JsonMap.tx(json.optJSONObject("transaction"))
            ?: throw IllegalStateException("Нет transaction")
    }

    fun listCategories(): List<Category> {
        val json = requireOk(post(JSONObject().put("action", "listCategories")))
        val out = mutableListOf<Category>()
        val arr = json.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until arr.length()) {
            JsonMap.category(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun addCategory(name: String): Category {
        val json = requireOk(
            post(JSONObject().put("action", "addCategory").put("name", name))
        )
        return JsonMap.category(json.optJSONObject("category"))
            ?: throw IllegalStateException("Нет category")
    }

    fun renameCategory(id: String, name: String): Category {
        val json = requireOk(
            post(
                JSONObject()
                    .put("action", "renameCategory")
                    .put("id", id)
                    .put("name", name)
            )
        )
        return JsonMap.category(json.optJSONObject("category"))
            ?: throw IllegalStateException("Нет category")
    }

    fun deleteCategory(id: String, replacementName: String?): JSONObject {
        val p = JSONObject().put("action", "deleteCategory").put("id", id)
        if (!replacementName.isNullOrBlank()) p.put("replacementName", replacementName)
        return requireOk(post(p))
    }

    fun categoryUsage(id: String): Pair<String, Int> {
        val json = requireOk(post(JSONObject().put("action", "categoryUsage").put("id", id)))
        val u = json.optJSONObject("usage") ?: throw IllegalStateException("Нет usage")
        return u.optString("name") to u.optInt("count", 0)
    }

    private fun draftJson(draft: Draft): JSONObject {
        val d = JSONObject()
            .put("type", draft.type)
            .put("amount", draft.amount)
            .put("category", draft.category)
            .put("comment", draft.comment)
            .put("rawText", draft.rawText)
        if (!draft.date.isNullOrBlank()) d.put("date", draft.date)
        return d
    }

    private fun parseResultFrom(json: JSONObject): ParseResult {
        val draftJson = json.optJSONObject("draft")
        val draft = draftJson?.let {
            Draft(
                type = it.optString("type").takeIf { s -> s.isNotBlank() && s != "null" },
                amount = if (it.has("amount") && !it.isNull("amount")) it.optDouble("amount") else null,
                category = it.optString("category").takeIf { s -> s.isNotBlank() && s != "null" },
                categoryIsNew = it.optBoolean("categoryIsNew", false),
                comment = it.optString("comment").takeIf { s -> s.isNotBlank() && s != "null" },
                rawText = it.optString("rawText").takeIf { s -> s.isNotBlank() && s != "null" }
            )
        }
        val error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
        return ParseResult(
            ok = json.optBoolean("ok", false),
            error = error,
            draft = draft
        )
    }
}
