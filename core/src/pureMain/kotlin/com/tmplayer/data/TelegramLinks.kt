package com.tmplayer.data

/**
 * A Telegram link as somebody pastes it or opens it from another app (CP79): a post, an invite
 * to a private group or channel, or a public chat by its username.
 */
sealed interface TgLink {
    /** The https address TDLib is handed for this link, whichever form it arrived in. */
    val url: String

    /**
     * One post: `t.me/<username>/<id>`, a private channel's `t.me/c/<internal id>/<id>`, either
     * with a forum topic between (`t.me/<username>/<topic>/<id>`), and the tg:// forms of both.
     * [startSec] is the `?t=` start time, 0 for none; [commentId] and [threadId] the `?comment=`
     * and `?thread=` a link to a comment carries, kept so Telegram resolves the comment itself.
     */
    data class Post(
        val username: String?,
        val channelId: Long?,
        val messageId: Long,
        val topicId: Long = 0,
        val startSec: Int = 0,
        val commentId: Long = 0,
        val threadId: Long = 0,
    ) : TgLink {
        override val url: String
            get() = buildString {
                append("https://t.me/")
                append(if (username != null) username else "c/$channelId")
                if (topicId != 0L) append("/").append(topicId)
                append("/").append(messageId)
                val query = listOfNotNull(
                    commentId.takeIf { it != 0L }?.let { "comment=$it" },
                    threadId.takeIf { it != 0L }?.let { "thread=$it" },
                    startSec.takeIf { it > 0 }?.let { "t=$it" },
                )
                if (query.isNotEmpty()) append("?").append(query.joinToString("&"))
            }
    }

    /** An invite: `t.me/+<hash>`, `t.me/joinchat/<hash>` or `tg://join?invite=<hash>`. */
    data class Invite(val hash: String) : TgLink {
        override val url: String get() = "https://t.me/+$hash"
    }

    /** A public chat: `t.me/<username>`, `@username` is [Usernames]' and Search's own. */
    data class Public(val username: String) : TgLink {
        override val url: String get() = "https://t.me/$username"
    }
}

/** Reads Telegram's links out of what people paste and what other apps hand over. */
object TelegramLinks {

    private val HOSTS = listOf("t.me", "telegram.me", "telegram.dog")

    /** Telegram's own first path parts, which are never a username. */
    internal val RESERVED = setOf(
        "c", "s", "joinchat", "addstickers", "addemoji", "addtheme", "addlist", "proxy", "socks",
        "share", "setlanguage", "login", "confirmphone", "bg", "invoice", "boost", "contact",
        "giftcode", "iv", "m", "nft", "msg", "msg_url", "privatepost", "premium_offer",
        "premium_multigift", "stars_topup",
    )

    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")
    private val HASH = Regex("^[A-Za-z0-9_-]{8,64}$")
    private val NUMBER = Regex("^[0-9]{1,19}$")

    /** Anything in a pasted text that looks like a Telegram address, for [find]. */
    private val IN_TEXT = Regex(
        """(?i)(?:https?://)?(?:www\.)?(?:t\.me|telegram\.me|telegram\.dog)/[^\s<>"']+|tg://[^\s<>"']+""",
    )

    /**
     * [text] as a link, when the whole of it (spaces aside) is one; null otherwise, including for
     * a plain `@username`, which Search answers through [Usernames].
     */
    fun parse(text: String): TgLink? {
        val t = text.trim().trimEnd('.', ',', ')', ']', '>')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        return if (t.startsWith("tg:", ignoreCase = true)) parseTg(t) else parseWeb(t)
    }

    /** The first link anywhere in [text]: a forwarded message's words with a post link in them. */
    fun find(text: String): TgLink? =
        IN_TEXT.findAll(text).firstNotNullOfOrNull { parse(it.value) }

    /** Whether [text] is a post or an invite, the two kinds Search offers to open (CP79). */
    fun opensSomething(text: String): Boolean = parse(text).let { it is TgLink.Post || it is TgLink.Invite }

