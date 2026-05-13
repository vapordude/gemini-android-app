package com.gemini.app.ui.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gemini.domain.TraceEvent

@Composable
fun TraceViewerScreen(
    events: List<TraceEvent>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Local traces (non-extractive)")
        events.forEach { ev ->
            Text(text = ev.toString(), modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
