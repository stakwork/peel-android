package com.sphinx.peel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sphinx.peel.ui.theme.PeelTheme
import uniffi.sphinxrs.mnemonicToSeed
import uniffi.sphinxrs.nodeKeys

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PeelTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        FfiSmokeTest(modifier = Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

/// Derives the keys for the "abandon ... about" test mnemonic through the
/// same sphinxrs core the Mac and iOS apps link, and checks the pubkey
/// against the vector Peel's own EngineTests expect
/// (Tests/PeelTestSupport/Fixtures.swift). Proves the whole bridge end to
/// end: Kotlin -> JNI -> the Rust core -> back.
private const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
private const val EXPECTED_PUBKEY = "03653e90c1ce4660fd8505dd6d643356e93cfe202af109d382787639dd5890e87d"

@Composable
private fun FfiSmokeTest(modifier: Modifier = Modifier) {
    val result = remember {
        runCatching {
            val seed = mnemonicToSeed(TEST_MNEMONIC)
            nodeKeys(net = "bitcoin", seed = seed).pubkey
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Peel", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        when {
            result.isFailure -> Text("sphinxrs FFI failed: ${result.exceptionOrNull()?.message}")
            result.getOrNull() == EXPECTED_PUBKEY -> Text("sphinxrs FFI bridge OK\npubkey matches the Mac/iOS test vector")
            else -> Text("sphinxrs FFI returned an unexpected pubkey:\n${result.getOrNull()}")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FfiSmokeTestPreview() {
    PeelTheme { FfiSmokeTest() }
}
