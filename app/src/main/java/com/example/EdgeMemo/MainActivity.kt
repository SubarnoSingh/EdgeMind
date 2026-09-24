package com.example.EdgeMemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.EdgeMemo.presentation.ask.AskScreen
import com.example.EdgeMemo.presentation.memory.MemoryScreen
import com.example.EdgeMemo.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EdgeMindApplication).container
        container.onAppForeground()
        setContent {
            MyApplicationTheme {
                EdgeMindRoot(
                    askScreen = { AskScreen(viewModel(factory = container.askViewModelFactory)) },
                    memoryScreen = { MemoryScreen(viewModel(factory = container.memoryViewModelFactory)) },
                )
            }
        }
    }
}

private enum class Destination { ASK, MEMORY }

@Composable
private fun EdgeMindRoot(
    askScreen: @Composable () -> Unit,
    memoryScreen: @Composable () -> Unit,
) {
    var destination by remember { mutableStateOf(Destination.ASK) }

    Column {
        Box(modifier = Modifier.weight(1f)) {
            when (destination) {
                Destination.ASK -> askScreen()
                Destination.MEMORY -> memoryScreen()
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = destination == Destination.ASK,
                onClick = { destination = Destination.ASK },
                icon = { Icon(Icons.Filled.Search, contentDescription = "Ask") },
                label = { Text("Ask") },
            )
            NavigationBarItem(
                selected = destination == Destination.MEMORY,
                onClick = { destination = Destination.MEMORY },
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Memory") },
                label = { Text("Memory") },
            )
        }
    }
}