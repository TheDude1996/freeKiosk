package com.kioskbrowser.app

/**
 * Whitelist / blacklist matching. One pattern per line (or comma separated).
 * '*' matches any sequence, patterns without scheme also match http(s)://pattern.
 * Lines starting with "regex:" are treated as raw regular expressions.
 */
object UrlFilter {
    private val internalPrefixes = listOf("file:///android_asset/", "about:", "data:", "blob:", "javascript:")

    fun isAllowed(url: String, whitelist: String, blacklist: String): Boolean {
        if (internalPrefixes.any { url.startsWith(it) }) return true
        val black = parse(blacklist)
        if (black.any { it.containsMatchIn(url) }) return false
        val white = parse(whitelist)
        return white.isEmpty() || white.any { it.containsMatchIn(url) }
    }

    private fun parse(list: String): List<Regex> =
        list.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { p ->
            runCatching {
                if (p.startsWith("regex:")) Regex(p.removePrefix("regex:"), RegexOption.IGNORE_CASE)
                else {
                    val glob = p.split('*').joinToString(".*") { Regex.escape(it) }
                    val withScheme = if (p.contains("://")) glob else "(https?://)?$glob"
                    Regex("^$withScheme", RegexOption.IGNORE_CASE)
                }
            }.getOrNull()
        }
}
