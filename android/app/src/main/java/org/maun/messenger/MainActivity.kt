package org.maun.messenger

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import org.maun.messenger.push.Background
import org.maun.messenger.ui.AppRoot
import org.maun.messenger.ui.LockScreen
import org.maun.messenger.ui.MaunTheme

class MainActivity : ComponentActivity() {

    private val messenger: Messenger
        get() = (application as MaunApp).messenger

    /** Whether the unlock question was already put since the app came on screen. */
    private var offeredUnlock = false

    /** Everything this window draws. */
    private val content: View
        get() = findViewById(android.R.id.content)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLock.onCreate(this)
        setContent {
            MaunTheme {
                // While locked, none of the app is drawn, and no conversation
                // counts as read. Where the user was is kept for afterwards.
                val screens = rememberSaveableStateHolder()
                if (AppLock.locked) {
                    LockScreen(onShown = ::reveal, onUnlock = { AppLock.unlock(this) })
                } else {
                    screens.SaveableStateProvider("app") { AppRoot() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        messenger.onForeground()
        Background.resume(this)
    }

    override fun onResume() {
        super.onResume()
        // Ask once by itself. After a refusal the lock screen's button asks again.
        if (AppLock.locked && !offeredUnlock) {
            offeredUnlock = true
            AppLock.unlock(this)
        }
        // In case the lock screen never reports in, the window must not stay blank.
        content.postDelayed(::reveal, REVEAL_AFTER_MILLIS)
    }

    override fun onStop() {
        messenger.onBackground()
        // On some phones the question is a screen of its own, which stops this one.
        if (!AppLock.asking) offeredUnlock = false
        val wasLocked = AppLock.locked
        AppLock.onStop(this)
        // The screens are redrawn only once the app is back in front. Until
        // the lock screen has taken their place, the window shows nothing
        // rather than, for an instant, the conversation that was open.
        if (AppLock.locked && !wasLocked) content.visibility = View.INVISIBLE
        super.onStop()
    }

    private fun reveal() {
        content.visibility = View.VISIBLE
    }

    private companion object {
        const val REVEAL_AFTER_MILLIS = 500L
    }
}
