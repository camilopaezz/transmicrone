package co.camilo.transmicrone.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun FloatingSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    // Scaffold already clears the navigation bar. Setup moves its actions into the
    // list while the IME is visible, so only the remaining keyboard inset is needed.
    SnackbarHost(hostState, modifier.windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) { data ->
        FloatingControlSurface(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag("floating-snackbar"),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = FloatingHeaderHeight).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    data.visuals.message,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton(onClick = { data.performAction() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(label, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (data.visuals.withDismissAction) {
                    IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, stringResource(androidx.compose.material3.R.string.m3c_snackbar_dismiss))
                    }
                }
            }
        }
    }
}
