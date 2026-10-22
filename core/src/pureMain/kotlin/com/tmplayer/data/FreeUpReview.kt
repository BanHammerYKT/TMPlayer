package com.tmplayer.data

/**
 * Free up space as a review, the way a photo app's "Free up space" works (user, 2026-10-22): one
 * list of everything that could go, in groups, each with what it holds; the groups that are only
 * copies (caches, unfinished parts) start ticked, the downloads the viewer chose to keep start
 * unticked and are ticked one by one or all at once; a running total; and one press that frees
 * what is ticked, asked about first only when a download is among it.
 *
 * Pure, so the phone, the television and the desktop share every rule here and the rules can be
 * tested without drawing anything. The pages hand in the [Section]s they measured; the sheet keeps
 * a [Picks] while it is up and hands back the ticked items when the viewer presses Free up.
 */
object FreeUpReview {

    /** The groups, in the order the sheet lists them: the safest first, the viewer's own last. */
    enum class Group {
        /** Downloads that stopped part way: part of a file, which a new download fetches again. */
        Unfinished,

        /** What playing left behind, kept so that going back is instant. */
        Recent,

        /** Video files on the device that no message or record names any more. */
        Unmatched,

        /** Thumbnails, previews and poster images, which come back as the viewer browses. */
        Pictures,

        /** Videos the viewer downloaded on purpose. Never ticked unless the viewer ticks them. */
        Downloads;

        /** Whether the group starts ticked: everything but the downloads the viewer chose to keep. */
        val startsTicked: Boolean get() = this != Downloads

        /** The storage chart's part the group's bytes are counted in, for the colour beside it. */
        val part: StorageChart.Part
            get() = when (this) {
                Unfinished -> StorageChart.Part.Unfinished
                Recent -> StorageChart.Part.Recent
                Unmatched -> StorageChart.Part.Unmatched
                Pictures -> StorageChart.Part.Pictures
                Downloads -> StorageChart.Part.Downloads
            }
    }

    /** Why an item is listed but cannot be ticked. */
    enum class Lock {
        /** A download still coming down (or moving into the folder): never removed from here. */
        Downloading,

        /** The file open in the player right now. */
        Playing,
    }

    /**
     * One thing that can go. [key] is the page's own handle for it, unique in its group; [title]
     * is what the sheet calls it. A [lock] lists it without a tick box, with the reason.
     */
    data class Item(val key: String, val title: String, val bytes: Long, val lock: Lock? = null) {
        val free: Boolean get() = lock == null
    }

    /**
     * A group as measured. [items] are what can be ticked one by one; a group that is cleared as a
     * whole (the recently played videos, the pictures) has none and is ticked as one, with [bytes]
     * and [count] saying what it holds and [names] naming a few of its videos.
     */
    data class Section(
        val group: Group,
        val bytes: Long,
        val count: Int = 0,
        val items: List<Item> = emptyList(),
        val names: List<String> = emptyList(),
    ) {
        /** Ticked one item at a time, rather than as a whole. */
        val byItem: Boolean get() = items.isNotEmpty()

        /** What ticking the whole group frees: the free items, or the group's own figure. */
        val freeable: Long
            get() = if (byItem) items.filter { it.free }.sumOf { it.bytes.coerceAtLeast(0) } else bytes.coerceAtLeast(0)

        /** Whether anything in it can be ticked at all. */
        val tickable: Boolean get() = if (byItem) items.any { it.free } else bytes > 0

        /** Whether the sheet lists it: something to free, or something it has to say is kept. */
        val shown: Boolean get() = tickable || items.isNotEmpty()

        /** The ids of everything tickable in it, as [Picks] holds them. */
        val ids: Set<String>
            get() = if (byItem) items.filter { it.free }.map { id(group, it.key) }.toSet() else if (bytes > 0) setOf(id(group, WHOLE)) else emptySet()
    }

    /** What is ticked, as ids from [id]. */
    data class Picks(val ids: Set<String>)

    /** A group's tick: all of it, none of it, or some of its items. */
    enum class Tick { On, Off, Some }

