package org.maun.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.maun.messenger.AccountState

private const val HOME = "home"
private const val MY_CODE = "my-code"
private const val ADD_CONTACT = "add-contact"
private const val SETUP_CHECK = "setup-check"
private const val CONTACT_PREFIX = "contact:"

/**
 * Decides which screen is showing. There are few screens and no deep links
 * yet, so the current screen is simply a saved string.
 */
@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val relay by viewModel.relay.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(HOME) }
    val goHome = { screen = HOME }

    BackHandler(enabled = screen != HOME, onBack = goHome)

    when (val current = account) {
        AccountState.Loading -> Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is AccountState.Broken -> ScreenFrame(title = "Maun cannot start") { BodyText(current.message) }

        else -> {
            val registered = current is AccountState.Present && current.registered
            when {
                screen == SETUP_CHECK -> SelfCheckScreen(onBack = goHome)

                !registered -> {
                    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
                    OnboardingScreen(
                        ui = onboarding,
                        account = current,
                        relay = relay,
                        onRelayUrlChange = viewModel::onRelayUrlChange,
                        onCreate = viewModel::createAccount,
                        onOpenSetupCheck = { screen = SETUP_CHECK },
                    )
                }

                else -> {
                    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
                    val contact = screen.takeIf { it.startsWith(CONTACT_PREFIX) }
                        ?.removePrefix(CONTACT_PREFIX)
                        ?.let { id -> contacts.firstOrNull { it.id == id } }
                    when {
                        screen == MY_CODE -> MyCodeScreen(
                            code = current.code,
                            onBack = goHome,
                        )

                        screen == ADD_CONTACT -> AddContactScreen(
                            checkCode = viewModel::checkCode,
                            onSave = { id, name, verified ->
                                viewModel.saveContact(id, name, verified)
                                goHome()
                            },
                            onBack = goHome,
                        )

                        contact != null -> ContactScreen(
                            contact = contact,
                            onBack = goHome,
                            onRemove = {
                                viewModel.removeContact(contact.id)
                                goHome()
                            },
                        )

                        else -> HomeScreen(
                            relay = relay,
                            contacts = contacts,
                            onShowMyCode = { screen = MY_CODE },
                            onAddContact = { screen = ADD_CONTACT },
                            onOpenContact = { id -> screen = CONTACT_PREFIX + id },
                            onReconnect = viewModel::reconnect,
                            onOpenSetupCheck = { screen = SETUP_CHECK },
                        )
                    }
                }
            }
        }
    }
}
