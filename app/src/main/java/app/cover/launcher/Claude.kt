package app.cover.launcher

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Turn(val role: String, val text: String)

/** Runs one of Claude's tools and returns what the model should see. */
fun interface ToolRunner {
    fun run(name: String, input: JSONObject): String
}

/** Minimal Messages API client tuned for a tiny screen: short, plain-text answers. */
class Claude(private val prefs: Prefs, private val tools: ToolRunner? = null) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val hasKey get() = prefs.get(Prefs.ANTHROPIC_KEY) != null

    /**
     * Asks Claude, running `get_events` when it asks for the calendar. Tool calls stay
     * inside this turn; the chat history keeps only what was said.
     */
    fun ask(history: List<Turn>, withTools: Boolean = true): String {
        val key = prefs.get(Prefs.ANTHROPIC_KEY) ?: throw IllegalStateException("Add your API key in cover settings")
        val messages = JSONArray()
        history.takeLast(10).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }

        val useTools = withTools && tools != null
        var response = post(key, messages, useTools)
        var round = 0

        while (useTools && response.optString("stop_reason") == "tool_use" && round++ < 4) {
            val content = response.getJSONArray("content")
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val results = JSONArray()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") != "tool_use") continue
                val call = runCatching { tools!!.run(block.getString("name"), block.optJSONObject("input") ?: JSONObject()) }
                results.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.getString("id"))
                        .put("content", call.getOrElse { it.message ?: "The tool failed" })
                        .put("is_error", call.isFailure),
                )
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
            response = post(key, messages, useTools)
        }

        val content = response.getJSONArray("content")
        val text = (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.getString("text") }
            .trim()
        return text.ifBlank { "I couldn't finish that one. Try asking again." }
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
        withTools = false,
    )

    private fun post(key: String, messages: JSONArray, withTools: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 600)
            .put("system", system())
            .put("messages", messages)
            .put("tools", TOOLS)
        if (!withTools) body.remove("tools")

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrNull()
                throw IllegalStateException(message ?: "Claude error ${response.code}")
            }
            return JSONObject(text)
        }
    }

    private fun system(): String {
        val now = ZonedDateTime.now()
        val stamp = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))
        return "$SYSTEM Today is $stamp (${now.zone.id})."
    }

    companion object {
        const val MODEL = "claude-haiku-5-5"
        const val SYSTEM = "You are answering on a small phone cover screen, about 30 characters wide. " +
            "Reply in plain text only, no markdown, no lists unless asked. " +
            "Keep it to three short sentences or fewer unless the user asks for more. Australian English. " +
            "For anything about the calendar, dates, or whether the user is free, call get_events first; " +
            "don't guess and don't rely on remembered dates. " +
            "If a tool says the calendar is unavailable, say so plainly instead of assuming the day is free."

        private val TOOLS = JSONArray().put(
            JSONObject()
                .put("name", "get_events")
                .put(
                    "description",
                    "Read the user's calendar for a date range. Use it for any question about what's on, " +
                        "when something is, or whether the user is free. Returns events with ISO 8601 times, " +
                        "the user's timezone, and where the data came from.",
                )
                .put(
                    "input_schema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "from",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Start of the range, ISO 8601, inclusive. A date alone means that day in the user's timezone."),
                                )
                                .put(
                                    "to",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "End of the range, ISO 8601, exclusive. A date alone means the whole of that day."),
                                ),
                        )
                        .put("required", JSONArray().put("from").put("to")),
                ),
        )
    }
}
