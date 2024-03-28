import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import org.jetbrains.compose.ui.tooling.preview.Preview
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreen
import ovh.plrapps.mapcompose.demo.ui.screens.MapDemoSimple

@Composable
@Preview
fun desktopApp() {
    MaterialTheme {
        Navigator(MapDemoSimple) { navigator ->
            Row {
                Column(modifier = Modifier.width(250.dp)) {
                    HomeScreen.Content()
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    CurrentScreen()
                }
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "MapComposeKMP") {
        desktopApp()
    }
}