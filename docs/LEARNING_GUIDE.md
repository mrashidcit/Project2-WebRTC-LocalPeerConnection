# Project #2 — Local WebRTC PeerConnection — Learning Guide

This is the companion study document for the code in `app/src/main/java/com/mrashidcit/project2_localpeerconnection`. Read it alongside the code, not instead of it — every section below points at specific files.

Library used: `io.getstream:stream-webrtc-android:1.3.10` (classes still live under the `org.webrtc` package — it is a maintained, prebuilt drop-in replacement for the WebRTC AAR Google used to publish).

---

## 1. Project architecture

```
Composable (LocalPeerConnectionScreen)
     |  reads LocalPeerConnectionUiState, calls plain functions
     v
ViewModel (LocalPeerConnectionViewModel)
     |  owns two PeerConnectionManager instances + one WebRtcManager
     |  contains the entire 18-step offer/answer/ICE flow, in order
     v
WebRTC layer (webrtc/WebRtcManager.kt, webrtc/PeerConnectionManager.kt, webrtc/WebRtcRenderer.kt)
     |  every call into org.webrtc.* happens only here
     v
org.webrtc.* (the native WebRTC engine, via JNI)
```

File-by-file:

- **`model/PeerConnectionState.kt`** — our own small enum for UI connection state (NEW/CONNECTING/CONNECTED/DISCONNECTED/FAILED/CLOSED), mirroring `PeerConnection.PeerConnectionState` so the UI layer never has to import `org.webrtc.*`.
- **`webrtc/WebRtcManager.kt`** — PART 1 & 2. Owns the one-time `PeerConnectionFactory` init, the shared `EglBase`, and creates the local camera/mic tracks. One instance per app session.
- **`webrtc/PeerConnectionManager.kt`** — PART 3–5, 7–14. A thin wrapper around ONE `PeerConnection`. Instantiated twice (label `"A"`, label `"B"`) — that is what makes Peer A ≠ Peer B. Owns the `PeerConnection.Observer`, and exposes `createOffer()`/`createAnswer()`/`setLocalDescription()`/`setRemoteDescription()` as `suspend` functions plus `addRemoteIceCandidate()`.
- **`webrtc/WebRtcRenderer.kt`** — PART 14 & 16. One `@Composable` (`RemoteVideoRenderer`) that bridges `SurfaceViewRenderer` (a classic Android `View`) into Compose via `AndroidView`.
- **`presentation/call/LocalPeerConnectionUiState.kt`** — PART 17. The one data class the Composable reads.
- **`presentation/call/LocalPeerConnectionViewModel.kt`** — PART 9 & 17. The orchestrator. This is where the entire required 18-step sequence is written out, top to bottom, as plain suspend calls — read this file second, right after this guide.
- **`presentation/call/LocalPeerConnectionScreen.kt`** — PART 16. Compose UI: permission request, remote video box, connection-state text, five buttons, scrolling log list.
- **`MainActivity.kt`** — thin host, applies the theme, shows the screen.

---

## 2. What is PeerConnection?

A `PeerConnection` is one endpoint's view of a single call. Think of a telephone call: your handset is one `PeerConnection`, the other person's handset is a *different* `PeerConnection` — even though they are talking to each other, neither handset "is" the call, each is one side of it. It owns SDP negotiation state, the ICE agent, the encrypted (DTLS-SRTP) transport, and the list of tracks it is sending/receiving.

## 3. Why are there two PeerConnections?

Because this project simulates **two separate devices** inside one process. In a real call, Device A's `PeerConnection` and Device B's `PeerConnection` are two objects that never touch each other directly — they only exchange small text messages (SDP, ICE candidates) via a signaling server. Here there is no second device and no server, so `LocalPeerConnectionViewModel` plays the role of "the network + the signaling server" by manually copying the `SessionDescription`/`IceCandidate` objects that `peerConnectionA` (Peer A ≠ Peer B — two distinct native objects, two distinct `PeerConnectionManager` instances) produces into `peerConnectionB`'s input methods, and vice versa.

## 4. What is PeerConnectionFactory?

