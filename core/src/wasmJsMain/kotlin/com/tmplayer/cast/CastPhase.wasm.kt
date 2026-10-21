package com.tmplayer.cast

/**
 * What the cast screen says the TV is doing (core/src/commonMain/.../cast/CastPlayback.kt). The
 * browser casts through its own Remote Playback and AirPlay rather than the apps' cast server, so
 * of that file only the phases the shared cast screen names come along, word for word.
 */
enum class CastPhase { Starting, Loading, Buffering, Playing, Paused, Ended, Stopped, Failed }
