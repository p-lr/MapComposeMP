import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import org.jetbrains.compose.ui.tooling.preview.Preview
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreen
import ovh.plrapps.mapcompose.demo.ui.screens.MapDemoSimple
import ovh.plrapps.mapcompose.demo.viewmodels.GlobalVM

@Composable
@Preview
fun desktopApp() {
    MaterialTheme {
        Navigator(MapDemoSimple) { navigator ->
            val globalScreenModel = navigator.rememberNavigatorScreenModel { GlobalVM }

            Row {
                Column(modifier = Modifier.width(250.dp)) {
                    HomeScreen.Content()
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CurrentScreen()
                        Column(modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 10.dp, end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Button(modifier = Modifier.size(40.dp), onClick = { globalScreenModel.zoomIn() }) {
                                Text("+")
                            }
                            Button(modifier = Modifier.size(40.dp), onClick = { globalScreenModel.zoomOut() }) {
                                Text("-")
                            }
                        }
                    }
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