package ovh.plrapps.mapcompose.demo.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.round

/**
 * A callout which animates its entry with an overshoot scaling interpolator.
 */
@Composable
fun Callout(
    x: Double, y: Double,
    title: String,
    shouldAnimate: Boolean,
    onAnimationDone: () -> Unit
) {
    var animVal by remember { mutableStateOf(if (shouldAnimate) 0f else 1f) }
    LaunchedEffect(true) {
        if (shouldAnimate) {
            Animatable(0f).animateTo(
                targetValue = 1f,
                animationSpec = tween(250)
            ) {
                animVal = value
            }
            onAnimationDone()
        }
    }
    Surface(
        Modifier
            .alpha(animVal)
            .padding(10.dp)
            .graphicsLayer {
                scaleX = animVal
                scaleY = animVal
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
        shape = RoundedCornerShape(5.dp),
        tonalElevation = 10.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "position ${x.format()} , ${x.format()}",
                modifier = Modifier
                    .align(alignment = Alignment.CenterHorizontally)
                    .padding(top = 4.dp),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = Color.Black
            )
        }
    }
}

private fun Double.format(): String {
    return (round(this * 100) / 100).toString()
}
