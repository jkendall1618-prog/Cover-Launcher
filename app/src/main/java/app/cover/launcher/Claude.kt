package app.cover.launcher

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Turn(val role: String, val text: String)

/** Minimal Messages API client tuned for a tiny screen: short, plain-text answers. */
class Claude(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val hasKey get() = prefs.get(Prefs.ANTHROPIC_KEY) != null

    fun ask(history: List<Turn>): String {
        val key = prefs.get(Prefs.ANTHROPIC_KEY) ?: throw IllegalStateException("Add your API key in cover settings")
        val msgs = JSONArray()
        history.takeLast(10).forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 600)
            .put("system", SYSTEM)
            .put("messages", msgs)
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IllegalStateException(msg ?: "Claude error ${r.code}")
            }
            val content = JSONObject(text).getJSONArray("content")
            return (0 until content.length())
                .map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .joinToString("") { it.getString("text") }
                .trim()
        }
    }

    /** Page 2's "brief me": one question built from today's calendar, todos and weather. */
    fun brief(facts: String): String = ask(
        listOf(
            Turn(
                "user",
                "Brief me on my day in three short lines: what's on, what to get done, anything to plan around. " +
                    "Only use these facts, don't invent anything.\n\n$facts",
            ),
        ),
    )

    companion object {
        const val MODEL = "claude-haiku-5-5"
        const val SYSTEM = "You are answering on a small phone cover screen, about 30 characters wide. " +
            "Reply in plain text only, no markdown, no lists unless asked. " +
            "Keep it to three short sentences or fewer unless the user asks for more. Australian English."
    }
}
