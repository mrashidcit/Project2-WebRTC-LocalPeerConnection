package com.mrashidcit.project2_localpeerconnection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mrashidcit.project2_localpeerconnection.presentation.call.LocalPeerConnectionScreen
import com.mrashidcit.project2_localpeerconnection.ui.theme.Project2LocalPeerConnectionTheme



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Project2LocalPeerConnectionTheme {
                LocalPeerConnectionScreen()
            }
        }
    }
}

/*
 * Deliberately thin: MainActivity's only job is to host the Compose tree and
 * apply the app theme. Everything WebRTC-related lives in
 * presentation/call/* and webrtc/* - see LocalPeerConnectionScreen.kt for
 * where the real project starts. LocalPeerConnectionScreen owns its own
 * Scaffold, so MainActivity does not add a second one.
 *
 * PART 20 - Android lifecycle note: this project uses a single Activity with
 * a single ViewModel obtained via `viewModel()`. Jetpack's ViewModel already
 * survives configuration changes (e.g. screen rotation) by design - the
 * WebRTC objects living inside LocalPeerConnectionViewModel are NOT
 * recreated on rotation, only the Compose UI recomposes around them. The
 * camera/PeerConnections are only torn down when the ViewModel itself is
 * cleared (the user presses "Close", or this Activity finishes for good) -
 * see LocalPeerConnectionViewModel.onCleared().
*/