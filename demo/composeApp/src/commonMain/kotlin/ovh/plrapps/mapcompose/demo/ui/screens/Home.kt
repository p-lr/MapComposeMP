package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import ovh.plrapps.mapcompose.demo.ui.MainDestinations
import ovh.plrapps.mapcomposemp.demo.Res
import ovh.plrapps.mapcomposemp.demo.app_name

expect object HomeScreen : Screen

@Composable
@OptIn(ExperimentalResourceApi::class)
fun HomeScreenCommonUi(onNavigate: (screen: Screen) -> Unit) {
    val demoListState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                backgroundColor = MaterialTheme.colors.primarySurface,
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            state = demoListState
        ) {
            MainDestinations.values().map { dest ->
                item {
                    Text(
                        text = dest.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(dest.screen) }
                            .padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                    Divider(thickness = 1.dp)
                }
            }
        }
    }
}