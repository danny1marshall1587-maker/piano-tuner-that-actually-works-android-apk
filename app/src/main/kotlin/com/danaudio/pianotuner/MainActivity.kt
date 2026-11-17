package com.danaudio.pianotuner

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.danaudio.pianotuner.ui.PianoTunerApp
import com.danaudio.pianotuner.ui.theme.PianoTunerProTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      PianoTunerProTheme(darkTheme = true) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Color(0xFF070B12),
        ) {
          PianoTunerApp(
            onKeepScreenOnChanged = { keepAwake ->
              if (keepAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
              } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
              }
            }
          )
        }
      }
    }
  }
}
