package nz.kaimahi.app.ui.room

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import nz.kaimahi.bridge.RestGeminiCore
import nz.kaimahi.bridge.persona.PersonaStore
import nz.kaimahi.bridge.room.ChatRoomEngine
import nz.kaimahi.domain.ChatRoom
import nz.kaimahi.domain.ChatRoomSeat
import nz.kaimahi.domain.GeminiCore
import nz.kaimahi.domain.Persona
import nz.kaimahi.domain.RoomMessage
import nz.kaimahi.domain.TurnPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the multi-model chat room. Holds the persona store + the
 * room engine; the UI just observes messages + seats and pushes user
 * input through [send].
 *
 * Per-seat driver routing: each [ChatRoomSeat] resolves to a [GeminiCore]
 * via [coreFor]; the host (MainActivity) passes a lambda that picks the
 * right driver per the seat's [Persona.driverPreference] — currently we
 * just use the routed [GeminiCore] for every seat, but the indirection
 * lets future work pin a seat to local-only or remote-only.
 */
class RoomViewModel(
    context: Context,
    private val routed: GeminiCore,
    @Suppress("unused") private val core: RestGeminiCore,
) : ViewModel() {

    private val personaStore = PersonaStore(context)

    private val _personas = MutableStateFlow(personaStore.list())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    private val _activeSeats = MutableStateFlow<List<ChatRoomSeat>>(emptyList())
    val activeSeats: StateFlow<List<ChatRoomSeat>> = _activeSeats.asStateFlow()

    private val _policy = MutableStateFlow(TurnPolicy.MENTION_OR_ROUND_ROBIN)
    val policy: StateFlow<TurnPolicy> = _policy.asStateFlow()

    private val _messages = MutableStateFlow<List<RoomMessage>>(emptyList())
    val messages: StateFlow<List<RoomMessage>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    @Volatile private var engine: ChatRoomEngine? = null

    /** Add [persona] as a new seat. Idempotent — a persona can only seat once. */
    fun addSeat(persona: Persona) {
        if (_activeSeats.value.any { it.persona.id == persona.id }) return
        val seat = ChatRoomSeat(
            seatId = UUID.randomUUID().toString(),
            persona = persona,
        )
        _activeSeats.value = _activeSeats.value + seat
        rebuildEngine()
    }

    fun removeSeat(seatId: String) {
        _activeSeats.value = _activeSeats.value.filterNot { it.seatId == seatId }
        rebuildEngine()
    }

    fun setPolicy(policy: TurnPolicy) {
        _policy.value = policy
        rebuildEngine()
    }

    fun send(text: String) {
        val eng = engine ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            _isSending.value = true
            try {
                eng.send(text)
                _messages.value = eng.messages.value
            } finally {
                _isSending.value = false
            }
        }
    }

    fun reset() {
        engine?.reset()
        _messages.value = emptyList()
    }

    private fun rebuildEngine() {
        if (_activeSeats.value.isEmpty()) {
            engine = null
            _messages.value = emptyList()
            return
        }
        val room = ChatRoom(
            id = "live",
            name = "Live room",
            seats = _activeSeats.value,
            policy = _policy.value,
        )
        // Every seat shares the routed GeminiCore today — the router will
        // pick local vs remote per turn based on prompt sensitivity. Future
        // work: dispatch by seat.driverPreference (LOCAL vs REMOTE) so a
        // "secrets advisor" persona can be pinned local regardless of
        // content.
        engine = ChatRoomEngine(room) { _ -> routed }
        _messages.value = engine?.messages?.value.orEmpty()
    }
}
