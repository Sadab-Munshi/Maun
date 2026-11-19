package org.maun.messenger.ui

import android.net.Uri
import android.widget.Toast
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
import org.maun.messenger.network.RelayState

private const val HOME = "home"
private const val MY_CODE = "my-code"
private const val ADD_CONTACT = "add-contact"
private const val SETUP_CHECK = "setup-check"
private const val BLOCKED = "blocked"
private const val SETTINGS = "settings"
private const val PROFILE = "profile"
private const val CHAT_PREFIX = "chat:"
private const val CONTACT_PREFIX = "contact:"
private const val VERIFY_PREFIX = "verify:"

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
            screen.startsWith(VERIFY_PREFIX) -> CONTACT_PREFIX + screen.removePrefix(VERIFY_PREFIX)
            screen.startsWith(CONTACT_PREFIX) -> CHAT_PREFIX + screen.removePrefix(CONTACT_PREFIX)
            screen == BLOCKED -> SETTINGS
            screen == PROFILE -> SETTINGS
            screen == SETUP_CHECK && signedIn -> SETTINGS
            else -> HOME
        }
    }

    BackHandler(enabled = screen != HOME, onBack = goBack)

    // A message notification was tapped: go to the conversation it was about.
    val wanted by viewModel.openChat.collectAsStateWithLifecycle()
    LaunchedEffect(wanted, account) {
        val peer = wanted ?: return@LaunchedEffect
        if ((account as? AccountState.Present)?.registered == true) {
            screen = CHAT_PREFIX + peer
            viewModel.chatOpened()
        }
    }

    // A notification about several conversations was tapped: the list is where
    // it leads, whatever was open before.
    val wantedList by viewModel.openChats.collectAsStateWithLifecycle()
    LaunchedEffect(wantedList, account) {
        if (wantedList && (account as? AccountState.Present)?.registered == true) {
            screen = HOME
            viewModel.chatsOpened()
        }
    }

    // Someone whose conversation or details are open set Maun up again and
    // has a new ID: stay with the person, not with the old ID.
    LaunchedEffect(Unit) {
        viewModel.moved.collect { (old, new) ->
            if (screen == CHAT_PREFIX + old) screen = CHAT_PREFIX + new
            if (screen == CONTACT_PREFIX + old) screen = CONTACT_PREFIX + new
        }
    }

    val avatarRevision by viewModel.avatarRevision.collectAsStateWithLifecycle()
    val avatarPending by viewModel.avatarPending.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAvatarRevision provides avatarRevision) {
    when (val current = account) {
        AccountState.Loading -> Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Maun", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("Opening your chats…", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                CircularProgressIndicator()
            }
        }

        is AccountState.Broken -> ScreenFrame(title = "Maun cannot start") { BodyText(current.message) }

        else -> {
            val registered = current is AccountState.Present && current.registered
            val banned by viewModel.banned.collectAsStateWithLifecycle()
            val signedOut by viewModel.signedOut.collectAsStateWithLifecycle()
            val recovery by viewModel.recovery.collectAsStateWithLifecycle()
            val awaiting by viewModel.awaitingApproval.collectAsStateWithLifecycle()
            val needsPermissionSetup by viewModel.needsPermissionSetup.collectAsStateWithLifecycle()
            val username by viewModel.username.collectAsStateWithLifecycle()
            val memberEmail by viewModel.memberEmail.collectAsStateWithLifecycle()
            // Once the relay has asked for the email login, the login screen
            // stays until the relay has let this phone in (or refused it for
            // good), also while the answer to a token is still on its way.
            var loginProblem by rememberSaveable { mutableStateOf<String?>(null) }
            var loginNeeded by rememberSaveable { mutableStateOf(false) }
            when (val state = relay) {
                is RelayState.LoginNeeded -> {
                    loginNeeded = true
                    loginProblem = state.problem
                }
                RelayState.Online, RelayState.Banned, RelayState.Pending, is RelayState.Rejected -> loginNeeded = false
                else -> Unit
            }
            DisposableEffect(loginNeeded) {
                // Logging in means leaving Maun to read the code from an email.
                AppLock.loggingIn = loginNeeded
                onDispose { AppLock.loggingIn = false }
            }
            when {
                // A member-only relay comes first: nothing else is shown to
                // someone it has shut out, or does not know yet.
                current is AccountState.Present && banned ->
                    BannedScreen(onCheckAgain = viewModel::checkBanAgain)

                // Signed out on this phone: nothing else is reachable until it signs in.
                current is AccountState.Present && signedOut ->
                    SignedOutScreen(
                        username = username,
                        onLogIn = viewModel::logIn,
                    )

                current is AccountState.Present && awaiting -> PendingScreen(
                    username = username,
                    checking = relay == RelayState.Connecting,
                    onCheckAgain = viewModel::reconnect,
                )

                current is AccountState.Present && loginNeeded -> {
                    val login by viewModel.login.collectAsStateWithLifecycle()
                    LoginScreen(
                        // What the relay said about the last attempt, until something new is tried.
                        ui = if (login.problem == null && !login.working) login.copy(problem = loginProblem) else login,
                        checking = relay !is RelayState.LoginNeeded,
                        onEmail = { email ->
                            loginProblem = null
                            viewModel.loginSendCode(email)
                        },
                        onCode = { code ->
                            loginProblem = null
                            viewModel.loginSubmitCode(code)
                        },
                        onUsername = viewModel::loginChooseUsername,
                        onStartOver = viewModel::loginStartOver,
                        onRestore = viewModel::loginRestore,
                        onStartNewIdentity = viewModel::loginStartNewIdentity,
                    )
                }

                screen == SETUP_CHECK -> SelfCheckScreen(onBack = goBack)

                !registered -> {
                    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
                    // The relay is built into the app, so a fresh install makes
                    // its ID by itself. The welcome screen with the address
                    // field is for builds without one, and for choosing another.
                    var manual by rememberSaveable { mutableStateOf(BuildConfig.RELAY_URL.isBlank()) }
                    if (!manual) {
                        LaunchedEffect(current) {
                            if (current is AccountState.None && onboarding.error == null) viewModel.createAccount()
                        }
                        SettingUpScreen(
                            problem = onboarding.error ?: when (val state = relay) {
                                is RelayState.Retrying -> state.reason
                                is RelayState.Rejected -> state.reason
                                else -> null
                            },
                            working = onboarding.working,
                            // Once the ID exists the connection keeps trying by itself.
                            onRetry = if (current is AccountState.None) viewModel::createAccount else viewModel::reconnect,
                            onChooseServer = if (current is AccountState.None) ({ manual = true }) else null,
                        )
                    } else OnboardingScreen(
                        ui = onboarding,
                        account = current,
                        relay = relay,
                        onRelayUrlChange = viewModel::onRelayUrlChange,
                        onCreate = viewModel::createAccount,
                        onOpenSetupCheck = { screen = SETUP_CHECK },
                    )
                }

                needsPermissionSetup -> PermissionsScreen(
                    onContinue = viewModel::completePermissionSetup,
                )

                else -> {
                    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
                    // Which of these people the relay says Maun's administrators
                    // have checked. Kept for everyone, shown beside their name.
                    val badges by viewModel.verified.collectAsStateWithLifecycle()
                    // Whether this account itself carries that tick.
                    val ownVerified by viewModel.ownVerified.collectAsStateWithLifecycle()
                    val chatPeer = screen.takeIf { it.startsWith(CHAT_PREFIX) }?.removePrefix(CHAT_PREFIX)
                    val detailsOf = screen.takeIf { it.startsWith(CONTACT_PREFIX) }
                        ?.removePrefix(CONTACT_PREFIX)
                        ?.let { id -> contacts.firstOrNull { it.id == id } }
                    // The safety number is worked out only on the screen that
                    // shows it, never for the page about the person.
                    val verifyOf = screen.takeIf { it.startsWith(VERIFY_PREFIX) }
                        ?.removePrefix(VERIFY_PREFIX)
                        ?.let { id -> contacts.firstOrNull { it.id == id } }
                    when {
                        screen == MY_CODE -> MyCodeScreen(
                            code = current.code,
                            onBack = goHome,
                            username = username,
                        )

                        screen == ADD_CONTACT -> AddContactScreen(
                            checkCode = viewModel::checkCode,
                            // Only a member-only relay has usernames.
                            findUser = if (username != null) viewModel::findUser else null,
                            onSave = { id, name, verified, foundAs ->
                                viewModel.saveContact(id, name, verified, foundAs)
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
                            val soundChoices by viewModel.soundChoices.collectAsStateWithLifecycle()
                            val update by viewModel.update.collectAsStateWithLifecycle()
                            // Permissions, push apps and the screen lock change outside Maun.
                            LifecycleResumeEffect(Unit) {
                                viewModel.refreshDelivery()
                                viewModel.refreshPrivacy()
                                onPauseOrDispose { }
                            }
                            SettingsScreen(
                                version = BuildConfig.VERSION_NAME,
                                relay = relay,
                                delivery = delivery,
                                privacy = privacy,
                                blockedCount = blocked.size,
                                sounds = soundChoices,
                                update = update,
                                onNotificationsChanged = viewModel::refreshDelivery,
                                onAppLock = { on ->
                                    if (activity != null) AppLock.setEnabled(activity, on, viewModel::refreshPrivacy)
                                },
                                onBlockScreenshots = { on ->
                                    if (activity != null) AppLock.setBlocksScreenshots(activity, on)
                                    viewModel.refreshPrivacy()
                                },
                                onNotifyText = viewModel::setNotifyText,
                                onTelecomCalls = viewModel::setTelecomCalls,
                                onSaveIncomingFiles = viewModel::setSaveIncomingFiles,
                                onOpenProfile = { screen = PROFILE },
                                onChatPattern = viewModel::setChatPattern,
                                onSentSound = viewModel::setSentSound,
                                onIncomingSound = viewModel::setIncomingSound,
                                onPreviewIncomingSound = viewModel::previewIncomingSound,
                                onLockScreenCalls = {
                                    // Android 14's own page for this; the answer shows when Settings is back in front.
                                    runCatching {
                                        activity?.startActivity(
                                            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                                .setData(Uri.parse("package:${activity.packageName}")),
                                        )
                                    }
                                },
                                onBattery = {
                                    // Android's own question; the answer shows when Settings is back in front.
                                    runCatching {
                                        activity?.startActivity(
                                            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                                .setData(Uri.parse("package:${activity.packageName}")),
                                        )
                                    }
                                },
                                onCheckForUpdates = viewModel::checkForUpdates,
                                onDownloadUpdate = viewModel::downloadUpdate,
                                onInstallUpdate = viewModel::installUpdate,
                                onDismissUpdate = viewModel::dismissUpdate,
                                onOpenBlocked = { screen = BLOCKED },
                                onOpenSetupCheck = { screen = SETUP_CHECK },
                                onBack = goHome,
                            )
                        }

                        screen == PROFILE -> ProfileScreen(
                            code = current.code.id,
                            username = username,
                            memberEmail = memberEmail,
                            onPhoto = { uri -> viewModel.setAvatar(uri) },
                            onRemovePhoto = { viewModel.removeAvatar() },
                            sharingPending = avatarPending,
                            verified = ownVerified,
                            onShowCode = { screen = MY_CODE },
                            recovery = recovery,
                            onEnableRecovery = viewModel::enableRecovery,
                            onDisableRecovery = viewModel::disableRecovery,
                            onLogOut = {
                                // Nothing to go back to: the signed-out screen takes over.
                                screen = HOME
                                viewModel.logOut()
                            },
                            onBack = goBack,
                        )

                        chatPeer != null -> {
                            // How many messages are loaded. It grows a page at a
                            // time as the reader scrolls back, and survives turning the phone.
                            var window by rememberSaveable(chatPeer) { mutableIntStateOf(Messenger.MESSAGE_PAGE) }
                            val windows = remember(chatPeer) { MutableStateFlow(window) }
                            val conversation by remember(chatPeer) { viewModel.conversation(chatPeer, windows) }
                                .collectAsStateWithLifecycle(
                                    // What was last seen of it, so it does not start out empty.
                                    initialValue = remember(chatPeer) {
                                        viewModel.cachedConversation(chatPeer) ?: Conversation(loaded = false)
                                    },
                                )
                            val isContact = contacts.any { it.id == chatPeer }
                            // Someone who wrote without being a contact: at least say what they call themselves.
                            val peerUsername by produceState<String?>(null, chatPeer, isContact, username) {
                                value = if (!isContact && username != null) viewModel.usernameOf(chatPeer) else null
                            }
                            val waiting by viewModel.waiting.collectAsStateWithLifecycle()
                            // What each message of this conversation carries, by message ID.
                            val reactions by remember(chatPeer) { viewModel.reactions(chatPeer) }
                                .collectAsStateWithLifecycle(emptyMap())
                            val fileProgress by viewModel.fileProgress.collectAsStateWithLifecycle()
                            val fileProblem by viewModel.fileProblem.collectAsStateWithLifecycle()
                            val fileNotice by viewModel.fileNotice.collectAsStateWithLifecycle()
                            val privacy by viewModel.privacy.collectAsStateWithLifecycle()
                            val voice by viewModel.voice.collectAsStateWithLifecycle()
                            // Leaving the conversation ends what was playing in it.
                            DisposableEffect(chatPeer) { onDispose { viewModel.stopVoice() } }
                            // A share that could not be started says why, once.
                            val liveProblem by viewModel.liveProblem.collectAsStateWithLifecycle()
                            val liveContext = LocalContext.current
                            LaunchedEffect(liveProblem) {
                                liveProblem?.let { why ->
                                    Toast.makeText(liveContext, why, Toast.LENGTH_LONG).show()
                                    viewModel.clearLiveProblem()
                                }
                            }
                            ChatScreen(
                                peerId = chatPeer,
                                contactName = contacts.firstOrNull { it.id == chatPeer }?.name,
                                peerUsername = peerUsername,
                                onCall = { video -> viewModel.startCall(chatPeer, video) },
                                contactUsername = contacts.firstOrNull { it.id == chatPeer }?.username,
                                chatPattern = privacy.chatPattern,
                                peerVerified = chatPeer in badges,
                                reactions = reactions,
                                onReact = { messageId, emoji -> viewModel.react(chatPeer, messageId, emoji) },
                                contacts = contacts,
                                onForward = { target, texts ->
                                    texts.forEach { text -> viewModel.sendText(target, text) }
                                },
                                loading = !conversation.loaded,
                                messages = conversation.messages,
                                hasOlder = conversation.hasOlder,
                                waiting = waiting[chatPeer],
                                relay = relay,
                                onSend = { text, replyTo -> viewModel.sendText(chatPeer, text, replyTo) },
                                onSeen = { viewModel.markRead(chatPeer) },
                                files = FileActions(
                                    progress = fileProgress,
                                    problem = fileProblem,
                                    onProblemShown = viewModel::clearFileProblem,
                                    notice = fileNotice,
                                    onNoticeShown = viewModel::clearFileNotice,
                                    onAttach = { uri -> viewModel.sendFile(chatPeer, uri) },
                                    onAttachMany = { uris -> viewModel.sendFiles(chatPeer, uris) },
                                    onOpen = { seq, file -> viewModel.openFile(seq, file) },
                                    onStart = viewModel::startFile,
                                    onSave = viewModel::saveFile,
                                    onShare = viewModel::shareFile,
                                    loadImage = viewModel::image,
                                    cachedImage = viewModel::cachedImage,
                                    onVoice = { recording, millis -> viewModel.sendVoice(chatPeer, recording, millis) },
                                    onPlay = viewModel::toggleVoice,
                                    voice = voice,
                                ),
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
                                live = LiveActions(
                                    onShare = { peer, minutes -> viewModel.startLiveShare(peer, minutes) },
                                    onSendNow = { peer -> viewModel.sendCurrentLocation(peer) },
                                    onStop = { peer, share -> viewModel.stopLiveShare(peer, share) },
                                    onWatch = { peer, share -> viewModel.watchLiveShare(peer, share) },
                                    onFetch = { peer, share, key -> viewModel.liveView(peer, share, key) },
                                ),
                            )
                        }

                        verifyOf != null -> {
                            // A few thousand rounds of hashing: quick, but not something to do while drawing.
                            val safetyNumber by produceState<String?>(null, current.code.id, verifyOf.id) {
                                value = withContext(Dispatchers.Default) {
                                    SafetyNumber.of(current.code.id, verifyOf.id)?.let(SafetyNumber::grouped)
                                }
                            }
                            VerificationScreen(
                                contact = verifyOf,
                                safetyNumber = safetyNumber,
                                onBack = goBack,
                                onChecked = { checked -> viewModel.setContactChecked(verifyOf.id, checked) },
                            )
                        }

                        detailsOf != null -> ContactScreen(
                            contact = detailsOf,
                            ticked = detailsOf.id in badges,
                            onBack = goBack,
                            onRename = { name -> viewModel.renameContact(detailsOf.id, name) },
                            onOpenEncryption = { screen = VERIFY_PREFIX + detailsOf.id },
                            onRemove = {
                                viewModel.removeContact(detailsOf.id)
                                goHome()
                            },
                            onCall = { video -> viewModel.startCall(detailsOf.id, video) },
                            onBlock = {
                                viewModel.block(detailsOf.id)
                                goHome()
                            },
                        )

                        else -> {
                            // These snapshots are read before the account is published. Derive
                            // the first frame directly, rather than briefly showing an empty home.
                            val chats by viewModel.chats.collectAsStateWithLifecycle()
                            val lists = remember(contacts, chats) { homeLists(contacts, chats) }
                            val delivery by viewModel.delivery.collectAsStateWithLifecycle()
                            val pushProblem by viewModel.pushProblem.collectAsStateWithLifecycle()
                            // Permissions and push apps change outside Maun; look again on every return,
                            // and ask the relay again who carries the administrators' tick.
                            LifecycleResumeEffect(Unit) {
                                viewModel.refreshDelivery()
                                viewModel.refreshBadges()
                                onPauseOrDispose { }
                            }
                            HomeScreen(
                                relay = relay,
                                lists = lists,
                                delivery = delivery,
                                pushProblem = pushProblem,
                                onChoosePushApp = viewModel::choosePushApp,
                                onGooglePush = viewModel::setGooglePush,
                                onDeliveryChanged = viewModel::refreshDelivery,
                                onShowMyCode = { screen = MY_CODE },
                                onAddContact = { screen = ADD_CONTACT },
                                onOpenChat = { id -> screen = CHAT_PREFIX + id },
                                onReconnect = viewModel::reconnect,
                                onOpenSettings = { screen = SETTINGS },
                                verified = badges.keys,
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
