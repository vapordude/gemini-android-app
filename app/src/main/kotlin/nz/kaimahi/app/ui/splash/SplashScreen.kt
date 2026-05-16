package nz.kaimahi.app.ui.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.kaimahi.ui.KaimahiMathSpiral
import nz.kaimahi.ui.LocalKaimahiColors

/**
 * Splash / cold-start screen — Frame 6 from `docs/design/frame-memory-splash-about.jsx`.
 * Pure-black background, the math-spiral mark rotating once per 60s
 * (calm focal, not marketing animation), wordmark + tagline below,
 * "Cathedral AI · Aotearoa" mono caps at the bottom.
 */
@Composable
fun KaimahiSplash(modifier: Modifier = Modifier) {
    val tokens = LocalKaimahiColors.current
    val infinite = rememberInfiniteTransition(label = "splash-spin")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050505)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KaimahiMathSpiral(
                size = 140.dp,
                modifier = Modifier.rotate(rotation),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Kaimahi",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 44.sp,
                color = tokens.textStrong,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your local AI worker.",
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 17.sp,
                color = tokens.muted,
                textAlign = TextAlign.Center,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 60.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CATHEDRAL AI · AOTEAROA",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5f.sp,
                letterSpacing = 1.8.sp,
                color = tokens.disabled,
            )
        }
    }
}
