package com.motherboard.focus.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motherboard.focus.storage.InterventionSettings

@Composable
fun HomeScreen(
    settings: InterventionSettings,
    onToggleBlocking: (Boolean) -> Unit,
    onSessionLimitChange: (Int) -> Unit,
    onCooldownMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Motherboard",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Shorts blocker for your attention span",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DashboardCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("YouTube Shorts Blocking", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.blockingEnabled) "Active" else "Paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.blockingEnabled,
                    onCheckedChange = onToggleBlocking,
                )
            }
        }

        DashboardCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "0 / ${settings.sessionLimit}",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Shorts this session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ExpandableSection(title = "Stats", initiallyExpanded = false) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow(label = "Shorts blocked today", value = "${settings.shortsBlockedToday}")
                StatRow(label = "Cooldowns triggered today", value = "${settings.cooldownsTriggeredToday}")
            }
        }

        ExpandableSection(title = "Settings", initiallyExpanded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HardLimitSlider(
                    value = settings.sessionLimit,
                    onValueChangeFinished = onSessionLimitChange,
                )
                CooldownSlider(
                    cooldownMillis = settings.cooldownDurationMillis,
                    onValueChangeFinished = onCooldownMinutesChange,
                )
            }
        }

        val context = LocalContext.current
        ExpandableSection(title = "Permissions", initiallyExpanded = false) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Accessibility: Not enabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Open Accessibility Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (expanded) "\u25BC" else "\u25B2",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HardLimitSlider(
    value: Int,
    onValueChangeFinished: (Int) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { sliderValue = value.toFloat() }

    Column {
        Text(
            "Hard limit: ${sliderValue.toInt()} Shorts",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue.toInt()) },
            valueRange = 3f..50f,
            steps = 46,
        )
    }
}

@Composable
private fun CooldownSlider(
    cooldownMillis: Long,
    onValueChangeFinished: (Int) -> Unit,
) {
    val cooldownMins = (cooldownMillis / 60_000L).toInt()
    var sliderValue by remember { mutableFloatStateOf(cooldownMins.toFloat()) }
    LaunchedEffect(cooldownMins) { sliderValue = cooldownMins.toFloat() }

    Column {
        Text(
            "Cooldown: ${sliderValue.toInt()} minutes",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue.toInt()) },
            valueRange = 1f..30f,
            steps = 28,
        )
    }
}
