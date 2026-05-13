package nz.kaimahi.app.ui.local

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.kaimahi.domain.EmdashProfile
import nz.kaimahi.ui.AppScreen

@Composable
fun DeploymentConfigsScreen(
    profiles: List<EmdashProfile>,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    AppScreen(
        title = "Remote emdash instances",
        navigationIcon = navigationIcon,
        actions = actions,
    ) {
        if (profiles.isEmpty()) {
            Text(
                "No instances configured. Add a profile under Settings → Emdash.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@AppScreen
        }
        profiles.forEach { p ->
            Text(
                text = "${p.env}: ${p.name}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = p.baseUrl,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
