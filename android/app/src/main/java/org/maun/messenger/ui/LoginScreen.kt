package org.maun.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.maun.messenger.network.Usernames

/** Where the email login of a member-only relay has got to. */
data class LoginUi(
    val step: Step = Step.Email,
    /** The address the code was sent to. */
    val email: String = "",
    /** A request to the relay is under way. */
    val working: Boolean = false,
    /** Why the last thing tried did not work. */
    val problem: String? = null,
    /** This address already has a Maun ID that is not on this phone. */
    val existing: Existing? = null,
) {
    enum class Step { Email, Code, Username, Existing }

    /** [hasRecovery] is false when that ID left no sealed backup to restore. */
    data class Existing(val id: String, val username: String?, val hasRecovery: Boolean)
}

/** Whether [text] has the shape of an email address. The relay has the last word. */
fun looksLikeEmail(text: String): Boolean {
    val trimmed = text.trim()
    val at = trimmed.indexOf('@')
    return at > 0 && at == trimmed.lastIndexOf('@') && trimmed.indexOf('.', at) > at + 1 &&
        !trimmed.endsWith(".") && trimmed.none { it.isWhitespace() } && trimmed.length <= 254
}

/**
 * Stands in front of the chats while the relay does not know who this is.
 * Three steps: an email address, the code sent there, and for a new member a
 * username. [checking] is true while the relay is being shown the result.
 */
@Composable
fun LoginScreen(
    relayName: String,
    ui: LoginUi,
    checking: Boolean,
    onEmail: (String) -> Unit,
    onCode: (String) -> Unit,
    onUsername: (String) -> Unit,
    onStartOver: () -> Unit,
    onRestore: (CharArray) -> Unit = {},
    onStartNewIdentity: () -> Unit = {},
) {
    val busy = ui.working || checking
    BackHandler(enabled = ui.step != LoginUi.Step.Email && !busy, onBack = onStartOver)
    when (ui.step) {
        LoginUi.Step.Email -> EmailStep(relayName, ui, busy, onEmail)
        LoginUi.Step.Code -> CodeStep(ui, busy, onCode, onResend = { onEmail(ui.email) }, onStartOver = onStartOver)
        LoginUi.Step.Username -> UsernameStep(ui, busy, onUsername)
        LoginUi.Step.Existing -> ExistingStep(ui, busy, onRestore, onStartNewIdentity, onStartOver)
    }
}

/**
 * The address has a Maun ID already, and this phone does not hold its keys.
 *
 * Nothing on the relay has been touched to get here, and nothing will be until
 * this is answered. If the old phone left a sealed backup, its passphrase is
 * asked for here and the ID comes back as it was: same ID, same safety number,
 * same username, same badge, same picture. If it left none, the only way on is
 * to start a new ID — said in as many words, with what it costs spelled out.
 */
@Composable
private fun ExistingStep(
    ui: LoginUi,
    busy: Boolean,
    onRestore: (CharArray) -> Unit,
    onStartNewIdentity: () -> Unit,
    onStartOver: () -> Unit,
) {
    val existing = ui.existing ?: return
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val who = existing.username?.let { "@$it" } ?: "an account"
    StepFrame(
        icon = MaunIcons.Lock,
        title = "This address already has a Maun ID",
        text = if (existing.hasRecovery) {
            "$who is on this relay, with its own keys. Enter the recovery passphrase you set on the " +
                "phone that had it, and this phone becomes that account again: the same ID, the same " +
                "safety number, the same picture."
        } else {
            "$who is on this relay, and it left no recovery backup. Its keys were only ever on the " +
                "phone that had it, so they cannot be brought here, and its chats cannot be recovered. " +
                "Nothing has been changed yet."
        },
    ) {
        if (existing.hasRecovery) {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Recovery passphrase") },
                singleLine = false,
                isError = ui.problem != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (passphrase.isNotEmpty() && !busy) onRestore(passphrase.toCharArray()) }),
                modifier = fieldWidth,
            )
            Problem(ui.problem)
            Button(
                onClick = { onRestore(passphrase.toCharArray()) },
                enabled = passphrase.isNotEmpty() && !busy,
                modifier = fieldWidth,
            ) { Text(if (busy) "Opening…" else "Restore this account") }
        } else {
            Problem(ui.problem)
        }

        if (!existing.hasRecovery || confirming) {
            Text(
                text = "A new Maun ID gets new keys: your contacts will see a new safety number, the " +
                    "username becomes the new ID's, and everything waiting for the old one is thrown " +
                    "away. Your chats are not brought back by either way.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalStatusColors.current.warning,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Button(
                onClick = onStartNewIdentity,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = fieldWidth,
            ) { Text(if (busy) "One moment…" else "Start a new Maun ID") }
        } else {
            TextButton(onClick = { confirming = true }, enabled = !busy) {
                Text("I cannot restore it — start a new ID instead")
            }
        }

        TextButton(onClick = onStartOver, enabled = !busy) { Text("Use another email address") }
    }
}

