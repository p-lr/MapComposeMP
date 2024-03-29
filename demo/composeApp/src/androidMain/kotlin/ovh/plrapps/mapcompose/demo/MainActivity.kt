package ovh.plrapps.mapcompose.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cafe.adriel.voyager.navigator.Navigator
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreen
import ovh.plrapps.mapcompose.demo.ui.theme.MapComposeTheme
import ovh.plrapps.mapcompose.demo.utils.AndroidInjector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjector.init(this.application)
        setContent {
            MapComposeTheme {
                Navigator(HomeScreen)
            }
        }
    }
}