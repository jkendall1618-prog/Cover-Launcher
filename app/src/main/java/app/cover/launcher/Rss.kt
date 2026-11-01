package app.cover.launcher

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One headline from a news feed. [star]: it names an artist you follow on Spotify. */
data class Headline(
    val title: String,
    val link: String,
    val at: Instant,
    val source: String,
    val star: Boolean = false,
)

/**
 * Reads RSS 2.0 and Atom feeds well enough for headlines: title, link and date per item.
 * Pattern-based rather than a strict XML parser on purpose, because blog feeds often carry
 * stray HTML entities that make a strict parser give up on the whole feed.
 */
object Rss {
    private val ITEM = Regex("""<(item|entry)\b[^>]*>(.*?)</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CDATA = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
    private val TAGS = Regex("""<[^>]+>""")
    private val NUM = Regex("""&#(x?)([0-9a-fA-F]+);""")
    private val ATOM_LINK = Regex("""<link\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val REL = Regex("""rel\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun parse(xml: String, source: String): List<Headline> =
        ITEM.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[2]
            val title = text(tag(body, "title") ?: return@mapNotNull null)
            if (title.isBlank()) return@mapNotNull null
            val link = link(body) ?: return@mapNotNull null
            val at = date(tag(body, "pubDate") ?: tag(body, "published") ?: tag(body, "updated") ?: tag(body, "dc:date"))
                ?: return@mapNotNull null
            Headline(title, link, at, source)
        }.toList()

    private fun tag(body: String, name: String): String? {
        val r = Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return r.find(body)?.groupValues?.get(1)
    }

    /** RSS: <link>url</link>. Atom: <link rel="alternate" href="url"/>, the first without a rel if none. */
    private fun link(body: String): String? {
        tag(body, "link")?.let { text(it) }?.takeIf { it.startsWith("http") }?.let { return it }
        val links = ATOM_LINK.findAll(body).map { it.groupValues[1] }.toList()
        val pick = links.firstOrNull { REL.find(it)?.groupValues?.get(1) == "alternate" }
            ?: links.firstOrNull { REL.find(it) == null }
            ?: return null
        return HREF.find(pick)?.groupValues?.get(1)?.let { unescape(it) }
    }

    /** CDATA unwrapped, HTML stripped, entities decoded, whitespace squashed. */
    fun text(s: String): String {
        val raw = CDATA.replace(s) { it.groupValues[1] }
        return unescape(TAGS.replace(unescape(raw), "")).replace(Regex("""\s+"""), " ").trim()
    }

    private fun unescape(s: String): String {
        val named = s
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&ndash;", "–").replace("&mdash;", "—").replace("&hellip;", "…")
            .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&ldquo;", "“").replace("&rdquo;", "”")
        val nums = NUM.replace(named) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code == null || code !in 1..0x10FFFF) m.value else String(Character.toChars(code))
        }
        // Last, so "&amp;#8217;" becomes "&#8217;" rather than an apostrophe twice-decoded.
        return nums.replace("&amp;", "&")
    }

    private fun date(s: String?): Instant? {
        val v = s?.let { text(it) }?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(v) }.getOrNull()
            // "Sat, 31 Oct 2026 14:00:00 EST": drop the zone name and read it as UTC rather than lose the item.
            ?: runCatching {
                val trimmed = v.substringBeforeLast(' ') + " +0000"
                ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull()
    }

    /**
     * Artists you follow that a headline names, matched as whole words with the artist's own
     * capitalisation, so "Sleep" the band stars but "can't sleep" doesn't. Names under three
     * letters are skipped (too many false hits).
     */
    fun mentions(title: String, artists: Collection<String>): Boolean = artists.any { a ->
        a.length >= 3 && Regex("""(?<![\p{L}\p{N}])${Regex.escape(a)}(?![\p{L}\p{N}])""").containsMatchIn(title)
    }

    /** Newest first with your artists on top; one copy of each story (same link, or same title on two sites). */
    fun merge(items: List<Headline>, artists: Collection<String>, since: Instant, limit: Int): List<Headline> {
        val seen = HashSet<String>()
        return items
            .filter { it.at.isAfter(since) }
            .sortedByDescending { it.at }
            .filter { seen.add(it.link.trimEnd('/')) and seen.add("t:" + it.title.lowercase().filter { c -> c.isLetterOrDigit() }) }
            .map { it.copy(star = mentions(it.title, artists)) }
            .sortedWith(compareByDescending<Headline> { it.star }.thenByDescending { it.at })
            .take(limit)
    }
}
