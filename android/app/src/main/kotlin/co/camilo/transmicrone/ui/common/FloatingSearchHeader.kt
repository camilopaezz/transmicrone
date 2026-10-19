package co.camilo.transmicrone.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R

/** Search shares the navigation layer, leaving the full list available beneath it. */
@Composable
internal fun FloatingSearchHeader(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(Modifier.floatingHeaderPadding(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FloatingControlSurface {
            IconButton(onClick = onBack, modifier = Modifier.size(FloatingHeaderHeight)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        }
        FloatingControlSurface(Modifier.weight(1f)) {
            TextField(
                value = query, onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).semantics { contentDescription = hint },
                textStyle = MaterialTheme.typography.titleMedium,
                placeholder = { Text(stringResource(R.string.catalog_search), style = MaterialTheme.typography.titleMedium) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear_search))
                    }
                }) else null,
                singleLine = true, shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
