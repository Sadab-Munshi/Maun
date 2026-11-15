package org.maun.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maun.messenger.ui.AppRoot
import org.maun.messenger.ui.MaunTheme

class MainActivity : ComponentActivity() {

    private val messenger: Messenger
        get() = (application as MaunApp).messenger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaunTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        messenger.onForeground()
    }

    override fun onStop() {
        messenger.onBackground()
        super.onStop()
    }
}
