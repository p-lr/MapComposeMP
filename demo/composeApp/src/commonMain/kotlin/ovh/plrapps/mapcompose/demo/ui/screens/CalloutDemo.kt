package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.CalloutVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object CalloutDemo {
    @Composable
    fun Content()
}

@Composable
fun CalloutCommonUi(screenModel: CalloutVM) {
    Column(Modifier.fillMaxSize()) {
        MapUI(
            Modifier.weight(2f),
            state = screenModel.state
        )
    }
}
