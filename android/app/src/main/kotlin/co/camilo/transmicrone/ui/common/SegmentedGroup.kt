package co.camilo.transmicrone.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Font scale at which row controls with several actions move beneath the text. */
internal const val SegmentedStackFontScale = 1.5f

/** Space between rows of one segmented group. */
internal val SegmentedRowGap = 2.dp

/** Space between consecutive segmented groups. */
internal val SegmentedGroupGap = 24.dp

private val SegmentedInnerCorner = CornerSize(4.dp)

/** Outer corners follow the theme's large shape; inner corners stay tight so rows read as one group. */
@Composable
internal fun segmentedRowShape(index: Int, count: Int): Shape {
    val outer = MaterialTheme.shapes.large
    return RoundedCornerShape(
        topStart = if (index == 0) outer.topStart else SegmentedInnerCorner,
        topEnd = if (index == 0) outer.topEnd else SegmentedInnerCorner,
        bottomEnd = if (index == count - 1) outer.bottomEnd else SegmentedInnerCorner,
        bottomStart = if (index == count - 1) outer.bottomStart else SegmentedInnerCorner,
    )
}

/** Section label above a segmented group. */
@Composable
internal fun SegmentedSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = SegmentedGroupGap, bottom = 8.dp))
}

/**
 * One opaque content-layer segment. Pass the row's accessible action (selectable, toggleable,
 * clickable) as [rowModifier] so the whole row is a single control and its ripple follows the shape.
 */
@Composable
internal fun SegmentedRow(
    index: Int,
    count: Int,
    title: String,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    stackTrailing: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else SegmentedRowGap),
        shape = segmentedRowShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            rowModifier.fillMaxWidth().heightIn(min = if (supporting == null) 56.dp else 72.dp)
                .padding(start = 16.dp, end = if (trailing == null) 16.dp else 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Large text keeps the full row width for the title; the controls move beneath it.
                if (stackTrailing && trailing != null) Row(Modifier.align(Alignment.End), content = trailing)
            }
            if (!stackTrailing) trailing?.invoke(this)
        }
    }
}
