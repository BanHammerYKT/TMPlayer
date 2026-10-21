package com.tmplayer.data

/**
 * Where TDLib's line to Telegram stands, as its `updateConnectionState` says.
 *
 * [Starting] is before the client has said anything at all, which is also what a client that has
 * just been replaced is.
 */
enum class TelegramLink {
    Starting,
    WaitingForNetwork,
    ConnectingToProxy,
    Connecting,
    Updating,
    Ready,
    ;

    /** Requests are answered from [Updating] on, so both count as connected (see [Td.connected]). */
    val isUp: Boolean get() = this == Updating || this == Ready

    companion object
}
