import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ovh.plrapps.mapcompose.demo.ui.MapAlone
import ovh.plrapps.mapcompose.demo.ui.demoDestinations
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreenCommonUi
import ovh.plrapps.mapcompose.demo.ui.theme.DemoTheme

@Composable
@Preview
fun desktopApp() {
    DemoTheme {
        val navController = rememberNavController()
        Row {
            Column(modifier = Modifier.width(250.dp)) {
                HomeScreenCommonUi(
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

// To run the desktop demo run this command in terminal: ./gradlew :demo:composeApp:run
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "MapComposeKMP") {
        desktopApp()
    }
}
