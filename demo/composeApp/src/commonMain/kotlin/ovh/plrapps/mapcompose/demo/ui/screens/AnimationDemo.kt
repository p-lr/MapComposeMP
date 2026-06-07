package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.plrapps.mapcompose.demo.viewmodels.AnimationDemoVM
import ovh.plrapps.mapcompose.ui.MapUI


expect object AnimationDemo {
    @Composable
    fun Content()
}

@Composable
fun AnimationCommonUi(viewModel: AnimationDemoVM) {
    val onRestart: () -> Unit = viewModel::startAnimation

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        MapUI(
            Modifier.weight(1f),
            state = viewModel.state
        )
        Button(onClick = {
            onRestart()
        }, Modifier.padding(8.dp)) {
            Text(text = "Start")
        }
    }
}