The root object of the whole native WebRTC stack — see the full doc comment at the top of `WebRtcManager.kt`. Everything else (PeerConnections, VideoSource, AudioSource, VideoTrack, AudioTrack) is built BY it. `PeerConnectionFactory.initialize(...)` is a static, process-wide call meant to run exactly once per process; `WebRtcManager` guards it with a `@Volatile` flag so a second call anywhere in the app just logs and skips instead of repeating native setup.

## 5. What is a MediaStreamTrack?

The base type for `VideoTrack` and `AudioTrack` — a single, continuous flow of media samples (video frames or audio buffers) with a `kind()` ("video"/"audio") and an enabled/disabled state. A track by itself does nothing; it only becomes useful once it's either attached to a `PeerConnection` via `addTrack()` (to send it) or attached to a sink like `SurfaceViewRenderer` via `addSink()` (to render it).

- `VideoTrack`: backed by a `VideoSource`, which is fed frames by a `VideoCapturer` (here, `CameraVideoCapturer` via `Camera2Enumerator`).
- `AudioTrack`: backed by an `AudioSource`, which WebRTC's internal audio device module feeds directly from the microphone — there is no separate "AudioCapturer" object in the public API.

## 6. What is SDP?

SDP (Session Description Protocol) is a text format that describes a proposed (or agreed) media session — NOT the media itself, just the *description* of it. One `SessionDescription` object, once you print its `.description` string, contains lines describing:

- which media kinds are involved (`m=audio`, `m=video`)
- which codecs are offered/accepted for each, in preference order (e.g. VP8, VP9, H264, Opus)
- the direction of each media line (`sendrecv`, `sendonly`, `recvonly`, `inactive`)
- transport/security info: the ICE username fragment + password, and the DTLS certificate fingerprint each side will use once a candidate pair is found
- grouping information tying related media lines together (`a=group:BUNDLE`)

Think of it as a very detailed "menu" one peer hands the other: "here is everything I could send you and everything I'm willing to receive — pick what works for you."

## 7. What is an Offer?

The FIRST `SessionDescription` in a negotiation, produced by `createOffer()` on the peer that starts the call (Peer A here). It lists everything Peer A is *proposing*: media kinds, every codec it supports for each, and its own transport credentials. Producing an offer inspects the tracks already added via `addTrack()` — that's why PART 6 (adding tracks to Peer A) must happen before PART 7 (creating the offer).

## 8. What is an Answer?

