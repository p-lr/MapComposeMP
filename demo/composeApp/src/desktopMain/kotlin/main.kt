import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import org.jetbrains.compose.ui.tooling.preview.Preview
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreen

@Composable
@Preview
fun desktopApp() {
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

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "MapComposeKMP") {
        desktopApp()
    }
}