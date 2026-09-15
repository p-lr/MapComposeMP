package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.plrapps.mapcompose.demo.viewmodels.FontFacesDemoVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object FontFacesDemo {
    @Composable
    fun Content()
}

/**
 * The map, plus the switch that says where its labels come from.
 *
 * On: the style declares a `font-faces` file and the labels are drawn from it, a grapheme cluster at
 * a time. Off: the same style without the block, so the labels come from its `glyphs` server.
 */
@Composable
fun FontFacesCommonUi(screenModel: FontFacesDemoVM) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = screenModel.useFontFile,
                onCheckedChange = { screenModel.onUseFontFileChanged(it) },
            )
            Text(
                text = if (screenModel.useFontFile) {
                    "labels from the declared font file"
                } else {
                    "labels from the glyphs server"
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        MapUI(
            Modifier,
            state = screenModel.state
        )
    }
}
