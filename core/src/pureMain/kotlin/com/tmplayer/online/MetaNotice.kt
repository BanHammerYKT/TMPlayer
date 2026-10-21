package com.tmplayer.online

/**
 * The two one-time words about online metadata in a chat's grid.
 *
 * The note: lookups are on by default, so the first grid says so once, with Keep and Turn off,
 * and never again after either. With lookups already off there is nothing to say.
 *
 * The invite: a viewer whose lookups are off because an older version had them off by default is
 * asked once whether to turn them on. Not somebody who turned them off in this version (Settings,
 * the first-run switch, the note, a restore), and never again after either answer.
 */
object MetaNotice {
    fun shouldShow(enabled: Boolean, dismissed: Boolean): Boolean = enabled && !dismissed

    fun shouldInvite(settings: MetaSettings, inviteSeen: Boolean): Boolean =
        !settings.enabled && !settings.offByChoice && !inviteSeen
}
