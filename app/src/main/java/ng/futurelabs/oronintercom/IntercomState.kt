package ng.futurelabs.oronintercom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Everything the screen needs to show. The service writes it, the activity reads it. */
data class UiState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val directorOnline: Boolean = false,
    val voiceLive: Boolean = false,
    val talking: Boolean = false,
    val directorTalking: Boolean = false,
    val muted: Boolean = false,
    val latched: Boolean = false,
    val host: String = "",
    val error: String? = null,
    // Camera (video to an "Oron Camera" slot on the laptop)
    val cameraOn: Boolean = false,
    val cameraLive: Boolean = false,
    val cameraSlot: Int = 1,
    val cameraFront: Boolean = false,
    val cameraError: String? = null,
)

object IntercomState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun set(block: (UiState) -> UiState) = _ui.update(block)
    fun reset() { _ui.value = UiState() }
}
