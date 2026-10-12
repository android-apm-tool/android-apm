package dev.androidapm.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SampleApp() }
    }
}

private data object Home
private data object Detail

@Composable
fun SampleApp() {
    val backStack = remember { mutableStateListOf<Any>(Home) }
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> { HomeScreen(onOpenDetail = { backStack.add(Detail) }) }
            entry<Detail> { DetailScreen() }
        },
    )
}

@Composable
fun HomeScreen(onOpenDetail: () -> Unit) {
    BasicText("Open detail", Modifier.padding(32.dp).clickable(onClick = onOpenDetail))
}

@Composable
fun DetailScreen() {
    BasicText("Android APM detail", Modifier.padding(32.dp))
}
