package org.maun.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.maun.messenger.AccountState
import org.maun.messenger.AppLock
import org.maun.messenger.BuildConfig
import org.maun.messenger.Messenger
import org.maun.messenger.crypto.SafetyNumber

private const val HOME = "home"
private const val MY_CODE = "my-code"
private const val ADD_CONTACT = "add-contact"
private const val SETUP_CHECK = "setup-check"
private const val BLOCKED = "blocked"
private const val SETTINGS = "settings"
private const val CHAT_PREFIX = "chat:"
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
    // A contact's details are reached from their conversation, and lead back
    // to it. The block list and the setup check are reached from Settings,
    // except that the setup check can also be opened before there is an account.
    val goBack = {
        val signedIn = (account as? AccountState.Present)?.registered == true
        screen = when {
            screen.startsWith(CONTACT_PREFIX) -> CHAT_PREFIX + screen.removePrefix(CONTACT_PREFIX)
            screen == BLOCKED -> SETTINGS
            screen == SETUP_CHECK && signedIn -> SETTINGS
            else -> HOME
        }
    }

    BackHandler(enabled = screen != HOME, onBack = goBack)

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
                screen == SETUP_CHECK -> SelfCheckScreen(onBack = goBack)

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
                    val chatPeer = screen.takeIf { it.startsWith(CHAT_PREFIX) }?.removePrefix(CHAT_PREFIX)
                    val detailsOf = screen.takeIf { it.startsWith(CONTACT_PREFIX) }
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
                                screen = CHAT_PREFIX + id
                            },
                            onBack = goHome,
                        )

                        screen == BLOCKED -> {
                            val blocked by viewModel.blocked.collectAsStateWithLifecycle()
                            BlockedScreen(blocked = blocked, onUnblock = viewModel::unblock, onBack = goBack)
                        }

                        screen == SETTINGS -> {
                            val activity = LocalActivity.current
                            val blocked by viewModel.blocked.collectAsStateWithLifecycle()
                            val delivery by viewModel.delivery.collectAsStateWithLifecycle()
                            val pushProblem by viewModel.pushProblem.collectAsStateWithLifecycle()
                            val privacy by viewModel.privacy.collectAsStateWithLifecycle()
                            // Permissions, push apps and the screen lock change outside Maun.
                            LifecycleResumeEffect(Unit) {
                                viewModel.refreshDelivery()
                                viewModel.refreshPrivacy()
                                onPauseOrDispose { }
                            }
                            SettingsScreen(
                                version = BuildConfig.VERSION_NAME,
                                relayName = current.code.relay,
                                relay = relay,
                                delivery = delivery,
                                pushProblem = pushProblem,
                                privacy = privacy,
                                blockedCount = blocked.size,
                                onChoosePushApp = viewModel::choosePushApp,
                                onGooglePush = viewModel::setGooglePush,
                                onStayConnected = viewModel::setStayConnected,
                                onDeliveryChanged = viewModel::refreshDelivery,
                                onAppLock = { on ->
                                    if (activity != null) AppLock.setEnabled(activity, on, viewModel::refreshPrivacy)
                                },
                                onBlockScreenshots = { on ->
                                    if (activity != null) AppLock.setBlocksScreenshots(activity, on)
                                    viewModel.refreshPrivacy()
                                },
                                onOpenBlocked = { screen = BLOCKED },
                                onOpenSetupCheck = { screen = SETUP_CHECK },
                                onBack = goHome,
                            )
                        }

                        chatPeer != null -> {
                            // How many messages are loaded. It grows a page at a
                            // time as the reader scrolls back, and survives turning the phone.
                            var window by rememberSaveable(chatPeer) { mutableIntStateOf(Messenger.MESSAGE_PAGE) }
                            val windows = remember(chatPeer) { MutableStateFlow(window) }
                            val conversation by remember(chatPeer) { viewModel.conversation(chatPeer, windows) }
                                .collectAsStateWithLifecycle(initialValue = Conversation())
                            val waiting by viewModel.waiting.collectAsStateWithLifecycle()
                            ChatScreen(
                                peerId = chatPeer,
                                contactName = contacts.firstOrNull { it.id == chatPeer }?.name,
                                messages = conversation.messages,
                                hasOlder = conversation.hasOlder,
                                waiting = waiting[chatPeer],
                                relay = relay,
                                onSend = { text -> viewModel.sendText(chatPeer, text) },
                                onSeen = { viewModel.markRead(chatPeer) },
                                onLoadOlder = {
                                    // Counted from what is on screen, so asking twice asks for the same page.
                                    window = conversation.messages.size + Messenger.MESSAGE_PAGE
                                    windows.value = window
                                },
                                onDelete = viewModel::deleteMessage,
                                onBack = goHome,
                                onOpenDetails = { screen = CONTACT_PREFIX + chatPeer },
                                onAccept = { name -> viewModel.saveContact(chatPeer, name, verified = false) },
                                onBlock = {
                                    viewModel.block(chatPeer)
                                    goHome()
                                },
                            )
                        }

                        detailsOf != null -> {
                            // A few thousand rounds of hashing: quick, but not something to do while drawing.
                            val safetyNumber by produceState<String?>(null, current.code.id, detailsOf.id) {
                                value = withContext(Dispatchers.Default) {
                                    SafetyNumber.of(current.code.id, detailsOf.id)?.let(SafetyNumber::grouped)
                                }
                            }
                            ContactScreen(
                                contact = detailsOf,
                                safetyNumber = safetyNumber,
                                onBack = goBack,
                                onRename = { name -> viewModel.renameContact(detailsOf.id, name) },
                                onChecked = { checked -> viewModel.setContactChecked(detailsOf.id, checked) },
                                onRemove = {
                                    viewModel.removeContact(detailsOf.id)
                                    goHome()
                                },
                                onBlock = {
                                    viewModel.block(detailsOf.id)
                                    goHome()
                                },
                            )
                        }

                        else -> {
                            val lists by viewModel.home.collectAsStateWithLifecycle()
                            val delivery by viewModel.delivery.collectAsStateWithLifecycle()
                            val pushProblem by viewModel.pushProblem.collectAsStateWithLifecycle()
                            // Permissions and push apps change outside Maun; look again on every return.
                            LifecycleResumeEffect(Unit) {
                                viewModel.refreshDelivery()
                                onPauseOrDispose { }
                            }
                            HomeScreen(
                                relay = relay,
                                lists = lists,
                                delivery = delivery,
                                pushProblem = pushProblem,
                                onChoosePushApp = viewModel::choosePushApp,
                                onGooglePush = viewModel::setGooglePush,
                                onStayConnected = viewModel::setStayConnected,
                                onDeliveryChanged = viewModel::refreshDelivery,
                                onShowMyCode = { screen = MY_CODE },
                                onAddContact = { screen = ADD_CONTACT },
                                onOpenChat = { id -> screen = CHAT_PREFIX + id },
                                onReconnect = viewModel::reconnect,
                                onOpenSettings = { screen = SETTINGS },
                            )
                        }
                    }
                }
            }
        }
    }
}
