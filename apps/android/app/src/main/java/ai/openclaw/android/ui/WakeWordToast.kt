package ai.openclaw.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun WakeWordToast(
  command: String?,
  modifier: Modifier = Modifier,
) {
  var visibleText by remember { mutableStateOf<String?>(null) }
  var visible by remember { mutableStateOf(false) }

  LaunchedEffect(command) {
    if (command != null) {
      visibleText = command
      visible = true
      delay(2500)
      visible = false
      delay(300) // let exit animation finish
      visibleText = null
    }
  }

  AnimatedVisibility(
    visible = visible,
    enter = fadeIn() + slideInVertically { -it },
    exit = fadeOut() + slideOutVertically { -it },
    modifier = modifier,
  ) {
    Surface(
      color = Color.Black.copy(alpha = 0.60f),
      shape = RoundedCornerShape(24.dp),
    ) {
      Text(
        text = visibleText.orEmpty(),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        color = Color.White.copy(alpha = 0.92f),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}
