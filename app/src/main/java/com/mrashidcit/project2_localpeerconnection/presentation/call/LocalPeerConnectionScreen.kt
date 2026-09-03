package com.mrashidcit.project2_localpeerconnection.presentation.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrashidcit.project2_localpeerconnection.webrtc.RemoteVideoRenderer

/**
 * PART 16 - the Compose UI. Notice this file never imports `org.webrtc.*`
 * except for `RemoteVideoRenderer`'s parameters (VideoTrack/EglBase.Context
 * are unavoidable there, see the comment in LocalPeerConnectionUiState.kt) -
 * every button here just calls a plain function on the ViewModel:
 *
 *     Composable  -->  ViewModel  -->  WebRtcManager / PeerConnectionManager
 *
 * This screen also owns runtime permission requesting (CAMERA + RECORD_AUDIO).
 * Permissions are a plain Android/OS concept, not a WebRTC concept, so they
 * belong at this UI layer rather than inside the WebRTC classes - the WebRTC
 * classes simply assume the permissions are already granted by the time
 * `onCreatePeersClicked()` runs.
 */
@Composable
fun LocalPeerConnectionScreen(
    viewModel: LocalPeerConnectionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasRequiredPermissions by remember { mutableStateOf(hasCameraAndMicPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasRequiredPermissions = results.values.all { granted -> granted }
    }

    // Ask once, the first time this screen is shown, if we don't already have
    // both permissions (e.g. from a previous run of the app).
    LaunchedEffect(Unit) {
        if (!hasRequiredPermissions) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Local PeerConnection",
                style = MaterialTheme.typography.headlineSmall
            )

            RemoteVideoBox(
                hasRemoteVideo = uiState.hasRemoteVideo,
                eglBaseContext = viewModel.eglBaseContext,
                videoTrack = uiState.remoteVideoTrack
            )

            Text(
                text = "Connection: ${uiState.connectionState}",
                style = MaterialTheme.typography.titleMedium
            )

            if (!hasRequiredPermissions) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Camera and microphone permissions are required to create local media tracks.")
                        Button(onClick = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                        }) {
                            Text("Grant permissions")
                        }
                    }
                }
            }

            uiState.error?.let { errorMessage ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = "Error: $errorMessage",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            ControlButtons(
                hasRequiredPermissions = hasRequiredPermissions,
                peersCreated = uiState.peersCreated,
                pendingIceCandidateCount = uiState.pendingIceCandidateCount,
                onCreatePeersClicked = viewModel::onCreatePeersClicked,
                onCreateOfferClicked = viewModel::onCreateOfferClicked,
                onCreateAnswerClicked = viewModel::onCreateAnswerClicked,
                onExchangeIceClicked = viewModel::onExchangeIceClicked,
                onCloseClicked = viewModel::onCloseClicked
            )

            Text(text = "Logs:", style = MaterialTheme.typography.titleSmall)
            LogList(logs = uiState.logs, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RemoteVideoBox(
    hasRemoteVideo: Boolean,
    eglBaseContext: org.webrtc.EglBase.Context,
    videoTrack: org.webrtc.VideoTrack?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (hasRemoteVideo) {
            RemoteVideoRenderer(eglBaseContext = eglBaseContext, videoTrack = videoTrack)
        } else {
            Text(
                text = "Remote Video\n(waiting for Peer B to receive a track)",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LocaleVideoBox(
    hasLocaleVideo: Boolean,
    eglBaseContext: org.webrtc.EglBase.Context,
    videoTrack: org.webrtc.VideoTrack?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (hasRemoteVideo) {
            Text(
                text =
            )
            RemoteVideoRenderer(eglBaseContext = eglBaseContext, videoTrack = videoTrack)
        } else {
            Text(
                text = "Local Video\n(waiting for Peer A track)",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ControlButtons(
    hasRequiredPermissions: Boolean,
    peersCreated: Boolean,
    pendingIceCandidateCount: Int,
    onCreatePeersClicked: () -> Unit,
    onCreateOfferClicked: () -> Unit,
    onCreateAnswerClicked: () -> Unit,
    onExchangeIceClicked: () -> Unit,
    onCloseClicked: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCreatePeersClicked,
                enabled = hasRequiredPermissions && !peersCreated,
                modifier = Modifier.weight(1f)
            ) { Text("Create Peers") }

            Button(
                onClick = onCreateOfferClicked,
                enabled = peersCreated,
                modifier = Modifier.weight(1f)
            ) { Text("Create Offer") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCreateAnswerClicked,
                enabled = peersCreated,
                modifier = Modifier.weight(1f)
            ) { Text("Create Answer") }

            Button(
                onClick = onExchangeIceClicked,
                enabled = peersCreated,
                modifier = Modifier.weight(1f)
            ) { Text("Exchange ICE ($pendingIceCandidateCount)") }
        }
        Button(
            onClick = onCloseClicked,
            enabled = peersCreated,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Close") }
    }
}

@Composable
private fun LogList(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Auto-scroll to the newest log line so you can watch the flow happen
    // live without manually scrolling every time you tap a button.
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

private fun hasCameraAndMicPermission(context: android.content.Context): Boolean {
    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    return cameraGranted && micGranted
}
