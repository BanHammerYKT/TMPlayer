package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.SearchMessagesFilterAudio
import dev.g000sha256.tdl.dto.SearchMessagesFilterVoiceNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * A chat's audio: music files, talks and lectures posted as audio, and voice messages, played
 * with the same player as a playlist ([PlayQueue]). Telegram keeps the two kinds apart, so each
 * is searched with its own filter and keeps its own place.
 */
object ChatAudio {

    /** One page of a chat's audio, newest first, with where each search got to. */
    data class Page(
        val items: List<MediaItem>,
        val nextAudio: Long,
        val nextVoice: Long,
        val audioDone: Boolean,
        val voiceDone: Boolean,
    ) {
        val done: Boolean get() = audioDone && voiceDone
    }

    /** How many audio files and voice messages [chatId] holds, from TDLib's count; 0 when unknown. */
    suspend fun count(td: TdlClient, chatId: Long): Int = withContext(Dispatchers.IO) {
        suspend fun one(filter: dev.g000sha256.tdl.dto.SearchMessagesFilter): Int {
            val local = td.getChatMessageCount(chatId, null, filter, true).valueOrNull?.count
            if (local != null && local >= 0) return local
            return td.getChatMessageCount(chatId, null, filter, false).valueOrNull?.count?.coerceAtLeast(0) ?: 0
        }
        // Side by side: either may be a round trip to the server, and neither waits on the other.
        coroutineScope {
            val audio = async { one(SearchMessagesFilterAudio()) }
            val voice = async { one(SearchMessagesFilterVoiceNote()) }
            audio.await() + voice.await()
        }
    }

    /** The next page after [after], or the first with null. */
    suspend fun page(td: TdlClient, chatId: Long, after: Page? = null, limit: Int = PAGE): Page = withContext(Dispatchers.IO) {
        coroutineScope {
            val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
            val audio = async {
                if (after?.audioDone == true) null
                else td.searchChatMessages(chatId, null, "", null, after?.nextAudio ?: 0, 0, limit, SearchMessagesFilterAudio()).valueOrNull
            }
            val voice = async {
                if (after?.voiceDone == true) null
                else td.searchChatMessages(chatId, null, "", null, after?.nextVoice ?: 0, 0, limit, SearchMessagesFilterVoiceNote()).valueOrNull
            }
            val a = audio.await()
            val v = voice.await()
            val items = (a?.messages.orEmpty().toList() + v?.messages.orEmpty().toList())
                .mapNotNull { itemOf(it, chatProtected) }
                .sortedByDescending { it.messageId }
            Page(
                items = items,
                nextAudio = a?.nextFromMessageId ?: after?.nextAudio ?: 0,
                nextVoice = v?.nextFromMessageId ?: after?.nextVoice ?: 0,
                audioDone = after?.audioDone == true || a == null || a.messages.isEmpty() || a.nextFromMessageId == 0L,
                voiceDone = after?.voiceDone == true || v == null || v.messages.isEmpty() || v.nextFromMessageId == 0L,
            )
        }
    }

    /** [message] as something the player can play, or null for anything but audio. */
    fun itemOf(message: Message, chatProtected: Boolean = false): MediaItem? {
        if (message.schedulingState != null || message.sendingState != null) return null
        if (message.selfDestructType != null) return null
        val saveable = !chatProtected && message.canBeSaved
        return when (val content = message.content) {
            is MessageAudio -> {
                val audio = content.audio
                val named = listOf(audio.performer, audio.title).filter { it.isNotBlank() }.joinToString(" · ")
                MediaItem(
                    chatId = message.chatId,
                    messageId = message.id,
                    fileId = audio.audio.id,
                    title = named.ifBlank { audio.fileName.substringBeforeLast('.').ifBlank { L.mediaUntitledAudio } },
                    fileName = audio.fileName,
                    sizeBytes = MediaMapper.fileSize(audio.audio.size, audio.audio.expectedSize),
                    durationSec = audio.duration,
                    mimeType = audio.mimeType.ifBlank { "audio/mpeg" },
                    thumbnailFileId = audio.albumCoverThumbnail?.file?.id ?: 0,
                    miniThumbnail = audio.albumCoverMinithumbnail?.data,
                    date = message.date,
                    onDevice = MediaMapper.onDevice(audio.audio),
                    caption = MediaMapper.captionOf(content.caption.text),
                    canBeSaved = saveable,
                )
            }
            is MessageVoiceNote -> {
                val voice = content.voiceNote
                MediaItem(
                    chatId = message.chatId,
                    messageId = message.id,
                    fileId = voice.voice.id,
                    title = MediaMapper.captionOf(content.caption.text).lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: L.mediaVoiceMessage,
                    sizeBytes = MediaMapper.fileSize(voice.voice.size, voice.voice.expectedSize),
                    durationSec = voice.duration,
                    mimeType = voice.mimeType.ifBlank { "audio/ogg" },
                    thumbnailFileId = 0,
                    miniThumbnail = null,
                    date = message.date,
                    onDevice = MediaMapper.onDevice(voice.voice),
                    caption = MediaMapper.captionOf(content.caption.text),
                    canBeSaved = saveable,
                )
            }
            else -> null
        }
    }

    /** Whether [item] is sound alone: the player shows its name and cover rather than a picture. */
    fun isAudio(item: MediaItem): Boolean = item.mimeType.startsWith("audio/", ignoreCase = true)

    const val PAGE = 50
}

/**
 * The list a chat's audio was started from, so the player goes on to the next file when one ends
 * (and back with Previous), the way it goes on to a show's next episode. Set when a file is played
 * from the list, and left alone by everything else: a video played afterwards is not in it.
 */
object PlayQueue {
    @Volatile
    var items: List<MediaItem> = emptyList()
        private set

    /** [list] in playing order, oldest first, as a course or an album is listened to. */
    fun set(list: List<MediaItem>) {
        items = list
    }

    fun clear() {
        items = emptyList()
    }

    /** The files either side of [item], or null when it was not started from the list. */
    fun stepsFor(item: MediaItem): EpisodeSteps? {
        val list = items
        val at = list.indexOfFirst { it.chatId == item.chatId && it.messageId == item.messageId }
        if (at < 0) return null
        return EpisodeSteps(previous = list.getOrNull(at - 1), next = list.getOrNull(at + 1))
    }
}
