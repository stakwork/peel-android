package com.sphinx.peel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sphinx.peel.account.FileSecretStore
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.home.HomeScreen
import com.sphinx.peel.ui.lock.LockScreen
import com.sphinx.peel.ui.onboarding.OnboardingScreen
import com.sphinx.peel.ui.onboarding.RestoringAccountScreen
import com.sphinx.peel.ui.theme.PeelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secretStore = FileSecretStore(applicationContext)
        val storage = Storage(applicationContext)
        setContent {
            PeelTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PeelApp(secretStore, storage)
                }
            }
        }
    }
}

private sealed class Screen {
    object Onboarding : Screen()
    object Lock : Screen()
    data class Restoring(val engine: Engine) : Screen()
    data class Home(val engine: Engine) : Screen()
}

/// Routes between the lock screen (an account is already sealed on this
/// device), onboarding (none is), the restore screen (a restored account
/// whose history hasn't finished) and the account placeholder, mirroring
/// what AppModel does on the Mac and iPhone once the engine exists. The
/// `Engine` for whichever account is open is made exactly once — by
/// onboarding or by the lock screen — and threaded through from there, so
/// `Engine.start()` is never called twice for the same account and its
/// connection survives moving between these screens.
@Composable
private fun PeelApp(secretStore: SecretStore, storage: Storage) {
    var screen by remember {
        mutableStateOf<Screen>(if (secretStore.hasMnemonic) Screen.Lock else Screen.Onboarding)
    }

    when (val current = screen) {
        Screen.Onboarding -> OnboardingScreen(secretStore, storage) { engine ->
            screen = if (engine.restoreProgress.value.restoring) Screen.Restoring(engine) else Screen.Home(engine)
        }
        Screen.Lock -> LockScreen(secretStore, storage) { engine -> screen = Screen.Home(engine) }
        is Screen.Restoring -> RestoringAccountScreen(current.engine) { screen = Screen.Home(current.engine) }
        is Screen.Home -> HomeScreen(current.engine, storage, secretStore) { screen = Screen.Onboarding }
    }
}