    /** The id the sheet ticks an item by. A whole group's is its group with [WHOLE]. */
    fun id(group: Group, key: String): String = group.name + "/" + key

    const val WHOLE = "*"

    /** The groups worth listing, in [Group] order. */
    fun shown(sections: List<Section>): List<Section> =
        sections.filter { it.shown }.sortedBy { it.group.ordinal }

    /** Nothing anywhere that a tick could free: the sheet says so and Free up stays off. */
    fun nothingToFree(sections: List<Section>): Boolean = sections.none { it.tickable }

    /** The ticks the sheet opens with: every free item of every group that [Group.startsTicked]. */
    fun initial(sections: List<Section>): Picks =
        Picks(sections.filter { it.group.startsTicked }.flatMap { it.ids }.toSet())

    /**
     * The ticks with [picks]' carried over to a fresh measurement: what is gone is dropped, and a
     * group that turned up since the sheet opened takes its starting tick, so a refresh while the
     * sheet is up neither loses the viewer's choices nor ticks a download behind their back.
     */
    fun carry(picks: Picks, before: List<Section>, after: List<Section>): Picks {
        val known = before.flatMap { s -> s.items.map { id(s.group, it.key) } + id(s.group, WHOLE) }.toSet()
        val now = after.flatMap { it.ids }.toSet()
        val fresh = after.filter { it.group.startsTicked }.flatMap { it.ids }.filter { it !in known }
        return Picks((picks.ids intersect now) + fresh)
    }

    fun tick(picks: Picks, section: Section): Tick {
        val ids = section.ids
        if (ids.isEmpty()) return Tick.Off
        val on = ids.count { it in picks.ids }
        return when (on) {
            0 -> Tick.Off
            ids.size -> Tick.On
            else -> Tick.Some
        }
    }

    /** A press on a group's tick: all of it on, or, when it already is, all of it off. */
    fun toggleGroup(picks: Picks, section: Section): Picks =
        if (tick(picks, section) == Tick.On) Picks(picks.ids - section.ids) else Picks(picks.ids + section.ids)

    /** A press on one item's tick. A locked item does not tick. */
    fun toggleItem(picks: Picks, section: Section, key: String): Picks {
        val item = section.items.firstOrNull { it.key == key } ?: return picks
        if (!item.free) return picks
        val id = id(section.group, key)
        return Picks(if (id in picks.ids) picks.ids - id else picks.ids + id)
    }

    /** What the ticks free, altogether. */
    fun bytes(picks: Picks, sections: List<Section>): Long = sections.sumOf { bytes(picks, it) }

    /** What the ticks free in one group. */
    fun bytes(picks: Picks, section: Section): Long =
        if (section.byItem) {
            section.items.filter { it.free && id(section.group, it.key) in picks.ids }.sumOf { it.bytes.coerceAtLeast(0) }
        } else if (id(section.group, WHOLE) in picks.ids) {
            section.bytes.coerceAtLeast(0)
        } else {
            0
        }

    /** The ticked items of a group that is ticked by item, for the page to remove. */
    fun picked(picks: Picks, section: Section): List<Item> =
        section.items.filter { it.free && id(section.group, it.key) in picks.ids }

    /** Whether a group that is cleared as a whole is ticked. */
    fun wholePicked(picks: Picks, section: Section): Boolean = !section.byItem && id(section.group, WHOLE) in picks.ids

    /** The downloads among the ticks: when there are any, the sheet asks before it frees anything. */
    fun downloadsPicked(picks: Picks, sections: List<Section>): List<Item> =
        sections.filter { it.group == Group.Downloads }.flatMap { picked(picks, it) }

    /** Whether Free up asks first: only when it would delete a download the viewer chose to keep. */
    fun asksFirst(picks: Picks, sections: List<Section>): Boolean = downloadsPicked(picks, sections).isNotEmpty()

    /**
     * What a run did: the bytes it freed and the items it had to leave (in use, or would not
     * delete), which the page says in one short line.
     */
    data class Outcome(val freed: Long, val skipped: Int = 0)
}
