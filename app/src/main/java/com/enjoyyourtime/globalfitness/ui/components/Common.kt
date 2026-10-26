package com.enjoyyourtime.globalfitness.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.domain.Classification
import com.enjoyyourtime.globalfitness.domain.Status
import com.enjoyyourtime.globalfitness.ui.labelRes
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.LocalChartSurface
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers

/** The app-wide snackbar (bottom of the main scaffold). */
val LocalAppSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Small colored dot. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Status chip: colored dot + text label (status is never conveyed by color alone). */
@Composable
fun StatusChip(classification: Classification, modifier: Modifier = Modifier) {
    StatusChip(stringResource(classification.level.labelRes), classification.status, modifier)
}

@Composable
fun StatusChip(label: String, status: Status, modifier: Modifier = Modifier) {
    val colors = ChartTheme.colors
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Dot(colors.status(status), size = 8.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Experimental" tag (unverified scale drivers, the direct band mode). */
@Composable
fun ExperimentalBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(stringResource(R.string.common_experimental), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Section header inside a page (announced as a heading by TalkBack). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(top = 12.dp, bottom = 2.dp, start = 4.dp)
            .semantics { heading() },
    )
}

/**
 * Top-level page: a compact title bar (the same on every section, tinted once content scrolls
 * under it) over a lazy list. The list ends above the ad banner and navigation bar (the main
 * scaffold's padding); [bottomPadding] adds room after the last item, e.g. for a floating action
 * button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabPage(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    bottomPadding: Dp = 24.dp,
    itemSpacing: Dp = 12.dp,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        TopAppBar(
            title = { TopBarTitle(title) },
            actions = actions,
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

/** Lets a child of a padded column (e.g. a chip row) span the [margin] on both sides. */
fun Modifier.fullBleed(margin: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedWidth) margin.roundToPx() * 2 else 0
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Filled tonal card used across the app; clickable when [onClick] is set. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val shape = MaterialTheme.shapes.large
    CompositionLocalProvider(LocalChartSurface provides containerColor) {
        if (onClick == null) {
            Card(modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
        } else {
            Card(
                onClick = onClick,
                modifier = modifier
                    .fillMaxWidth()
                    .semantics {
                        if (onClickLabel != null) {
                            onClick(label = onClickLabel) {
                                onClick()
                                true
                            }
                        }
                    },
                shape = shape,
                colors = colors,
                content = content,
            )
        }
    }
}

/** Icon + title + text + optional actions: problems, empty states and calls to action. */
@Composable
fun NoticeCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    AppCard(modifier, containerColor = containerColor) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (actions != null) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

/** Label / value line (tabular figures for the value). */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            trailing()
            Spacer(Modifier.width(8.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), textAlign = TextAlign.End)
    }
}

/**
 * Circular progress ring with a centered value. [progress] is clamped to 0..1; the ring is
 * decorative, [description] is what TalkBack reads.
 */
@Composable
fun ProgressRing(
    progress: Float?,
    value: String,
    unit: String,
    color: Color,
    trackColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    stroke: Dp = 10.dp,
) {
    val animated by animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), AppMotion.slowSpatial(), label = "ring")
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2f
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(trackColor, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            if (progress != null && animated > 0f) {
                drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val style = if (value.length > 4) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
            Text(value, style = style.merge(TabularNumbers), maxLines = 1)
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Minimal line of [values] (oldest first), scaled to its own range. Decorative. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val w = 2.5.dp.toPx()
        val usable = size.height - w
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * (size.width / (values.size - 1))
            val y = w / 2 + usable - ((v - min) / span * usable).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.last()
        drawCircle(color, w * 1.4f, Offset(size.width, w / 2 + usable - ((last - min) / span * usable).toFloat()))
    }
}
