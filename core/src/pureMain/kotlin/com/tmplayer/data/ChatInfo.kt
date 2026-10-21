package com.tmplayer.data

/**
 * A chat's info sheet: [summary] for its name, kind and small picture, [bigPhotoFileId] for the
 * large one, the public [username] (without the @) when it has one, a person's bio or a group's or
 * channel's description as [about], and [memberCount] for groups and channels.
 */
data class ChatInfo(
    val summary: ChatSummary,
    val bigPhotoFileId: Int,
    val username: String?,
    val about: String?,
    val memberCount: Int?,
    /**
     * A public group or channel the account is not in, which the sheet offers to join (CP79);
     * [joinByRequest] when the group's admins approve each newcomer.
     */
    val canJoin: Boolean = false,
    val joinByRequest: Boolean = false,
) {
    /** The public t.me address, for sharing and copying; null for a chat with no username. */
    val link: String? get() = username?.let { "https://t.me/$it" }
}

/** Telegram usernames as people type them. */
object Usernames {
    /** 5 to 32 letters, digits and underscores, starting with a letter (Telegram's own rule). */
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")

    /**
     * The username in [text], or null when it cannot be one: a leading @, a t.me or telegram.me
     * address (with or without https://) and surrounding spaces are taken off.
     */
    fun clean(text: String): String? {
        var t = text.trim()
        t = t.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        for (host in listOf("t.me/", "telegram.me/", "telegram.dog/")) {
            if (t.startsWith(host, ignoreCase = true)) {
                t = t.substring(host.length)
                // The web preview of a channel, t.me/s/<username>.
                if (t.startsWith("s/", ignoreCase = true)) t = t.substring(2)
                t = t.substringBefore('/').substringBefore('?')
                // Telegram's own paths (an invite, a private post, a proxy) name nobody.
                if (t.lowercase() in TelegramLinks.RESERVED) return null
            }
        }
        t = t.removePrefix("@")
        return t.takeIf { NAME.matches(it) }
    }

    /** Whether [text] reads as someone asking for a username rather than a title: an @ or a t.me address. */
    fun looksAsked(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("@") || t.contains("t.me/", ignoreCase = true) || t.contains("telegram.me/", ignoreCase = true)) && clean(t) != null
    }
}
