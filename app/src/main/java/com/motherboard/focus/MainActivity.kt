package com.motherboard.focus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motherboard.focus.ui.DashboardViewModel
import com.motherboard.focus.ui.MotherboardApp
import com.motherboard.focus.ui.theme.MotherboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotherboardTheme {
                val viewModel: DashboardViewModel = viewModel()
                MotherboardApp(viewModel = viewModel)
            }
        }
    }
}
