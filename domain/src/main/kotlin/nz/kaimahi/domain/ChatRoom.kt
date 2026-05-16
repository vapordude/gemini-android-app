package nz.kaimahi.domain

/**
 * A multi-seat chat room. Each [ChatRoomSeat] owns its own [GeminiCore]
 * (local Gemma 4, or remote Gemini via OAuth / API key), bound to a
 * persona that supplies the system prompt + sampling defaults.
 *
 * The turn policy decides who responds next. The shared message log is
 * append-only and visible to every seat.
 */
data class ChatRoom(
    val id: String,
    val name: String,
    val seats: List<ChatRoomSeat>,
    val policy: TurnPolicy = TurnPolicy.MENTION_OR_ROUND_ROBIN,
)

data class ChatRoomSeat(
    val seatId: String,
    val persona: Persona,
    /** Driver this seat uses. Null means "follow the global DriverMode". */
    val driverHint: DriverPreference = persona.driverPreference,
)

enum class TurnPolicy {
    /** The next seat in the list responds. */
    ROUND_ROBIN,
    /** A seat responds only when @mentioned. */
    MENTIONED_ONLY,
    /**
     * Mention takes priority; if no mention, fall back to round robin.
     * This is the default for the most natural reading experience.
     */
    MENTION_OR_ROUND_ROBIN,
    /** Every seat responds to every user message. */
    EVERYONE,
}

/**
 * One message in the room. `seatId == null` means a user message.
 */
data class RoomMessage(
    val id: String,
    val seatId: String?,        // null = human user
    val text: String,
    val timestamp: Long,
    val attachmentPaths: List<String> = emptyList(),
)
