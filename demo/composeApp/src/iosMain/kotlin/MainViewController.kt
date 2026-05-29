import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreen

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun iOSApp() {
    MaterialTheme {
        Navigator(HomeScreen) { navigator ->
            Scaffold(
                topBar = {
                    if (navigator.lastItem != HomeScreen) {
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                Button(onClick = { navigator.pop() }) {
                                    Text("Back")
                                }
                            }
                        )
                    }
                },
                content = { CurrentScreen() },
            )
        }
    }
}


@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController() = ComposeUIViewController { iOSApp() }
