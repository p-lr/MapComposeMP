import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import ovh.plrapps.mapcompose.demo.ui.DemoNavHost
import ovh.plrapps.mapcompose.demo.ui.theme.DemoTheme

@Composable
fun iOSApp() {
    DemoTheme {
        DemoNavHost()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController() = ComposeUIViewController { iOSApp() }
