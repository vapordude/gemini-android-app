package nz.kaimahi.app.ui.drawer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Material 3 icons for drawer rows. The design system draws bespoke
 * geometric icons in SVG (frame-shell.jsx); Material icons are a
 * pragmatic stand-in until/unless we hand-author Compose vector
 * equivalents. They read at the right weight and stay on-family at
 * 20dp.
 */
internal object DrawerIcons {
    val Chat: ImageVector = Icons.Filled.ChatBubbleOutline
    val FrontPage: ImageVector = Icons.AutoMirrored.Filled.Article
    val Todo: ImageVector = Icons.Filled.Checklist
    val Memory: ImageVector = Icons.Filled.AccountTree
    val Trace: ImageVector = Icons.Filled.Insights
    val Deploy: ImageVector = Icons.Filled.Cloud
    val Model: ImageVector = Icons.Filled.Memory
    val Settings: ImageVector = Icons.Filled.Settings
    val About: ImageVector = Icons.Filled.Info
}
