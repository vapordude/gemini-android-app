package com.gemini.app.ui.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gemini.domain.EmdashProfile

@Composable
fun DeploymentConfigsScreen(
    profiles: List<EmdashProfile>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Remote emdash instances")
        if (profiles.isEmpty()) {
            Text(
                "No instances configured. Add a profile under Settings → Emdash.",
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        profiles.forEach { p ->
            Text(text = "${p.env}: ${p.name} (${p.baseUrl})", modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
