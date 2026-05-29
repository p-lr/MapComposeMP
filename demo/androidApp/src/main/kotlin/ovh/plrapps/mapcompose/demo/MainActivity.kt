package ovh.plrapps.mapcompose.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ovh.plrapps.mapcompose.demo.ui.DemoNavHost
import ovh.plrapps.mapcompose.demo.ui.theme.DemoTheme
import ovh.plrapps.mapcompose.demo.utils.AndroidInjector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidInjector.init(this.application)
        setContent {
            DemoTheme {
                DemoNavHost()
            }
        }
    }
}