@Composable
private fun StepFrame(icon: ImageVector, title: String, text: String, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            content()
        }
    }
}

@Composable
private fun Problem(text: String?) {
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 420.dp),
    )
}

private val fieldWidth = Modifier.fillMaxWidth().widthIn(max = 420.dp)

@Composable
private fun EmailStep(relayName: String, ui: LoginUi, busy: Boolean, onEmail: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf(ui.email) }
    val focus = LocalFocusManager.current
    val valid = looksLikeEmail(email)
    val go = {
        if (valid && !busy) {
            focus.clearFocus()
            onEmail(email.trim())
        }
    }
    StepFrame(
        icon = MaunIcons.Mail,
        title = "Log in",
        text = "$relayName is for members. Enter your email address; a 6-digit code is sent there.",
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.take(254) },
            label = { Text("Email address") },
            singleLine = true,
            isError = ui.problem != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = fieldWidth,
        )
        Problem(ui.problem)
        Button(onClick = go, enabled = valid && !busy, modifier = fieldWidth) {
            Text(if (busy) "One moment…" else "Send code")
        }
        Text(
            text = "You log in once on this phone. After that the phone proves itself with a key that " +
                "cannot leave it.\n\nThe relay's administrator will see this address, your username, your " +
                "Maun ID, and which phone you use: its make, model, a fingerprint of it, and the app's " +
                "version. Your messages " +
                "stay unreadable to the relay and its administrator.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

@Composable
private fun CodeStep(ui: LoginUi, busy: Boolean, onCode: (String) -> Unit, onResend: () -> Unit, onStartOver: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val go = {
        if (code.length == CODE_DIGITS && !busy) {
            focus.clearFocus()
            onCode(code)
        }
    }
    StepFrame(
        icon = MaunIcons.Lock,
        title = "Enter the code",
        text = "A 6-digit code was sent to ${ui.email}. It works for ten minutes. Look in the spam folder too.",
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { typed -> code = typed.filter { it.isDigit() }.take(CODE_DIGITS) },
            label = { Text("Code") },
            singleLine = true,
            isError = ui.problem != null,
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = MaterialTheme.typography.headlineSmall.fontSize * 0.3f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = fieldWidth,
        )
        Problem(ui.problem)
        Button(onClick = go, enabled = code.length == CODE_DIGITS && !busy, modifier = fieldWidth) {
            Text(if (busy) "Checking…" else "Log in")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onResend, enabled = !busy) { Text("Send again") }
            TextButton(onClick = onStartOver, enabled = !busy) { Text("Other address") }
        }
    }
}

@Composable
private fun UsernameStep(ui: LoginUi, busy: Boolean, onUsername: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val valid = Usernames.isValid(name)
    val go = {
        if (valid && !busy) {
            focus.clearFocus()
            onUsername(name)
        }
    }
    StepFrame(
        icon = MaunIcons.AddPerson,
        title = "Choose a username",
        text = "Other members add you by typing it, and the administrator sees it when deciding whether to let you in.",
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { typed -> name = Usernames.clean(typed).filter { it.isLetterOrDigit() || it == '_' }.take(20) },
            label = { Text("Username") },
            prefix = { Text("@") },
            singleLine = true,
            isError = ui.problem != null,
            supportingText = { Text(Usernames.RULE.replaceFirstChar { it.uppercase() }) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = fieldWidth,
        )
        Problem(ui.problem)
        Button(onClick = go, enabled = valid && !busy, modifier = fieldWidth) {
            Text(if (busy) "One moment…" else "Continue")
        }
    }
}

private const val CODE_DIGITS = 6

/**
 * Shown after the email login, until the relay's administrator has let this
 * member in. Asks again by itself every so often while it is on screen.
 */
@Composable
fun PendingScreen(relayName: String, username: String?, checking: Boolean, onCheckAgain: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(RECHECK_MILLIS)
            onCheckAgain()
        }
    }
    StepFrame(
        icon = MaunIcons.Clock,
        title = "Waiting for approval",
        text = "The administrator of $relayName has been told that " +
            (if (username != null) "@$username" else "you") +
            " would like to join. Maun opens by itself as soon as they approve.",
    ) {
        TextButton(onClick = onCheckAgain, enabled = !checking) { Text(if (checking) "Checking…" else "Check now") }
    }
}

private const val RECHECK_MILLIS = 20_000L

/** Shown, and nothing else, once the relay's owner has shut this member out. */
@Composable
fun BannedScreen(relayName: String, onCheckAgain: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                MaunIcons.Block,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                "You have been banned",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "The administrator of $relayName has removed your access. The messages and files on this phone " +
                    "have been deleted. If you think this is a mistake, ask them.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            TextButton(onClick = onCheckAgain) { Text("Check again") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginPreview() {
    MaunTheme { LoginScreen("maun.example.workers.dev", LoginUi(), checking = false, onEmail = {}, onCode = {}, onUsername = {}, onStartOver = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PendingPreview() {
    MaunTheme { PendingScreen("maun.example.workers.dev", "asha", checking = false, onCheckAgain = {}) }
}
