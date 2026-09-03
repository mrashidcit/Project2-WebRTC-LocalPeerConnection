package com.mrashidcit.project2_localpeerconnection.presentation.call

import com.mrashidcit.project2_localpeerconnection.model.PeerConnectionState
import org.webrtc.VideoTrack

/**
 * PART 17 - everything the Compose screen needs to render, and nothing more.
 *
 * The Composable never talks to WebRtcManager/PeerConnectionManager directly
 * (see LocalPeerConnectionViewModel) - it only ever reads this data class and
 * calls plain functions like `onCreateOfferClicked()` on the ViewModel. That
 * is what keeps WebRTC implementation details out of the Composable, as
 * required by the project brief.
 *
 * `remoteVideoTrack` is the one intentional exception to "no WebRTC types in
 * the UI layer": the whole point of this screen is to render a live
 * `VideoTrack` on screen, and `RemoteVideoRenderer` (webrtc/WebRtcRenderer.kt)
 * needs the real `org.webrtc.VideoTrack` object to call `addSink()` on. Wrapping
 * it in another abstraction here would hide the exact WebRTC flow this
 * project exists to teach, which the brief explicitly asks us to avoid -
 * `hasRemoteVideo` is kept alongside it purely so simple UI logic (e.g. "show
 * a placeholder text") does not need to null-check a WebRTC type itself.
 */
data class LocalPeerConnectionUiState(
    val connectionState: PeerConnectionState = PeerConnectionState.NEW,
    val logs: List<String> = emptyList(),
    val hasRemoteVideo: Boolean = false,
    val remoteVideoTrack: VideoTrack? = null,
    val error: String? = null,
    val peersCreated: Boolean = false,
    val offerAnswerExchanged: Boolean = false,
    val pendingIceCandidateCount: Int = 0
)
