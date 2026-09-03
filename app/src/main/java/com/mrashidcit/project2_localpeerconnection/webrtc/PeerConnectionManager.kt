package com.mrashidcit.project2_localpeerconnection.webrtc

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * PeerConnectionManager - PART 3, 4, 5, 7-14 of the learning project.
 *
 * ============================================================================
 * WHAT IS A PeerConnection?
 * ============================================================================
 * A `PeerConnection` represents ONE ENDPOINT of a single WebRTC session - it
 * is "my side of the call". It owns:
 *  - The SDP negotiation state machine (local description, remote
 *    description, signaling state).
 *  - The ICE agent (gathers local network candidates, tries candidate pairs
 *    against the other side's candidates, picks the best working pair).
 *  - The encrypted transport (DTLS-SRTP) once a pair is selected.
 *  - Zero or more senders (outgoing tracks) and receivers (incoming tracks).
 *
 * ============================================================================
 * WHY TWO PeerConnections IN ONE APP?
 * ============================================================================
 * In a real call, Device A has ONE PeerConnection object representing "my
 * connection to the other person", and Device B, running on a different
 * phone, has its OWN separate PeerConnection object representing "my
 * connection to the other person" from ITS point of view. Neither device
 * ever sees the other device's PeerConnection object - they only exchange
 * small text (SDP) and JSON-ish blobs (ICE candidates) through a signaling
 * channel.
 *
 * This class is instantiated twice by the ViewModel - once "as Peer A", once
 * "as Peer B" - specifically so that Peer A ≠ Peer B: they are two separate
 * native PeerConnection objects, each with its own state machine, each
 * unaware that the other happens to be running in the same Android process.
 * The only thing simulating "the network" between them is the ViewModel
 * manually copying SDP objects and IceCandidate objects from one
 * PeerConnectionManager's output to the other's input - see
 * LocalPeerConnectionViewModel for that hand-off code, which stands in for a
 * signaling server in a real app.
 *
 * ============================================================================
 * PART 4 - PeerConnection.RTCConfiguration / IceServer
 * ============================================================================
 * `PeerConnection.RTCConfiguration` configures how THIS PeerConnection's ICE
 * agent behaves. Its most important field is `iceServers`: a list of STUN/
 * TURN servers used to discover a candidate's public address (STUN) or relay
 * media through a third party when a direct path is impossible (TURN).
 *
 * This project passes an EMPTY list of ICE servers on purpose. Both
 * PeerConnections run inside the SAME Android process on the SAME device, so
 * the ICE agent only needs to find "host" candidates - addresses bound
 * directly to the device's own network interfaces (Wi-Fi IP, or loopback).
 * There is no NAT to see through and nothing external to reach, so STUN
 * (which exists purely to discover a "server-reflexive" address behind a
 * NAT) and TURN (which exists purely to relay media when no direct path
 * exists) add nothing here. A later, real multi-device project is where
 * `iceServers` starts to matter.
 */
class PeerConnectionManager(
    private val label: String,
    factory: PeerConnectionFactory,
    rtcConfig: PeerConnection.RTCConfiguration,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onIceConnectionStateChanged: (PeerConnection.IceConnectionState) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit,
    private val onRemoteVideoTrack: (VideoTrack) -> Unit,
    private val onRemoteAudioTrack: (AudioTrack) -> Unit,
    private val log: (String) -> Unit
) {

    /**
     * PART 5 - PeerConnection.Observer.
     *
     * This is how the (native, C++) PeerConnection talks back to our Kotlin
     * code. Every callback below fires on WebRTC's own "signaling thread",
     * asynchronously, whenever something changes inside the native engine -
     * we never call these ourselves.
     */
    private val observer = object : PeerConnection.Observer {

        /**
         * Fires whenever the SDP negotiation state machine changes, e.g.
         * STABLE -> HAVE_LOCAL_OFFER after createOffer()+setLocalDescription(),
         * then back to STABLE once the remote answer is set. Mostly useful
         * for debugging "why won't my negotiation proceed" issues.
         */
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            log("[WebRTC][$label] Signaling state: $newState")
        }

        /**
         * The LEGACY (pre-Unified-Plan-standard) ICE connection state, e.g.
         * NEW -> CHECKING -> CONNECTED -> COMPLETED. Still useful for logs;
         * `onConnectionChange` below is the more modern, more complete signal
         * to actually drive UI off of.
         */
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            log("[WebRTC][$label] ICE connection state: $newState")
            newState?.let(onIceConnectionStateChanged)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            log("[WebRTC][$label] ICE connection receiving data: $receiving")
        }

        /**
         * NEW -> GATHERING -> COMPLETE. COMPLETE means "this peer has
         * finished discovering every local candidate it is going to find" -
         * it does NOT mean the peers are connected to each other, only that
         * one side is done looking for its own addresses.
         */
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            log("[WebRTC][$label] ICE gathering state: $newState")
        }

        /**
         * *** One of the most important callbacks in this whole project. ***
         *
         * Fires once for every local ICE candidate the ICE agent discovers,
         * starting right after `setLocalDescription()` is called. Each
         * `IceCandidate` describes one possible way to reach THIS peer
         * (an IP address + port + transport protocol). In a real app you
         * would immediately send this candidate to the other peer through
         * your signaling channel. Here, we hand it to
         * `onIceCandidateGenerated`, which the ViewModel uses to simply
         * queue it up until the "Exchange ICE" step manually delivers it
         * to the other PeerConnectionManager's `addRemoteIceCandidate()`.
         */
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            log("[WebRTC][$label] ICE candidate generated")
            onIceCandidateGenerated(candidate)
        }

        /** Rare: fires if previously-announced candidates become invalid (e.g. a network interface disappeared). */
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            log("[WebRTC][$label] ICE candidates removed: ${candidates?.size ?: 0}")
        }

        /**
         * Legacy Plan-B-era callback for "a whole remote MediaStream showed
         * up". Modern WebRTC (Unified Plan, which this whole project uses
         * via `addTrack`) delivers new remote tracks through `onAddTrack`
         * below instead, one track at a time. We still implement this
         * because `PeerConnection.Observer` requires it, but you should not
         * expect it to fire in this project.
         */
        override fun onAddStream(stream: MediaStream?) {
            log("[WebRTC][$label] onAddStream fired (legacy callback, not used by this project's Unified Plan flow)")
        }

        override fun onRemoveStream(stream: MediaStream?) {
            log("[WebRTC][$label] onRemoveStream fired")
        }

        /** We never create a DataChannel in this project, so this should never fire - implemented because the interface requires it. */
        override fun onDataChannel(dataChannel: DataChannel?) {
            log("[WebRTC][$label] onDataChannel fired (unexpected - this project creates no data channels)")
        }

        /**
         * Fires when the set of senders/receivers changes in a way that
         * requires a brand-new offer/answer round (e.g. calling addTrack()
         * after the connection is already established). In this project we
         * add all local tracks to Peer A BEFORE creating the first offer, so
         * the initial offer already describes them and this fires at most
         * once, harmlessly, right after `addTrack()`.
         */
        override fun onRenegotiationNeeded() {
            log("[WebRTC][$label] Renegotiation needed")
        }

        /**
         * *** The other critical callback. ***
         *
         * Fires on the RECEIVING side once a remote track has been
         * negotiated in and starts flowing. `receiver.track()` is the actual
         * `MediaStreamTrack` - we check whether it is a `VideoTrack` or
         * `AudioTrack` and forward it upward so it can be rendered
         * (video, via SurfaceViewRenderer) or simply left alone (audio,
         * which WebRTC plays through the device speaker automatically once
         * the track is enabled - no manual "renderer" needed for audio).
         */
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            log("[WebRTC][$label] onAddTrack fired, track kind=${track.kind()}")
            when (track) {
                is VideoTrack -> onRemoteVideoTrack(track)
                is AudioTrack -> onRemoteAudioTrack(track)
            }
        }

        /**
         * The modern, AGGREGATED connection state - combines ICE transport
         * state AND DTLS (encryption handshake) state into one value:
         * NEW -> CONNECTING -> CONNECTED -> (DISCONNECTED | FAILED | CLOSED).
         * This is the callback this project's UI is actually driven from
         * (see PART 13 - Connection State).
         */
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            log("[WebRTC][$label] Peer connection state: $newState")
            newState?.let(onConnectionStateChanged)
        }
    }

    /**
     * PART 3 - creating the actual native PeerConnection. `createPeerConnection`
     * can return null if WebRTC fails to construct the native object (e.g. bad
     * configuration) - we fail loudly instead of silently proceeding with a
     * broken peer, per the project's error-handling requirements.
     */
    val peerConnection: PeerConnection = requireNotNull(
        factory.createPeerConnection(rtcConfig, observer)
    ) { "Peer $label: PeerConnectionFactory.createPeerConnection() returned null" }

    /**
     * PART 6 - "addTrack() attaches a local media track to this
     * PeerConnection". Conceptually, a MediaStreamTrack (VideoTrack or
     * AudioTrack) is a single, continuous flow of media samples (frames or
     * audio buffers) with a kind ("video"/"audio") and an enabled/muted
     * state. `addTrack()` tells THIS PeerConnection "start describing and
     * sending this track to whoever I negotiate with" - it creates an
     * internal RtpSender + RtpTransceiver and is exactly what makes the
     * track show up as an `m=video`/`m=audio` line in the next SDP offer.
     *
     * `streamIds` groups tracks that should be treated as originating from
     * the same logical source (useful for A/V sync on the receiving side) -
     * we use one shared stream id for both the video and audio track we add
     * to Peer A.
     */
    fun addLocalVideoTrack(videoTrack: VideoTrack, streamId: String) {
        peerConnection.addTrack(videoTrack, listOf(streamId))
        log("[WebRTC][$label] Local video track added to peer connection")
    }

    fun addLocalAudioTrack(audioTrack: AudioTrack, streamId: String) {
        peerConnection.addTrack(audioTrack, listOf(streamId))
        log("[WebRTC][$label] Local audio track added to peer connection")
    }

    /**
     * PART 7 - "What happens inside createOffer()?"
     *
     * `createOffer()` does NOT change any state by itself - it only INSPECTS
     * the tracks currently added via `addTrack()` and produces a
     * `SessionDescription` (an SDP text blob) describing: which media kinds
     * this peer wants to send/receive, which codecs it supports for each
     * kind (in preference order), the ICE username/password fragment this
     * peer will use, and the DTLS fingerprint for its certificate. The
     * result is handed back through the SdpObserver callback, not returned
     * directly - `createOffer` is asynchronous because building the codec
     * list can involve querying hardware codec capabilities.
     *
     * We wrap that callback API in `suspendCancellableCoroutine` so the
     * ViewModel can simply write:
     *     val offer = peerConnectionA.createOffer()
     * as a normal, linear, one-line suspend call instead of nesting
     * callbacks - this keeps the 18-step WebRTC flow required by the
     * project fully visible and readable in the ViewModel.
     */
    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { continuation ->
        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    continuation.resume(sdp)
                } else {
                    continuation.resumeWithException(IllegalStateException("Peer $label: createOffer succeeded but returned a null SessionDescription"))
                }
            }
            override fun onCreateFailure(error: String?) {
                continuation.resumeWithException(IllegalStateException("Peer $label: createOffer failed: $error"))
            }
            // Not relevant to createOffer - only onCreateSuccess/onCreateFailure fire for it.
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    /**
     * PART 11 - createAnswer() is the mirror image of createOffer(): it also
     * only produces a SessionDescription, but this time WebRTC builds it by
     * matching what the peer wants to send/receive against what the just-set
     * remote OFFER asked for (e.g. picking one codec out of the offer's list
     * that both sides support). createAnswer() can only be called AFTER
     * `setRemoteDescription(offer)` has succeeded.
     */
    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { continuation ->
        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    continuation.resume(sdp)
                } else {
                    continuation.resumeWithException(IllegalStateException("Peer $label: createAnswer succeeded but returned a null SessionDescription"))
                }
            }
            override fun onCreateFailure(error: String?) {
                continuation.resumeWithException(IllegalStateException("Peer $label: createAnswer failed: $error"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    /**
     * PART 8 - setLocalDescription().
     *
     * `createOffer()` only BUILDS a SessionDescription in memory - it does
     * not change the PeerConnection's own state at all. `setLocalDescription()`
     * is the call that actually COMMITS that description as "this is what
     * I, this peer, am proposing/have agreed to". Only after this call does
     * the PeerConnection's signaling state advance (STABLE -> HAVE_LOCAL_OFFER)
     * and does the ICE agent start gathering local candidates for this
     * session. In other words: `Offer` is just a value; `Local Description`
     * is that value applied to this specific PeerConnection.
     */
    suspend fun setLocalDescription(sessionDescription: SessionDescription): Unit =
        suspendCancellableCoroutine { continuation ->
            peerConnection.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    continuation.resume(Unit)
                }
                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException("Peer $label: setLocalDescription failed: $error"))
                }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sessionDescription)
        }

    /**
     * PART 10 - setRemoteDescription().
     *
     * The counterpart to setLocalDescription(): it tells THIS PeerConnection
     * "here is what the OTHER side is proposing/has agreed to". Both a local
     * AND a remote description are required before media can flow, because
     * negotiation is inherently two-sided: this peer needs to know both
     * "what I offered" and "what the other side answered" to compute the
     * final, agreed-upon set of codecs/directions/transport parameters.
     */
    suspend fun setRemoteDescription(sessionDescription: SessionDescription): Unit =
        suspendCancellableCoroutine { continuation ->
            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    continuation.resume(Unit)
                }
                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException("Peer $label: setRemoteDescription failed: $error"))
                }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sessionDescription)
        }

    /**
     * PART 12 - addIceCandidate().
     *
     * SDP negotiation (offer/answer) tells both peers WHAT they want to
     * communicate (codecs, media kinds, directions). It says nothing about
     * HOW the bytes will actually travel over the network. ICE is the "how":
     * each side gathers a list of its own possible network addresses
     * (candidates) via `onIceCandidate` above, and `addIceCandidate()` is
     * how you feed the OTHER side's candidates into THIS PeerConnection's
     * ICE agent. Once a PeerConnection has both a remote description and at
     * least one usable remote candidate, its ICE agent starts sending
     * connectivity checks (STUN binding requests, in this case sent
     * directly device-to-network-interface since we configured no STUN/TURN
     * servers) to find a candidate PAIR (one local + one remote address)
     * that actually works. That is the pair `onConnectionChange` reports as
     * CONNECTED once it is found.
     *
     * This uses the classic, synchronous-style overload
     * (`addIceCandidate(IceCandidate): Boolean`, returning whether it was
     * accepted) rather than the newer callback-based
     * `addIceCandidate(IceCandidate, AddIceObserver)` overload, since a
     * single boolean result is all this project needs and it keeps the call
     * site a plain one-liner.
     */
    fun addRemoteIceCandidate(candidate: IceCandidate) {
        val wasAdded = peerConnection.addIceCandidate(candidate)
        log("[WebRTC][$label] addIceCandidate() -> accepted=$wasAdded")
    }

    /**
     * PART 19 - close() vs dispose(). `close()` gracefully tears down the
     * active session (stops sending/receiving media, closes ICE/DTLS
     * transports) but keeps the Kotlin/native object alive enough to answer
     * a few more queries. `dispose()` actually frees the underlying native
     * memory - after dispose(), this object must not be used again. We
     * always call close() first, then dispose().
     */
    fun close() {
        peerConnection.close()
        log("[WebRTC][$label] PeerConnection closed")
    }

    fun dispose() {
        peerConnection.dispose()
        log("[WebRTC][$label] PeerConnection disposed")
    }
}