    private fun parseWeb(link: String): TgLink? {
        var t = link
        t = t.removePrefixIgnoringCase("https://").removePrefixIgnoringCase("http://").removePrefixIgnoringCase("www.")
        val host = HOSTS.firstOrNull { t.startsWith("$it/", ignoreCase = true) } ?: return null
        t = t.substring(host.length + 1)
        val path = t.substringBefore('?').substringBefore('#').trimEnd('/')
        val query = queryOf(t)
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val first = parts[0]
        return when {
            first.startsWith("+") -> first.drop(1).takeIf { HASH.matches(it) && !NUMBER.matches(it) }?.let { TgLink.Invite(it) }
            first.equals("joinchat", ignoreCase = true) -> parts.getOrNull(1)?.takeIf { HASH.matches(it) }?.let { TgLink.Invite(it) }
            first.equals("c", ignoreCase = true) -> {
                val channel = parts.getOrNull(1)?.takeIf { NUMBER.matches(it) }?.toLongOrNull() ?: return null
                post(username = null, channelId = channel, rest = parts.drop(2), query = query)
            }
            // The web preview of a channel, t.me/s/<username>/<id>.
            first.equals("s", ignoreCase = true) -> {
                val name = parts.getOrNull(1)?.takeIf { isUsername(it) } ?: return null
                if (parts.size == 2) TgLink.Public(name) else post(name, null, parts.drop(2), query)
            }
            isUsername(first) -> if (parts.size == 1) TgLink.Public(first) else post(first, null, parts.drop(1), query)
            else -> null
        }
    }

    private fun parseTg(link: String): TgLink? {
        val body = link.substringAfter(':').removePrefix("//")
        val action = body.substringBefore('?').trimEnd('/').lowercase()
        val query = queryOf(body)
        val postId = query["post"]?.takeIf { NUMBER.matches(it) }?.toLongOrNull()
        return when (action) {
            "resolve" -> {
                val name = query["domain"]?.takeIf { isUsername(it) } ?: return null
                if (postId == null) TgLink.Public(name) else withQuery(TgLink.Post(name, null, postId), query)
            }
            "privatepost" -> {
                val channel = query["channel"]?.takeIf { NUMBER.matches(it) }?.toLongOrNull() ?: return null
                withQuery(TgLink.Post(null, channel, postId ?: return null), query)
            }
            "join" -> query["invite"]?.takeIf { HASH.matches(it) }?.let { TgLink.Invite(it) }
            else -> null
        }
    }

    /** The post a path after the chat names: `<id>`, or `<topic>/<id>`. */
    private fun post(username: String?, channelId: Long?, rest: List<String>, query: Map<String, String>): TgLink? {
        val numbers = rest.take(2).map { it.takeIf { n -> NUMBER.matches(n) }?.toLongOrNull() ?: return null }
        if (numbers.isEmpty() || rest.size > 2) return null
        val base = if (numbers.size == 2) {
            TgLink.Post(username, channelId, messageId = numbers[1], topicId = numbers[0])
        } else {
            TgLink.Post(username, channelId, messageId = numbers[0])
        }
        if (base.messageId <= 0L) return null
        return withQuery(base, query)
    }

    private fun withQuery(post: TgLink.Post, query: Map<String, String>): TgLink.Post = post.copy(
        topicId = post.topicId.takeIf { it != 0L } ?: query["topic"]?.toLongOrNull() ?: 0L,
        startSec = query["t"]?.let(::startSeconds) ?: 0,
        commentId = query["comment"]?.toLongOrNull() ?: 0L,
        threadId = query["thread"]?.toLongOrNull() ?: 0L,
    )

    /**
     * A start time as Telegram writes it after `t=`: whole seconds (`90`), `1h2m3s` in any part,
     * or a clock (`1:30`, `1:02:03`). Null for anything else; 0 is "from the start".
     */
    fun startSeconds(text: String): Int? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it.takeIf { s -> s >= 0 } }
        if (':' in t) {
            val parts = t.split(':').map { it.toIntOrNull() ?: return null }
            if (parts.size !in 2..3 || parts.any { it < 0 }) return null
            return parts.fold(0) { acc, n -> acc * 60 + n }
        }
        val units = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(t) ?: return null
        if (units.groupValues.drop(1).all { it.isEmpty() }) return null
        val (h, m, s) = units.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
        return h * 3600 + m * 60 + s
    }

    private fun isUsername(text: String): Boolean = NAME.matches(text) && text.lowercase() !in RESERVED

    private fun queryOf(text: String): Map<String, String> {
        val q = text.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            if (key.isEmpty()) null else key to value
        }.toMap()
    }

    private fun String.removePrefixIgnoringCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
