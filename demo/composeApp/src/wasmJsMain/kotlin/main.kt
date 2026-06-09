import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import ovh.plrapps.mapcompose.demo.ui.MainDestinations
import ovh.plrapps.mapcompose.demo.ui.MapAlone
import ovh.plrapps.mapcompose.demo.ui.demoDestinations
import ovh.plrapps.mapcompose.demo.ui.theme.DemoTheme

@Composable
fun WasmApp() {
    DemoTheme(false) {
        val navController = rememberNavController()
        Row {
            Column(modifier = Modifier.width(250.dp)) {
                Menu(
                    onNavigate = { route ->
                        navController.navigate(route) {
                            /* Master-detail: replace the detail pane rather than stacking onto it,
                             * popping back to the start destination (which is always the base). */
                            popUpTo(MapAlone)
                            launchSingleTop = true
                        }
                    }
                )
            }
            Column(modifier = Modifier.fillMaxSize()) {
                NavHost(navController, startDestination = MapAlone) {
                    demoDestinations()
                }
            }
        }
    }
}

@Composable
private fun Menu(onNavigate: (route: Any) -> Unit) {
    LazyColumn(Modifier.background(MaterialTheme.colorScheme.surface)) {
        MainDestinations.entries.forEach { dest ->
            item {
                Text(
                    text = dest.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(dest.route) }
                        .padding(16.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider(Modifier, thickness = 1.dp, color = DividerDefaults.color)
            }
        }
    }
}

// To run the wasmJs demo in a browser:
// ./gradlew :demo:composeApp:wasmJsBrowserDevelopmentRun
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "composeApplication") {
        WasmApp()
    }
}
