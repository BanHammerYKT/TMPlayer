package com.tmplayer.data

/**
 * How long typing rests before a search goes to Telegram or the network, the same everywhere a
 * field searches as it is typed into: the search across every chat, a chat's own search and the
 * username lookup, on the phone, the TV, the desktop and the web (user, 2026-10-22: "search
 * happens too instantly").
 *
 * Long enough that a word typed at speed, or a TV's on-screen keyboard sending its letters one
 * after another, is one round trip and not one per letter; short enough that a pause reads as
 * the app answering. The keyboard's search key and a recent search picked skip the wait.
 *
 * Filters over a list already on screen (the chat list, the country picker) do not wait: they
 * cost no round trip, and a list that narrows under the finger is the answer itself.
 */
object SearchSettle {
    const val MS = 350L
}
