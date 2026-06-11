package com.motherboard.focus.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.motherboard.focus.ui.screens.HomeScreen

@Composable
fun MotherboardApp(viewModel: DashboardViewModel) {
    val settings by viewModel.settings.collectAsState()
    val isServiceEnabled by viewModel.isAccessibilityServiceEnabled.collectAsState()
    val detectionState by viewModel.detectionState.collectAsState()
    val sessionCount by viewModel.sessionCount.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        HomeScreen(
            settings = settings,
            isServiceEnabled = isServiceEnabled,
            detectionState = detectionState,
            sessionCount = sessionCount,
            onToggleBlocking = viewModel::setBlockingEnabled,
            onSessionLimitChange = viewModel::setSessionLimit,
            onCooldownMinutesChange = viewModel::setCooldownMinutes,
            onToggleDebugLogging = viewModel::setDebugLogging,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
