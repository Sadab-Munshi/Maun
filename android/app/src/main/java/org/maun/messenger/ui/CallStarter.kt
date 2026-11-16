package org.maun.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Gives back a function that starts a call (a video call when given true)
 * once the phone lets Maun use what the call needs. The microphone, and for a
 * video call the camera, are asked for the first time they are needed. A
 * call needs the microphone; without the camera a video call still goes
 * ahead, with sound only.
 */
@Composable
fun rememberCallStarter(onCall: (Boolean) -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val start by rememberUpdatedState(onCall)
    var wantsVideo by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic) start(wantsVideo) else Toast.makeText(context, "Maun needs the microphone for a call.", Toast.LENGTH_LONG).show()
    }
    return { video ->
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video })
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        wantsVideo = video
        if (needed.isEmpty()) start(video) else ask.launch(needed.toTypedArray())
    }
}
