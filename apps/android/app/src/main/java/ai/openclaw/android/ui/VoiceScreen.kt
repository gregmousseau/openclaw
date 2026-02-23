package ai.openclaw.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.openclaw.android.MainViewModel
import ai.openclaw.android.VoiceWakeMode

@Composable
fun VoiceScreen(viewModel: MainViewModel, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(16.dp)) {
  val voiceWakeMode by viewModel.voiceWakeMode.collectAsState()
  val voiceWakeStatusText by viewModel.voiceWakeStatusText.collectAsState()
  val voiceWakeIsListening by viewModel.voiceWakeIsListening.collectAsState()
  val talkEnabled by viewModel.talkEnabled.collectAsState()
  val wakeWords by viewModel.wakeWords.collectAsState()
  val elevenLabsApiKey by viewModel.talkElevenLabsApiKey.collectAsState()
  val voiceId by viewModel.talkVoiceId.collectAsState()

  var apiKeyInput by remember(elevenLabsApiKey) { mutableStateOf(elevenLabsApiKey) }
  var voiceIdInput by remember(voiceId) { mutableStateOf(voiceId) }
  var showApiKey by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // Status section
    item { Text("Status", style = MaterialTheme.typography.titleSmall) }
    item {
      ListItem(
        headlineContent = { Text("Voice Wake") },
        trailingContent = {
          Text(if (voiceWakeMode != VoiceWakeMode.Off) "Enabled" else "Disabled")
        },
      )
    }
    item {
      ListItem(
        headlineContent = { Text("Listener") },
        trailingContent = {
          Text(if (voiceWakeIsListening) "Listening" else "Idle")
        },
      )
    }
    item {
      Text(
        voiceWakeStatusText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    item {
      ListItem(
        headlineContent = { Text("Talk Mode") },
        trailingContent = {
          Text(if (talkEnabled) "Enabled" else "Disabled")
        },
      )
    }

    item { HorizontalDivider() }

    // Notes section
    item { Text("Notes", style = MaterialTheme.typography.titleSmall) }
    item {
      val noteText = when {
        wakeWords.isEmpty() -> "Add wake words in Settings."
        wakeWords.size == 1 -> "Say \u201c${wakeWords[0]} \u2026\u201d to trigger."
        wakeWords.size == 2 -> "Say \u201c${wakeWords[0]} \u2026\u201d or \u201c${wakeWords[1]} \u2026\u201d to trigger."
        else -> "Say \u201c${wakeWords.joinToString(" \u2026\u201d, \u201c")} \u2026\u201d to trigger."
      }
      Text(
        noteText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    item { HorizontalDivider() }

    // ElevenLabs section
    item { Text("ElevenLabs Voice", style = MaterialTheme.typography.titleSmall) }
    item {
      Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = apiKeyInput,
          onValueChange = {
            apiKeyInput = it
            viewModel.saveTalkElevenLabsApiKey(it)
          },
          label = { Text("API Key") },
          placeholder = { Text("sk_…") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          trailingIcon = {
            IconButton(onClick = { showApiKey = !showApiKey }) {
              Icon(
                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (showApiKey) "Hide key" else "Show key",
              )
            }
          },
        )
        OutlinedTextField(
          value = voiceIdInput,
          onValueChange = {
            voiceIdInput = it
            viewModel.saveTalkVoiceId(it)
          },
          label = { Text("Voice ID") },
          placeholder = { Text("Leave blank for default (Rachel)") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        Text(
          "High-quality voice via ElevenLabs. Get a free key at elevenlabs.io",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
