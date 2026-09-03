package com.mrashidcit.project2_localpeerconnection.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRtcManager - PART 1 & PART 2 of the learning project.
 *
 * ============================================================================
 * PART 1: WHAT IS PeerConnectionFactory?
 * ============================================================================
 *
 * `PeerConnectionFactory` is the root object of the entire WebRTC native
 * stack on Android. Everything else - PeerConnections, VideoSources,
 * AudioSources, VideoTracks, AudioTracks - is created BY this factory,
 * never with a plain Kotlin `XyzTrack()` constructor.
 *
 * Think of it like `Retrofit` in networking code: you configure one
 * `Retrofit` instance (base URL, converters, interceptors) and then every
 * API service you use is built from that one instance. `PeerConnectionFactory`
 * plays the same role for WebRTC: you configure it once (which video
 * encoder/decoder to use, which EGL context to render with) and then every
 * PeerConnection and every media track is built from that one factory.
 *
 * Under the hood, `PeerConnectionFactory` owns:
 *  - The native (C++) WebRTC engine (loaded via JNI - this is why WebRTC
 *    Android libraries ship large .so files per CPU architecture).
 *  - Threads used internally by WebRTC: the "worker thread" (encoding,
 *    network I/O) the "signaling thread" (SDP/ICE state machine callbacks)
 *    and the "network thread".
 *  - The video encoder/decoder pipeline (hardware or software codecs).
 *
 * ----------------------------------------------------------------------------
 * DOES INITIALIZATION HAPPEN ONCE OR MULTIPLE TIMES?
 * ----------------------------------------------------------------------------
 * `PeerConnectionFactory.initialize(...)` is a STATIC, PROCESS-WIDE call.
 * It loads the native library and starts up global WebRTC state
 * (SSL library init, field trials, tracing). It is meant to be called
 * EXACTLY ONCE per process lifetime - not once per screen, not once per
 * PeerConnection, not once per Activity recreation.
 *
 * WHAT HAPPENS IF YOU CALL IT MULTIPLE TIMES?
 * The native implementation is defensive: calling `initialize()` again is
 * not fatal, but it is wasted work (it repeats native setup) and, more
 * importantly, it is a sign your architecture is wrong - a screen that gets
 * recreated (e.g. on rotation) should NOT be responsible for global
 * WebRTC init. That is exactly why this class guards the call with the
 * `isNativeEnvironmentInitialized` flag below: if some part of the app
 * calls `initialize()` a second time (e.g. the user backs out and re-enters
 * the screen), we skip the native call and just log that we skipped it.
 *
 * You CAN, however, create a new `PeerConnectionFactory` instance (via
 * `.builder().createPeerConnectionFactory()`) as many times as you like
 * after the one-time native `initialize()` call - though for this project
 * we only ever create one, since one factory can create as many
 * PeerConnections as we need (we create two: Peer A and Peer B).
 *
 * ============================================================================
 * PART 2: CREATING MEDIA - Camera -> VideoCapturer -> VideoSource -> VideoTrack
 * ============================================================================
 *
 * This mirrors exactly what you built in Project #1:
 *
 *   Camera  --(frames)-->  VideoCapturer  --(frames)-->  VideoSource  -->  VideoTrack
 *   Mic     --(samples)--> (built into AudioSource)      AudioSource  -->  AudioTrack
 *
 * - VideoCapturer: an interface that knows how to pull frames from a
 *   hardware source (camera, screen, or - in tests - a fake generator) and
 *   push them into a VideoSource. We use `Camera2Enumerator` to find and
 *   create a `CameraVideoCapturer` for the device's front camera, exactly
 *   like Project #1.
 *
 * - VideoSource: a native WebRTC object that receives raw frames from the
 *   capturer and makes them available to the rest of the pipeline (encoders,
 *   local rendering). One VideoSource is normally paired with one capturer.
 *
 * - VideoTrack: the object that actually gets attached to a PeerConnection
 *   (via `addTrack`) or a renderer (via `addSink`). A single VideoSource can
 *   back multiple VideoTracks if you needed to send/render the same camera
 *   feed twice - we only need one.
 *
 * - AudioSource / AudioTrack: the audio equivalent. There is no visible
 *   "AudioCapturer" object in the public API - WebRTC's audio device module
 *   (ADM) captures microphone samples internally and feeds them straight
 *   into the AudioSource once you call `createAudioSource()`.
 */
class WebRtcManager(private val appContext: Context) {

    companion object {
        private const val VIDEO_TRACK_ID = "LOCAL_VIDEO_TRACK"
        private const val AUDIO_TRACK_ID = "LOCAL_AUDIO_TRACK"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30

        // Process-wide flag. PeerConnectionFactory.initialize() must only run
        // once per process - see the class doc above.
        @Volatile
        private var isNativeEnvironmentInitialized = false
    }

