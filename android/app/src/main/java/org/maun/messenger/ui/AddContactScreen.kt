package org.maun.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.maun.messenger.CodeCheck
import org.maun.messenger.Messenger

/**
 * Adds a contact in two steps: get their code (camera or pasted text), then
 * give them a name. [checkCode] decides whether a piece of text is a code that
 * can be added; [onSave] receives the ID, the name, and whether it was scanned.
 */
@Composable
fun AddContactScreen(
    checkCode: suspend (text: String, typed: Boolean) -> CodeCheck,
    onSave: (id: String, name: String, verified: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // The ID that was found, and how. Null while still looking.
    var foundId by rememberSaveable { mutableStateOf<String?>(null) }
    var foundByCamera by rememberSaveable { mutableStateOf(false) }

    val id = foundId
    if (id == null) {
        FindCodeStep(
            checkCode = checkCode,
            onFound = { found, byCamera ->
                foundByCamera = byCamera
                foundId = found
            },
            onBack = onBack,
        )
    } else {
        NameStep(
            id = id,
            scanned = foundByCamera,
            onSave = { name -> onSave(id, name, foundByCamera) },
            onBack = { foundId = null },
        )
    }
}

@Composable
private fun FindCodeStep(
    checkCode: suspend (text: String, typed: Boolean) -> CodeCheck,
    onFound: (id: String, byCamera: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var cameraAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraAsked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        cameraAsked = true
    }

    var typed by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraProblem by rememberSaveable { mutableStateOf<String?>(null) }
    // The camera reports the same code many times a second; each text is judged once.
    var lastScanned by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun judge(text: String, wasTyped: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            try {
                val result = checkCode(text, wasTyped)
                problem = describe(result, wasTyped)
                if (result is CodeCheck.Ok) onFound(result.code.id, !wasTyped)
            } finally {
                checking = false
            }
        }
    }

    ScreenFrame(title = "Add contact", onBack = onBack) {
        BodyText("Ask the other person to open My code in Maun, then point this phone at their screen.")

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                val failure = cameraProblem
                when {
                    failure != null -> CameraMessage(failure)
                    cameraAllowed -> QrScanner(
                        onCode = { text ->
                            if (text != lastScanned) {
                                lastScanned = text
                                judge(text, wasTyped = false)
                            }
                        },
                        onError = { cameraProblem = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> CameraMessage(
                        text = if (cameraAsked) {
                            "Camera access was not given. You can allow it in Android settings, " +
                                "or paste the code below."
                        } else {
                            "Maun uses the camera only to read the code. Nothing is recorded or sent."
                        },
                        action = "Allow camera",
                        onAction = { permission.launch(Manifest.permission.CAMERA) },
                    )
                }
            }
        }

        val shownProblem = problem
        if (shownProblem != null) {
            Text(
                text = shownProblem,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalStatusColors.current.failed,
            )
        }

        Text("Or paste their code", style = MaterialTheme.typography.titleMedium)
        val submit = {
            focus.clearFocus()
            if (typed.isNotBlank()) judge(typed, wasTyped = true)
        }
        OutlinedTextField(
            value = typed,
            onValueChange = {
                typed = it
                problem = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Code or ID") },
            placeholder = { Text("maun:c1:…") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        OutlinedButton(onClick = submit, enabled = typed.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Use this code")
        }
    }
}

/** Why a code cannot be added, in words; null when it can. */
private fun describe(result: CodeCheck, wasTyped: Boolean): String? = when (result) {
    is CodeCheck.Ok -> null
    CodeCheck.NotACode -> if (wasTyped) "That is not a Maun code or ID." else "That QR code is not a Maun code."
    CodeCheck.OwnCode -> "That is your own code."
    is CodeCheck.OtherRelay ->
        "This person uses a different relay (${result.relay}). Maun can only connect people on the same relay."
    is CodeCheck.AlreadyAdded -> "Already in your contacts as ${result.name}."
}

@Composable
private fun CameraMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun NameStep(id: String, scanned: Boolean, onSave: (String) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val save = {
        focus.clearFocus()
        if (name.isNotBlank()) onSave(name)
    }

    ScreenFrame(title = "Name this contact", onBack = onBack) {
        Text(
            text = if (scanned) {
                "Code read from their screen."
            } else {
                "Code pasted. Compare the ID below with the one on their My code screen " +
                    "to be sure it is really theirs."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (scanned) LocalStatusColors.current.passed else LocalStatusColors.current.warning,
        )
        IdText(id)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(Messenger.MAX_NAME_LENGTH) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Name") },
            supportingText = { Text("Only you see this name. It stays on this phone.") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = save, enabled = name.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("Save contact")
            }
        }
    }
}
