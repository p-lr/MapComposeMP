import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import ovh.plrapps.mapcompose.demo.ui.DemoNavHost

@Composable
fun iOSApp() {
    MaterialTheme {
        DemoNavHost()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController() = ComposeUIViewController { iOSApp() }
