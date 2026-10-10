package co.camilo.transmicrone.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import co.camilo.transmicrone.R

fun destinationOptions(value: String): List<String> = value.split("||")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

@Composable
fun ServiceDestinationList(value: String, @StringRes label: Int, singleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val destinations = destinationOptions(value)
    if (destinations.size <= 1) {
        Text(destinations.firstOrNull().orEmpty(), style = singleStyle)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            destinations.forEach { destination ->
                Text(
                    "${stringResource(label)} $destination",
                    style = singleStyle,
                )
            }
        }
    }
}
