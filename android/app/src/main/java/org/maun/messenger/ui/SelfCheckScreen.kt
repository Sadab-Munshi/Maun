package org.maun.messenger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.maun.messenger.BuildConfig

@Composable
fun SelfCheckScreen(onBack: (() -> Unit)? = null, viewModel: SelfCheckViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SelfCheckContent(
        state = state,
        onBack = onBack,
        onRelayUrlChange = viewModel::onRelayUrlChange,
        onRun = viewModel::runChecks,
    )
}

@Composable
private fun SelfCheckContent(
    state: SelfCheckUiState,
    onBack: (() -> Unit)?,
    onRelayUrlChange: (String) -> Unit,
    onRun: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val run = {
        focus.clearFocus()
        onRun()
    }

    ScreenFrame(title = "Setup check", onBack = onBack) {
        BodyText("Checks that this phone and your server can do everything Maun is built on.")

        OutlinedTextField(
            value = state.relayUrl,
            onValueChange = onRelayUrlChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.running && !state.relayFixed,
            singleLine = true,
            label = { Text("Relay address") },
            placeholder = { Text("maun.your-name.workers.dev") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { run() }),
        )

        Button(
            onClick = run,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.running) "Checking…" else "Run checks")
        }

        state.checks.forEach { CheckRow(it) }

        Text(
            text = "Maun ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(check: CheckState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusBadge(check.status)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = check.id.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = check.detail.ifEmpty { check.id.purpose },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (check.status) {
                        CheckStatus.FAILED -> LocalStatusColors.current.failed
                        CheckStatus.WARNING -> LocalStatusColors.current.warning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: CheckStatus) {
    val colors = LocalStatusColors.current
    val (symbol, color, label) = when (status) {
        CheckStatus.PENDING -> Triple("", MaterialTheme.colorScheme.outline, "Not run yet")
        CheckStatus.RUNNING -> Triple("", MaterialTheme.colorScheme.primary, "Running")
        CheckStatus.PASSED -> Triple("✓", colors.passed, "Passed")
        CheckStatus.WARNING -> Triple("!", colors.warning, "Passed with a caution")
        CheckStatus.FAILED -> Triple("✕", colors.failed, "Failed")
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            CheckStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            CheckStatus.PENDING -> Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(2.dp, color),
                modifier = Modifier.size(20.dp),
            ) {}
            else -> Surface(shape = CircleShape, color = color, modifier = Modifier.size(26.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = symbol,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SelfCheckPreview() {
    MaunTheme {
        SelfCheckContent(
            state = SelfCheckUiState(
                relayUrl = "maun.example.workers.dev",
                checks = listOf(
                    CheckState(CheckId.DEVICE_KEY, CheckStatus.PASSED, "Held in the processor's secure area (TEE)"),
                    CheckState(CheckId.DATABASE, CheckStatus.WARNING, "Example caution"),
                    CheckState(CheckId.SIGNAL, CheckStatus.RUNNING),
                    CheckState(CheckId.RELAY, CheckStatus.FAILED, "Could not reach the relay"),
                ),
            ),
            onBack = {},
            onRelayUrlChange = {},
            onRun = {},
        )
    }
}
