package nz.kaimahi.bridge.room

import nz.kaimahi.domain.ChatRoom
import nz.kaimahi.domain.ChatRoomSeat
import nz.kaimahi.domain.GeminiCore
import nz.kaimahi.domain.GeminiResult
import nz.kaimahi.domain.RoomMessage
import nz.kaimahi.domain.TurnPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Drives a multi-seat conversation. The engine owns the message log + a
 * per-seat [GeminiCore] (passed in by [coreFor]; the host can wire it to
 * the local Rust driver or a remote OAuth/API-key instance).
 *
 * Single-thread invariant: turns are taken via [send] and serialized through
 * a mutex so the log can never have two responses interleave.
 */
class ChatRoomEngine(
    val room: ChatRoom,
    /** Returns the [GeminiCore] to use for [seat]'s next turn. */
    private val coreFor: (seat: ChatRoomSeat) -> GeminiCore,
) {
    private val _messages = MutableStateFlow<List<RoomMessage>>(emptyList())
    val messages: StateFlow<List<RoomMessage>> = _messages.asStateFlow()

    private val lock = Mutex()
    @Volatile private var rrIndex = 0

    /**
     * Send a user message and let the turn policy pick which seats respond.
     * Each respondent's reply is appended to the log in order. Returns the
     * list of new messages that were added (user + responses).
     */
    suspend fun send(userText: String): List<RoomMessage> = lock.withLock {
        val added = mutableListOf<RoomMessage>()
        val userMsg = RoomMessage(
            id = UUID.randomUUID().toString(),
            seatId = null,
            text = userText,
            timestamp = System.currentTimeMillis(),
        )
        _messages.value = _messages.value + userMsg
        added += userMsg

        val turn = pickRespondents(userText)
        for (seat in turn) {
            val core = coreFor(seat)
            // Tell the driver the seat's persona via the proper interface
            // method; the user text stays untouched. The driver builds the
            // final system instruction with the persona prepended.
            runCatching { core.setSystemPrompt(seat.persona.systemPrompt) }
            val r = runCatching { core.sendMessage(userText) }
                .getOrElse { GeminiResult.Error(it.message ?: "send failed") }
            val text = when (r) {
                is GeminiResult.Success -> r.response
                is GeminiResult.Error -> "(error: ${r.message})"
            }
            val response = RoomMessage(
                id = UUID.randomUUID().toString(),
                seatId = seat.seatId,
                text = text,
                timestamp = System.currentTimeMillis(),
            )
            _messages.value = _messages.value + response
            added += response
        }
        added
    }

    private fun pickRespondents(text: String): List<ChatRoomSeat> {
        val mentions = mentionedSeats(text)
        return when (room.policy) {
            TurnPolicy.ROUND_ROBIN -> listOf(nextRoundRobin())
            TurnPolicy.MENTIONED_ONLY -> mentions
            TurnPolicy.MENTION_OR_ROUND_ROBIN ->
                if (mentions.isNotEmpty()) mentions else listOf(nextRoundRobin())
            TurnPolicy.EVERYONE -> room.seats
        }
    }

    private fun nextRoundRobin(): ChatRoomSeat {
        if (room.seats.isEmpty()) error("Room has no seats")
        val i = rrIndex % room.seats.size
        rrIndex = (rrIndex + 1) % room.seats.size
        return room.seats[i]
    }

    private fun mentionedSeats(text: String): List<ChatRoomSeat> {
        val lower = text.lowercase()
        return room.seats.filter { seat ->
            // Match @name, case-insensitive. Treat spaces in names as hyphens
            // so "@dungeon-master" matches the "Dungeon Master" persona.
            val handle = "@" + seat.persona.name.lowercase().replace(' ', '-')
            lower.contains(handle)
        }
    }

    /** Reset the room. Per-seat session state is the caller's responsibility. */
    fun reset() {
        _messages.value = emptyList()
        rrIndex = 0
    }
}
