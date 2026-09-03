package com.mrashidcit.project2_localpeerconnection.webrtc

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * PART 14 & 16 - rendering a remote VideoTrack inside Jetpack Compose.
 *
 * `SurfaceViewRenderer` is a plain Android `View` (it existed long before
 * Jetpack Compose) that knows how to draw WebRTC video frames using OpenGL.
 * WebRTC has no Compose-native renderer, so - exactly like Project #1 - we
 * bridge it into Compose with `AndroidView`, which lets a classic View
 * participate in a Compose layout tree.
 *
 * The full path a remote frame takes to reach the screen is:
 *
 *   Remote PeerConnection (Peer B)
 *        |  onAddTrack() fires with a VideoTrack
 *        v
 *   Remote VideoTrack                              <- this is `videoTrack` below
 *        |  videoTrack.addSink(surfaceViewRenderer)
 *        v
 *   SurfaceViewRenderer                             <- a real Android View
 *        |  wrapped by AndroidView { ... }
 *        v
 *   Jetpack Compose layout
 *
 * `VideoTrack.addSink(sink)` is the call that actually connects a track to
 * anything that wants to consume its frames - a `VideoSink` is any object
 * that implements `onFrame(VideoFrame)`, and `SurfaceViewRenderer` is
 * WebRTC's own ready-made `VideoSink` implementation that draws each frame
 * to its Surface. A single VideoTrack can have multiple sinks (e.g. local
 * preview AND being sent to a PeerConnection at the same time); we only
 * attach one sink here.
 *
 * `AndroidView`'s three lambdas map directly onto the View's lifecycle:
 *  - `factory`: runs exactly once, when this Composable first enters the
 *    tree - this is where we create and `init()` the SurfaceViewRenderer.
 *  - `update`: re-runs whenever a value it reads (here, `videoTrack`)
 *    changes identity - this is where we attach the renderer as a sink once
 *    the remote track actually arrives (it starts out null, before Peer B
 *    has negotiated anything).
 *  - `onRelease`: runs when this Composable leaves the tree - this is where
 *    we detach the sink and release the renderer's native/GL resources so
 *    they don't leak (PART 19 - RESOURCE CLEANUP applies to the renderer
 *    too, not just PeerConnection/tracks).
 */
@Composable
fun RemoteVideoRenderer(
    eglBaseContext: EglBase.Context,
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                // Must be called before the renderer can display anything.
                // ScalingType.SCALE_ASPECT_FIT keeps the remote video's
                // aspect ratio instead of stretching it to fill the box.
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setMirror(false)
                setEnableHardwareScaler(true)
            }
        },
        update = { view ->
            videoTrack?.addSink(view)
        },
        onRelease = { view ->
            videoTrack?.removeSink(view)
            view.release()
        }
    )
}
