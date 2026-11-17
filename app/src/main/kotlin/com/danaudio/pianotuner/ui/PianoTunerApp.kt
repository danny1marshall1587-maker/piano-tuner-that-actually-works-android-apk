package com.danaudio.pianotuner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danaudio.pianotuner.ui.screens.CyberDspTabScreen
import com.danaudio.pianotuner.ui.screens.ProfilesAndLogsTabScreen
import com.danaudio.pianotuner.ui.screens.StretchTabScreen
import com.danaudio.pianotuner.ui.screens.TunerTabScreen
import java.util.Locale

@Composable
fun PianoTunerApp(
  onKeepScreenOnChanged: (Boolean) -> Unit,
  viewModel: TunerViewModel = viewModel(),
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> viewModel.onAppForeground()
        Lifecycle.Event.ON_STOP -> viewModel.onAppBackground()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    viewModel.onPermissionResult(granted)
  }

  LaunchedEffect(Unit) {
    val granted = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) {
      viewModel.onPermissionResult(true)
    } else {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  LaunchedEffect(state.keepScreenOn) {
    onKeepScreenOnChanged(state.keepScreenOn)
  }

  LaunchedEffect(state.statusBanner) {
    val msg = state.statusBanner
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.dismissBanner()
    }
  }

  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF070B12))
      .safeDrawingPadding(),
    containerColor = Color(0xFF070B12),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopStudioBar(
        state = state,
        onToggleMic = {
          if (!state.hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          } else if (state.isListening) {
            viewModel.stopListening()
          } else {
            viewModel.startListening()
          }
        },
        onToggleAwake = { viewModel.toggleKeepScreenOn() },
      )
    },
    bottomBar = {
      NavigationBar(
        containerColor = Color(0xFF0D131F),
        contentColor = Color.White,
        modifier = Modifier.height(62.dp),
      ) {
        TunerTab.entries.forEach { tab ->
          val selected = state.selectedTab == tab
          NavigationBarItem(
            selected = selected,
            onClick = { viewModel.selectTab(tab) },
            icon = {
              Text(
                text = when (tab) {
                  TunerTab.TUNER -> "◎"
                  TunerTab.DSP -> "◈"
                  TunerTab.STRETCH -> "∿"
                  TunerTab.PROFILES -> "☰"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color(0xFF38BDF8) else Color(0xFF64748B),
              )
            },
            label = {
              Text(
                text = tab.label,
                fontSize = 10.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
              )
            },
            colors = NavigationBarItemDefaults.colors(
              selectedTextColor = Color(0xFF38BDF8),
              unselectedTextColor = Color(0xFF64748B),
              indicatorColor = Color(0xFF1E293B),
            ),
          )
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      if (!state.hasMicPermission && state.selectedTab == TunerTab.TUNER) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = "Microphone Access Needed",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          Spacer(Modifier.height(8.dp))
          Text(
            text = "Piano Tuner Pro needs microphone input for Cyber-Denoiser PRO, Fast AGC pitch tracking, inharmonicity measurement, and unison string checks.",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8),
          )
          Spacer(Modifier.height(16.dp))
          Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
            Text("Grant Microphone Permission")
          }
        }
      } else {
        when (state.selectedTab) {
          TunerTab.TUNER -> TunerTabScreen(state = state, viewModel = viewModel)
          TunerTab.DSP -> CyberDspTabScreen(state = state, viewModel = viewModel)
          TunerTab.STRETCH -> StretchTabScreen(state = state, viewModel = viewModel)
          TunerTab.PROFILES -> ProfilesAndLogsTabScreen(state = state, viewModel = viewModel)
        }
      }
    }
  }
}

@Composable
private fun TopStudioBar(
  state: TunerUiState,
  onToggleMic: () -> Unit,
  onToggleAwake: () -> Unit,
) {
  val profile = state.activeProfile
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color(0xFF0D131F))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = profile.name,
        fontSize = 14.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "A4=${String.format(Locale.US, "%.1f", profile.a4TargetHz)}Hz • ${profile.temperament.label} • ${if (state.denoiserParams.enabled) "CyberDN ON" else "DN OFF"}",
        fontSize = 10.sp,
        color = Color(0xFF94A3B8),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .background(if (state.keepScreenOn) Color(0xFF1E293B) else Color(0xFF0F172A))
          .clickable(onClick = onToggleAwake)
          .padding(horizontal = 8.dp, vertical = 5.dp),
      ) {
        Text(
          text = if (state.keepScreenOn) "AWAKE" else "SLEEP",
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          color = if (state.keepScreenOn) Color(0xFFFACC15) else Color(0xFF64748B),
        )
      }

      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .background(if (state.isListening) Color(0xFF064E3B) else Color(0xFF7F1D1D))
          .clickable(onClick = onToggleMic)
          .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (state.isListening) Color(0xFF22C55E) else Color(0xFFEF4444)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
          text = if (state.isListening) "LIVE" else "MIC OFF",
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
      }
    }
  }
}