The SECOND `SessionDescription`, produced by `createAnswer()` on the peer that received the offer (Peer B here), and only callable after that peer has called `setRemoteDescription(offer)`. It is the *response*: for each media line in the offer, WebRTC picks the best codec both sides actually support, and decides a direction based on what Peer B itself is offering back (Peer B has no local tracks in this project, so its answer ends up `recvonly` for both media lines even though Peer A's offer was `sendrecv`).

## 9. What is Local Description?

The `SessionDescription` a PeerConnection has committed to as "what I, this peer, am proposing/have agreed to." `createOffer()`/`createAnswer()` only *build* a `SessionDescription` value in memory — nothing changes on the PeerConnection until you call `setLocalDescription(sdp)`. Only after that call does the signaling state advance and does ICE gathering start for that peer.

## 10. What is Remote Description?

The `SessionDescription` a PeerConnection has been told the OTHER side is proposing/has agreed to, via `setRemoteDescription(sdp)`. A PeerConnection needs BOTH a local and a remote description before it can finish negotiating, because the final agreed-upon parameters (codec, direction, transport) are only computable once you know both "what I offered/answered" and "what they offered/answered."

## 11. What is ICE?

SDP negotiation (offer/answer) settles **what** the two peers want to communicate — media kinds, codecs, directions. It says nothing about **how** the bytes will actually get from one peer to the other over a real network. ICE (Interactive Connectivity Establishment) is the "how": each peer gathers a list of its own possible network addresses (candidates), the two lists get exchanged, and each peer's ICE agent tries every local×remote pair until it finds one that actually works (a STUN connectivity check succeeds).

## 12. What is an ICE Candidate?

One single possible address+port+protocol a peer could be reached at — e.g. "UDP, 192.168.1.14:53202, host candidate." A peer usually discovers several: one per network interface, plus (with STUN/TURN configured) server-reflexive and relay candidates. This project only ever sees **host** candidates, because both PeerConnections run on the same device with no STUN/TURN configured — see PART 4's explanation in `PeerConnectionManager.kt`.

## 13. Why do candidates need to be exchanged?

Because each peer only knows its OWN addresses — Peer A has no idea what addresses Peer B is reachable at, and vice versa, until they tell each other. `addIceCandidate()` is literally "here is one more address you can try reaching me at." Without exchanging candidates, `onIceConnectionChange` would sit at `CHECKING` forever with nothing to check against.

---

## 14. What happens inside `createOffer()`?

It does not touch the network or change any PeerConnection state. It looks at the tracks already added via `addTrack()`, builds the codec list WebRTC's engine supports for each media kind (querying hardware codec capabilities where relevant), generates this peer's ICE username fragment/password and DTLS fingerprint, and hands the resulting `SessionDescription` back through the `SdpObserver.onCreateSuccess` callback (wrapped as a `suspend` function in `PeerConnectionManager.createOffer()`).

## 15. What happens inside `createAnswer()`?

Same shape as `createOffer()`, but it can only run after `setRemoteDescription(offer)` succeeded, and it builds its result by intersecting what THIS peer supports/wants against what the remote OFFER asked for — e.g. picking one mutually-supported codec per media line, and computing a direction (Peer B ends up `recvonly` here, since it added no local tracks).

## 16. What happens after `setRemoteDescription()`?

The PeerConnection now has enough information (its own local description + the other side's remote description) to finish computing the negotiated session. Internally, transceivers/receivers matching each remote media line are created (this is what eventually fires `onAddTrack` once media actually starts flowing), and — if a local description is also already set — the ICE agent starts pairing local candidates against whatever remote candidates it has been given via `addIceCandidate()`.

## 17. What happens when `onIceCandidate()` fires?

The native ICE agent just discovered one more local address this peer could be reached at. In this project the callback stores it in a small in-memory queue (`candidatesFromA`/`candidatesFromB` in the ViewModel) rather than delivering it immediately — the actual `addIceCandidate()` call on the OTHER peer only happens when you press "Exchange ICE," which is a deliberate stand-in for the network delay real signaling always has between "I found a candidate" and "the other device received it."

## 18. What happens when the connection becomes CONNECTED?

`onConnectionChange(PeerConnection.PeerConnectionState.CONNECTED)` fires once the ICE agent has found at least one working candidate pair AND the DTLS handshake (encryption key exchange) over that pair has completed. From this point, encrypted media (and, if negotiated, data) can flow over that pair. In this project you will see this fire independently for Peer A and Peer B — the ViewModel combines both into one aggregated state for the UI (see `aggregateConnectionStates()`).

## 19. How does the remote video reach `SurfaceViewRenderer`?

```
Peer A's camera
   -> Peer A's local VideoTrack
   -> peerConnectionA.addTrack(videoTrack, ...)                  (PART 6)
   -> negotiated via the offer/answer exchange                   (PART 7-11)
   -> media flows once ICE connects                              (PART 12, 18)
   -> Peer B's PeerConnection.Observer.onAddTrack(receiver, ...)  (PART 14)
   -> receiver.track() as VideoTrack -> ViewModel's uiState.remoteVideoTrack
   -> LocalPeerConnectionScreen passes it to RemoteVideoRenderer
   -> videoTrack.addSink(surfaceViewRenderer)                    (webrtc/WebRtcRenderer.kt)
   -> SurfaceViewRenderer draws each VideoFrame with OpenGL
```

---

## 20. Complete sequence diagram

```
Peer A                                          Peer B
  |                                                |
  |  addTrack(videoTrack)                          |
  |  addTrack(audioTrack)                          |
  |                                                |
  |  createOffer()                                 |
  |  setLocalDescription(offer)                    |
  |  --- ICE gathering starts on A ---             |
  |                                                |
  |───────────────── Offer ──────────────────────► |
  |          (SIGNALING SIMULATION: ViewModel      |
  |           passes the same object directly)     |
  |                                                |  setRemoteDescription(offer)
  |                                                |  createAnswer()
  |                                                |  setLocalDescription(answer)
  |                                                |  --- ICE gathering starts on B ---
  |                                                |
  | ◄──────────────── Answer ──────────────────────|
  |          (SIGNALING SIMULATION, B -> A)         |
  |                                                |
  |  setRemoteDescription(answer)                  |
  |                                                |
  | ◄─────────── ICE candidate (B's) ───────────── |
  |───────────── ICE candidate (A's) ─────────────►|
  |     (both directions, "Exchange ICE" button)    |
  |                                                |
  |  addIceCandidate(B's candidates)               |  addIceCandidate(A's candidates)
  |  ICE connectivity checks run automatically      |
  |                                                |
  | ════════ DTLS handshake + encrypted media ═════|
  |                                                |
  | onConnectionChange(CONNECTED)     onConnectionChange(CONNECTED)
  |                                    onAddTrack(videoTrack) -> render
```

Step by step: Peer A prepares everything it wants to send (tracks) and describes it (offer), commits that description locally, and ICE starts looking for A's own addresses. The offer is handed to Peer B exactly as-is (no serialization needed since it's the same process) — that hand-off is the one thing standing in for a signaling server. Peer B records what A wants (`setRemoteDescription`), decides what it can actually do about it (`createAnswer`), commits its own answer, and its own ICE gathering starts. The answer travels back to A the same way. Now both sides have a local AND remote description, so both know the codecs/directions everyone agreed on — but they still don't know how to reach each other over the network, which is what the ICE candidate exchange (bottom half of the diagram) solves. Once each side has fed the other's candidates in via `addIceCandidate()`, WebRTC's ICE agent tries pairs on its own until one works, runs a DTLS handshake over it, and only then do the `onConnectionChange(CONNECTED)` and `onAddTrack` callbacks fire — completely automatically, with no further button presses required.

---

## 21. The 10-15 most important lines, explained

1. **`PeerConnectionFactory.initialize(options)`** (`WebRtcManager.initialize`) — the one-time native bootstrap everything else depends on. Get this wrong (call it twice with different options, or skip it) and every downstream WebRTC call misbehaves.
2. **`peerConnectionFactory.builder().setVideoEncoderFactory(...).setVideoDecoderFactory(...).createPeerConnectionFactory()`** — this is where hardware-accelerated codec support gets wired in via the shared `EglBase.Context`.
3. **`enumerator.createCapturer(cameraName, null)`** (`WebRtcManager.createFrontCameraCapturer`) — the bridge from Android's Camera2 API into WebRTC's `VideoCapturer` abstraction.
4. **`peerConnectionFactory.createVideoSource(capturer.isScreencast)`** then **`capturer.initialize(helper, appContext, source.capturerObserver)`** — wires the capturer's raw frames into WebRTC's pipeline; get the `capturerObserver` wiring wrong and you get a black remote video with no errors at all.
5. **`val rtcConfig = PeerConnection.RTCConfiguration(emptyList())`** (`LocalPeerConnectionViewModel.onCreatePeersClicked`) — the single line that encodes "no STUN/TURN needed for two peers on one device." Read the class doc above it before changing this.
6. **`val peerConnection = requireNotNull(factory.createPeerConnection(rtcConfig, observer))`** (`PeerConnectionManager`) — where the actual native PeerConnection object is born, tied to its `Observer` from the very first moment (you cannot attach an observer later).
7. **`peerConnection.addTrack(videoTrack, listOf(streamId))`** — the line that makes a captured camera feed actually show up in the next offer's SDP.
8. **`suspendCancellableCoroutine { continuation -> peerConnection.createOffer(object : SdpObserver {...}, MediaConstraints()) }`** — the callback-to-suspend bridge pattern, repeated for `createAnswer`/`setLocalDescription`/`setRemoteDescription`. Understanding this one lets you read all four.
9. **`peerB.setRemoteDescription(offer)`** right after **`appendLog("Offer transferred...")`** (`onCreateOfferClicked`) — this exact pair of lines IS the "signaling simulation": no network, no serialization, just handing the same Kotlin object to the other peer.
10. **`onIceCandidate(candidate: IceCandidate?) { ... onIceCandidateGenerated(candidate) }`** (`PeerConnectionManager`'s observer) — candidates are discovered continuously and asynchronously, independent of any button press.
11. **`candidatesFromA.add(candidate)`** vs. **`peerB.addRemoteIceCandidate(candidate)`** (in `onExchangeIceClicked`) — the split between *discovering* a candidate and *delivering* it is the crux of PART 12; these two lines are in different functions on purpose.
12. **`peerConnection.addIceCandidate(candidate)`** — the only line that actually feeds network reachability information into the ICE agent; everything before it was purely descriptive (SDP).
13. **`override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) { ... newState?.let(onConnectionStateChanged) }`** — the single callback this whole UI's connection-state text is ultimately driven from.
14. **`override fun onAddTrack(receiver: RtpReceiver?, ...) { when (track) { is VideoTrack -> onRemoteVideoTrack(track) ... } }`** — where a remote track crosses from "WebRTC's world" into "this app's world."
15. **`videoTrack?.addSink(view)`** (`WebRtcRenderer.RemoteVideoRenderer`'s `update` lambda) — the very last line in the whole pipeline: this is the call that makes pixels actually appear on screen.

---

## 22. Read this code in this order

1. This guide, sections 1-13 (concepts before code).
2. `model/PeerConnectionState.kt` — smallest file, orients you to the state model.
3. `webrtc/WebRtcManager.kt` — PeerConnectionFactory + local media, read the class doc comment fully.
4. `webrtc/PeerConnectionManager.kt` — the biggest concept file; read the class doc, then the `Observer`, then the suspend functions top to bottom.
5. `presentation/call/LocalPeerConnectionUiState.kt` — quick, tells you what the ViewModel is building toward.
6. `presentation/call/LocalPeerConnectionViewModel.kt` — the payoff: read `onCreatePeersClicked` -> `onCreateOfferClicked` -> `onCreateAnswerClicked` -> `onExchangeIceClicked` -> `onCloseClicked` in that order, matching the button order in the UI.
7. `webrtc/WebRtcRenderer.kt` — short, closes the loop on "where does the remote video actually render."
8. `presentation/call/LocalPeerConnectionScreen.kt` — last, since by now you already know what every button does.
9. `MainActivity.kt` — one screenful, mostly a lifecycle note.

Then run the app and, with `docs/LEARNING_GUIDE.md` section 20 (sequence diagram) open, press the buttons in order (Create Peers -> Create Offer -> Create Answer -> Exchange ICE) and match every log line that appears against a line in the diagram.

---

## 23. Exercises — modify the code yourself

Do these in order; each builds on the last. Don't copy an answer from anywhere — the point is to make a change and watch the logs/UI tell you whether your mental model was right.

1. **Add a local preview.** The current UI only shows Peer B's remote video. Add a second, small `RemoteVideoRenderer`-style box that renders Peer A's *own* `localVideoTrack` (hint: `VideoTrack.addSink()` works on a local track too, it doesn't care whether the track is local or remote — you'll need to expose `webRtcManager.localVideoTrack` up through the ViewModel).
2. **Make "Exchange ICE" happen automatically.** Instead of queueing candidates and waiting for a button press, call `addRemoteIceCandidate()` directly from `onIceCandidateGenerated` the instant a candidate is generated. Remove the "Exchange ICE" button. Does the connection still reach CONNECTED? (It should — this is closer to how a real trickle-ICE signaling channel behaves.)
3. **Log every ICE candidate's contents**, not just "a candidate was generated" — print `candidate.sdp` to the log list and read a few. Can you find the port number? The `typ host` marker?
4. **Make it bidirectional.** Add a camera+mic track to Peer B as well (a second `WebRtcManager` instance, or a second camera capturer sharing the factory), add them to `peerConnectionB` before the answer is created, and wire up `onRemoteVideoTrack`/`onRemoteAudioTrack` on Peer A's `PeerConnectionManager` (currently no-ops). You should end up with two remote video boxes, one per peer.
5. **Break it on purpose.** Comment out the `peerB.setRemoteDescription(offer)` call and try pressing "Create Answer." Read the exception message that surfaces in the error `Card` — does it match what PART 15/16 of this guide predicted `createAnswer()` requires?
6. **Simulate a disconnect.** After reaching CONNECTED, try disabling Wi-Fi/switching networks on the emulator/device and watch the logs for `DISCONNECTED`/`FAILED`. What real-world condition would need to happen for two peers on a real network to see the same transition?
7. **Force PLAN of a codec.** Read the offer's SDP text (`offer.description`) in the log and identify which video codec ended up first in the `m=video` line's payload type list. Does it match what `DefaultVideoEncoderFactory`'s constructor flags predict?

---

## 24. If the video does not appear, check these things

Work through this list top to bottom — each step assumes the previous ones are already confirmed working.

1. **Camera permission.** Look for the "Camera and microphone permissions are required..." card. If it's showing, tap "Grant permissions" and check the system dialog result. No log line will appear at all if this is blocking you — `onCreatePeersClicked` never runs.
2. **Microphone permission.** Same card covers both; if only mic was denied, `createLocalAudioTrack` throws inside the `runCatching` block and you'll see `[WebRTC] ERROR: ...` in the log instead of a crash.
3. **PeerConnectionFactory initialization.** Expect `[WebRTC] Factory created` in the very first few log lines after pressing "Create Peers." If it's missing, the coroutine likely threw before reaching that line — check the error card.
4. **VideoSource.** No direct log line, but if this silently failed you'd never see `[WebRTC] Camera capture started (...)` right after it.
5. **VideoTrack.** Expect `[WebRTC] Local video track created`.
6. **`addTrack()`.** Expect `[WebRTC] Local video track added to peer connection` (and the audio equivalent) — these only appear for Peer A, never Peer B, by design.
7. **Offer creation.** Expect `[WebRTC] Offer created` after pressing "Create Offer." If this never appears, check that "Create Peers" fully finished first (`peersCreated` must be true — the button is disabled otherwise).
8. **Local description.** Expect `[WebRTC][A] Signaling state: HAVE_LOCAL_OFFER` followed by `[WebRTC] Peer A local description set`.
9. **Remote description.** Expect `[WebRTC][B] Signaling state: HAVE_REMOTE_OFFER` then `[WebRTC] Peer B remote description set`. If B's signaling state never changes, the "signaling simulation" hand-off (passing the same `offer` object) didn't happen — check you pressed "Create Offer" (not just "Create Peers").
10. **ICE candidates.** Expect several `[WebRTC][A] ICE candidate generated` / `[WebRTC][B] ICE candidate generated` lines appearing continuously in the background once each peer's local description is set — you do not need to press anything for these to start.
11. **Connection state.** After pressing "Exchange ICE," expect a burst of `[WebRTC][A] ICE connection state: CHECKING` / `COMPLETED` and `[WebRTC][A] Peer connection state: CONNECTING` -> `CONNECTED` (same for B) within a second or two. If it sticks on `CHECKING`, candidates were probably not actually exchanged — check the "Exchange ICE ($N)" button's count before/after tapping it.
12. **`onAddTrack()`.** Expect `[WebRTC][B] onAddTrack fired, track kind=video` shortly after B's connection state reaches CONNECTED. If connection is CONNECTED but this never fires, double check `addTrack()` (step 6) actually happened on Peer A before the offer was created.
13. **SurfaceViewRenderer initialization.** If `hasRemoteVideo` is true but the box stays blank/grey, check `RemoteVideoRenderer`'s `factory` lambda — `init(eglBaseContext, null)` must run before `addSink` is ever called; make sure you didn't pass two DIFFERENT `EglBase.Context` instances anywhere (there must be exactly one `EglBase` for the whole app, owned by `WebRtcManager`).
14. **Renderer layout.** Confirm the `Box` around `RemoteVideoRenderer` has a nonzero size (`.height(220.dp)` in this project) — a `SurfaceViewRenderer` with zero layout size renders nothing even with a healthy track attached.
15. **Renderer release.** If you navigate away and back and the video never reappears, check that `onRelease` in `WebRtcRenderer.kt` isn't accidentally being triggered while the screen is still visible (it should only fire when the Composable truly leaves the tree) and that `uiState.remoteVideoTrack` still holds a live (non-disposed) track.

---

## 25. Learning checkpoints

Work through these without looking at the code. If you get stuck on one, that tells you exactly which section above to re-read.

- **Checkpoint 1:** Explain what `PeerConnectionFactory` is, in one or two sentences, using your own analogy (not the Retrofit one from section 4).
- **Checkpoint 2:** Explain why this project has two `PeerConnection` objects even though it's one Android app on one device.
- **Checkpoint 3:** Draw `Offer -> Answer` by hand, labeling which peer calls which method at each arrow.
- **Checkpoint 4:** Explain the difference between Local Description and Remote Description, and why a PeerConnection needs both before media can flow.
- **Checkpoint 5:** Explain what an ICE Candidate is and why SDP negotiation alone isn't enough to connect two peers.
- **Checkpoint 6:** Without opening any file, narrate the complete flow out loud, end to end — from pressing "Create Peers" to seeing Peer B's remote video render — including where PeerConnectionFactory, addTrack, offer/answer, and ICE candidates each fit in.

---

## 26. Interview questions

1. **What is a PeerConnection?**
   *Answer:* One endpoint's representation of a single WebRTC session — it owns SDP negotiation state, the ICE agent, the encrypted transport, and its senders/receivers.
   *Tests:* Whether you understand PeerConnection as a per-endpoint object, not "the call" itself.

2. **What is PeerConnectionFactory, and why does WebRTC need it instead of letting you `new PeerConnection()` directly?**
   *Answer:* The factory owns the native engine, its threads, and codec pipeline; every PeerConnection/track must be built through it so they all share that one native context.
   *Tests:* Understanding of WebRTC's native/JNI architecture, not just the API surface.

3. **What is SDP, and what does it actually describe?**
   *Answer:* A text format describing a proposed/agreed media session: media kinds, codecs, directions, and transport/security parameters — not the media itself.
   *Tests:* Whether you can go beyond "it's a string" to what the string represents.

4. **Offer vs. Answer — what's the actual difference?**
   *Answer:* The offer is the first, proposing description (built from the offering peer's own tracks); the answer is the second, produced only after `setRemoteDescription(offer)`, and it's constrained by what the offer asked for.
   *Tests:* Ordering and dependency understanding, not just definitions.

5. **Local Description vs. Remote Description?**
   *Answer:* Local = what this PeerConnection has committed to proposing/agreeing to; Remote = what the other side has committed to. Both are required before negotiation can complete.
   *Tests:* Whether "Offer" (a value) and "Local Description" (that value applied to a specific connection) are understood as distinct concepts.

6. **What is ICE, and why is it separate from SDP negotiation?**
   *Answer:* ICE is the process of discovering and testing real network paths between peers; SDP settles *what* to communicate, ICE settles *how* the bytes travel.
   *Tests:* Whether you conflate signaling/negotiation with actual connectivity.

7. **What is an ICE candidate?**
   *Answer:* One possible address+port+protocol a peer could be reached at (host, server-reflexive, or relay).
   *Tests:* Basic ICE vocabulary and whether you know the three candidate types exist even if this project only produces host candidates.

8. **Why is signaling required at all if WebRTC media itself is peer-to-peer?**
   *Answer:* Because the two peers have no way to find each other or agree on session parameters without SOME out-of-band channel first — WebRTC intentionally does not define what that channel is (WebSocket, HTTP polling, even copy-pasting text), leaving app developers free to pick one.
   *Tests:* Understanding that "no signaling protocol is mandated" is a deliberate design choice, illustrated by this project doing it with plain in-memory object hand-offs.

9. **Why does WebRTC use SDP instead of, say, JSON, for describing sessions?**
   *Answer:* SDP predates WebRTC (RFC 4566, originally for SIP/RTSP); WebRTC reused it for interoperability with the existing real-time-communication ecosystem rather than inventing a new format.
   *Tests:* Awareness that WebRTC builds on pre-existing IETF protocols rather than being designed in isolation.

10. **What happens immediately after `createOffer()` succeeds — is the peer connection now "sending" anything?**
    *Answer:* No — `createOffer()` only produces a `SessionDescription` value; nothing is committed or sent until `setLocalDescription()` is called (and, in a real app, until the offer is actually transmitted to the other peer).
    *Tests:* Whether you understand `createOffer()` as a pure/inspecting call versus a stateful one.

11. **What does `onIceCandidate()` do, and when does it fire relative to `setLocalDescription()`?**
    *Answer:* It's called by the native ICE agent every time it discovers one more local candidate; it only starts firing after `setLocalDescription()` has been called for that PeerConnection.
    *Tests:* Correct sequencing knowledge — a common trick question is asking whether candidates can appear before any description is set (they cannot, for the *local* side).

12. **What is `onAddTrack()`, and how is it different from the older `onAddStream()`?**
    *Answer:* `onAddTrack()` fires per-track under Unified Plan (the modern, standardized SDP semantics) with an `RtpReceiver`; `onAddStream()` is a legacy Plan B callback delivering a whole `MediaStream` at once. Modern code should rely on `onAddTrack` (or `onTrack`).
    *Tests:* Awareness of the Plan B -> Unified Plan transition in WebRTC's history.

13. **How does remote video actually reach the UI in an Android app?**
    *Answer:* `onAddTrack` hands you a `VideoTrack`; you call `videoTrack.addSink(surfaceViewRenderer)` on a `SurfaceViewRenderer` (a `VideoSink` implementation) that has been `init()`-ed with a shared `EglBase.Context`; the renderer draws each `VideoFrame` via OpenGL.
    *Tests:* Whether you know the concrete `addSink`/renderer mechanics, not just "it renders somehow."

14. **What happens if ICE connection fails?**
    *Answer:* After trying every candidate pair without success, the ICE agent reports `IceConnectionState.FAILED`, and the aggregated `PeerConnectionState` also becomes `FAILED`; media never flows. Common real-world causes are symmetric NATs with no TURN server configured, or firewalls blocking UDP.
    *Tests:* Whether you can connect the FAILED state back to real network conditions, not just recite the enum value.

15. **What's the difference between WebRTC "media" and "signaling," concretely, in this project?**
    *Answer:* Signaling is everything this project's ViewModel does manually — passing `SessionDescription`/`IceCandidate` Kotlin objects between the two `PeerConnectionManager`s; media is the actual encrypted audio/video bytes that flow directly between the two native `PeerConnection` objects once ICE connects, which this project's application code never touches directly.
    *Tests:* Whether you can point at the exact lines in a real codebase that are "signaling" versus lines that are "WebRTC doing its own thing."

16. **Why does this project pass an empty `iceServers` list instead of a public STUN server?**
    *Answer:* Both PeerConnections run on the same device; there's no NAT to see through and no external network to reach, so only host candidates are needed, and STUN/TURN would add nothing.
    *Tests:* Whether you understand STUN/TURN's *purpose* well enough to know when they're unnecessary, not just "always add a STUN server."

17. **Why is `PeerConnectionFactory.initialize()` guarded against being called twice in this project, and what would happen if you removed the guard?**
    *Answer:* It's a static, process-wide native call meant to run once; removing the guard wouldn't necessarily crash (WebRTC's implementation tolerates re-init), but it signals a wrong architecture and wastes native setup work — the correct fix is not calling it more than once, not making the second call "safe."
    *Tests:* Whether you understand *why* the guard exists as a design decision, not just that a flag happens to be there.
