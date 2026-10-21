package com.tmplayer.cast

/**
 * Subtitles on the TV (CP73). While it is false the cast screens offer
 * no Subtitles button, an HLS master carries no subtitle renditions, a LOAD declares no side-loaded
 * text tracks, and a subtitle file loaded mid-cast stays on the phone, so a cast can never fail
 * for its subtitles' sake. The work for them stays behind this switch.
 */
const val CAST_SUBTITLES = true
