import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ovh.plrapps.mapcompose.demo.ui.DemoNavHost
import ovh.plrapps.mapcompose.demo.ui.Home

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun iOSApp() {
    MaterialTheme {
        val navController = rememberNavController()
        val currentEntry by navController.currentBackStackEntryAsState()
        val showBack = currentEntry?.destination?.hasRoute<Home>() == false

        Scaffold(
            topBar = {
                if (showBack) {
                    TopAppBar(
                        title = {},
                        navigationIcon = {
                            Button(onClick = { navController.popBackStack() }) {
                                Text("Back")
                            }
                        }
                    )
                }
            },
            content = { padding ->
                DemoNavHost(navController, Modifier.padding(padding))
            },
        )
    }
}


@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController() = ComposeUIViewController { iOSApp() }
