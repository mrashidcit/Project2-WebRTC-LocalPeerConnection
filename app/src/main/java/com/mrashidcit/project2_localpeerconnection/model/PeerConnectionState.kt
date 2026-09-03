package com.mrashidcit.project2_localpeerconnection.model

/**
 * Our own, simplified connection-state model for the UI.
 *
 * WebRTC itself exposes several different state machines on a PeerConnection
 * (SignalingState, IceConnectionState, IceGatheringState, PeerConnectionState).
 * That is a lot of detail for a Compose screen to react to directly.
 *
 * Instead, we translate WebRTC's aggregated `PeerConnection.PeerConnectionState`
 * (the value delivered to `PeerConnection.Observer.onConnectionChange`) into this
 * small enum. `PeerConnection.PeerConnectionState` already has almost the same
 * cases (NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED) - we mirror
 * them here on purpose so the mapping in the ViewModel is a 1:1 rename, not a
 * lossy simplification. Keeping our own enum (instead of exposing the WebRTC
 * enum straight to Compose) means the UI layer does not need to import
 * `org.webrtc.*` at all, which keeps WebRTC details out of the UI as required
 * by the project brief.
 */
enum class PeerConnectionState {
    /** PeerConnection was just created; negotiation has not started yet. */
    NEW,

    /** ICE is checking candidate pairs, trying to find a route that works. */
    CONNECTING,

    /** At least one usable ICE candidate pair was found; media can flow. */
    CONNECTED,

    /** A previously working connection lost its route (e.g. Wi-Fi dropped). */
    DISCONNECTED,

    /** ICE checked every candidate pair and none of them worked. */
    FAILED,

    /** close() was called; the PeerConnection is fully torn down. */
    CLOSED
}
