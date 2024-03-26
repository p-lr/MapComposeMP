import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import org.jetbrains.compose.ui.tooling.preview.Preview
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreen

@Composable
@Preview
fun iOSApp() {
    MaterialTheme {
        Navigator(HomeScreen) { navigator ->
            Scaffold(
                topBar = { TopAppBar() {
                    if (navigator.lastItem != HomeScreen) {
                        Button(onClick = { navigator.pop() }) {
                            Text("Back")
                        }
                    }
                } },
                content = { CurrentScreen() },
            )
        }
    }
}


fun MainViewController() = ComposeUIViewController { iOSApp() }