    /**
     * EglBase owns an EGL context that WebRTC uses for all GPU work: decoding
     * camera frames into OpenGL textures, running the (GPU-accelerated) video
     * encoder/decoder, and rendering into SurfaceViewRenderer. The SAME
     * EglBase.Context must be shared by the capturer, the encoder/decoder
     * factories and every SurfaceViewRenderer, otherwise WebRTC would need to
     * copy frames between separate GPU contexts, which is slow. We create one
     * EglBase for the whole manager and hand its context out to everyone.
     */
    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    lateinit var peerConnectionFactory: PeerConnectionFactory
        private set

    private var cameraVideoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    var localVideoTrack: VideoTrack? = null
        private set
    var localAudioTrack: AudioTrack? = null
        private set

    /**
     * Step 1 of the required sequence: "Initialize PeerConnectionFactory".
     * Must be called before any other method on this class, and before any
     * PeerConnection is created.
     */
    fun initialize(log: (String) -> Unit) {
        if (!isNativeEnvironmentInitialized) {
            val options = PeerConnectionFactory.InitializationOptions
                .builder(appContext.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            isNativeEnvironmentInitialized = true
            log("[WebRTC] PeerConnectionFactory native environment initialized")
        } else {
            log("[WebRTC] Native environment already initialized elsewhere - skipping re-init")
        }

        // The encoder/decoder factories are what let WebRTC use the phone's
        // hardware (MediaCodec) video codecs instead of slow software codecs.
        // Both need the shared EGL context so decoded/encoded frames stay on
        // the GPU instead of being copied to and from the CPU.
        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
        )
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()

        log("[WebRTC] Factory created")
    }

    /**
     * Camera -> VideoCapturer -> VideoSource -> VideoTrack.
     * Only Peer A will use this track (see PART 6 in PeerConnectionManager /
     * the ViewModel) - Peer B receives Peer A's video purely over the
     * simulated PeerConnection link, it never touches the camera itself.
     */
    fun createLocalVideoTrack(log: (String) -> Unit): VideoTrack {
        val capturer = createFrontCameraCapturer(appContext)
        cameraVideoCapturer = capturer

        // SurfaceTextureHelper owns the background thread + SurfaceTexture
        // that the camera writes frames into before WebRTC turns them into
        // encodable video frames. Same pattern as Project #1.
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        surfaceTextureHelper = helper

        // `isScreencast = false` because this is a real camera, not a screen
        // share - it affects internal encoder tuning (screen content uses
        // different bitrate heuristics than camera content).
        val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        videoSource = source

        capturer.initialize(helper, appContext, source.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        log("[WebRTC] Camera capture started (${VIDEO_WIDTH}x$VIDEO_HEIGHT @${VIDEO_FPS}fps)")

        val track = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, source)
        track.setEnabled(true)
        localVideoTrack = track
        log("[WebRTC] Local video track created")
        return track
    }

    /** Microphone -> AudioSource -> AudioTrack. */
    fun createLocalAudioTrack(log: (String) -> Unit): AudioTrack {
        // MediaConstraints here mainly controls audio-processing options
        // (echo cancellation, auto gain control, noise suppression). An
        // empty MediaConstraints() uses WebRTC's sensible defaults, which is
        // all we need for this learning project.
        val source = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioSource = source

        val track = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, source)
        track.setEnabled(true)
        localAudioTrack = track
        log("[WebRTC] Local audio track created")
        return track
    }

    private fun createFrontCameraCapturer(context: Context): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        if (deviceNames.isEmpty()) {
            throw IllegalStateException("No camera found on this device")
        }

        val cameraName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: deviceNames.first()

        // The second argument is a CameraEventsHandler - we don't need one
        // for this project, so we pass null (same as Project #1).
        return enumerator.createCapturer(cameraName, null)
            ?: throw IllegalStateException("Camera2Enumerator could not create a capturer for $cameraName")
    }

    /**
     * PART 19 - RESOURCE CLEANUP.
     *
     * Order matters here:
     *  1. Stop the capturer BEFORE disposing it (stopCapture is a blocking
     *     call that waits for the capture thread to actually stop).
     *  2. Dispose the capturer and the SurfaceTextureHelper.
     *  3. Dispose tracks, then the sources they were built from.
     *  4. Dispose the factory last, since it is the parent of everything
     *     above.
     *  5. Release the EglBase context last of all, since encoders/decoders/
     *     renderers may still be using it up to that point.
     *
     * WHY THIS MATTERS: every object above is a thin Kotlin wrapper around a
     * native C++ object holding real OS resources (camera handles, decoder/
     * encoder hardware sessions, GPU contexts, background threads). If you
     * let a WebRtcManager go out of scope without calling release(), those
     * native resources leak - the camera can stay "busy" for other apps, and
     * native memory is never freed until the process dies.
     */
    fun release(log: (String) -> Unit) {
        try {
            cameraVideoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            log("[WebRTC] Interrupted while stopping camera capture: ${e.message}")
        }
        cameraVideoCapturer?.dispose()
        cameraVideoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null

        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null

        if (::peerConnectionFactory.isInitialized) {
            peerConnectionFactory.dispose()
        }

        eglBase.release()
        log("[WebRTC] WebRtcManager resources released")
    }
}
