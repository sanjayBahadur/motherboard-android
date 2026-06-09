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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        HomeScreen(
            settings = settings,
            onToggleBlocking = viewModel::setBlockingEnabled,
            onSessionLimitChange = viewModel::setSessionLimit,
            onCooldownMinutesChange = viewModel::setCooldownMinutes,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
