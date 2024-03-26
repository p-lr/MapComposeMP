package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.plrapps.mapcompose.demo.viewmodels.AnimationDemoVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen


object AnimationDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { AnimationDemoVM() }
        val onRestart: () -> Unit = screenModel::startAnimation

        Column(Modifier.fillMaxSize()) {
            MapUI(
                Modifier.weight(2f),
                state = screenModel.state
            )
            Button(onClick = {
                onRestart()
            }, Modifier.padding(8.dp)) {
                Text(text = "Start")
            }
        }
    }
}
