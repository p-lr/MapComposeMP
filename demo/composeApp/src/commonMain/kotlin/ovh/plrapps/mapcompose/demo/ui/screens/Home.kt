package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import ovh.plrapps.mapcompose.demo.ui.MainDestinations
import ovh.plrapps.mapcomposemp.demo.Res
import ovh.plrapps.mapcomposemp.demo.app_name

@Composable
fun HomeScreenCommonUi(onNavigate: (route: Any) -> Unit) {
    val demoListState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxWidth(),
            state = demoListState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MainDestinations.entries.forEach { dest ->
                item {
                    Button(
                        onClick = { onNavigate.invoke(dest.route) }
                    ) {
                        Text(text = dest.title)
                    }
                }
            }
        }
    }
}
