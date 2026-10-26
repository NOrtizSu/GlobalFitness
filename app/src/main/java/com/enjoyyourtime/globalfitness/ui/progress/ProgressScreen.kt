@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.progress

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.components.TopBarTitle
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion

/** The tabs of Progreso. Cuerpo and Actividad are the former Body and Activity sections. */
enum class ProgressTab(@param:StringRes val label: Int) {
    MUSCLES(R.string.progress_tab_muscles),
    BODY(R.string.nav_body),
    ACTIVITY(R.string.nav_activity),
}

/**
 * The list state of each tab, hoisted to the main scaffold: the body list drives the "Add weight"
 * button, and every list keeps its position when switching tabs or sections.
 */
@Stable
class ProgressLists(val muscles: LazyListState, val body: LazyListState, val activity: LazyListState) {
    fun of(tab: ProgressTab): LazyListState = when (tab) {
        ProgressTab.MUSCLES -> muscles
        ProgressTab.BODY -> body
        ProgressTab.ACTIVITY -> activity
    }
}

@Composable
fun rememberProgressLists(): ProgressLists {
    val muscles = rememberLazyListState()
    val body = rememberLazyListState()
    val activity = rememberLazyListState()
    return remember(muscles, body, activity) { ProgressLists(muscles, body, activity) }
}

/**
 * Progreso: one title bar and the Músculos | Cuerpo | Actividad tabs over the selected page.
 * [tab] is hoisted so Hoy can open a given tab. Tabs only, no swiping: the charts below use
 * horizontal drags. Each page keeps its saved state (dialogs, chip choices) while another one is
 * shown. The bar and the tabs take the scrolled color together once the page leaves its top.
 */
@Composable
fun ProgressScreen(
    tab: ProgressTab,
    onTab: (ProgressTab) -> Unit,
    lists: ProgressLists,
    muscles: @Composable () -> Unit,
    body: @Composable () -> Unit,
    activity: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scrolled = lists.of(tab).canScrollBackward
    val barColor by animateColorAsState(if (scrolled) scheme.surfaceContainer else scheme.surface, AppMotion.effects(), label = "progressBar")
    val pages = rememberSaveableStateHolder()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { TopBarTitle(stringResource(R.string.nav_progress)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor),
        )
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = barColor) {
            ProgressTab.entries.forEach { t ->
                Tab(
                    selected = t == tab,
                    onClick = { onTab(t) },
                    text = { Text(stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        pages.SaveableStateProvider(tab.name) {
            when (tab) {
                ProgressTab.MUSCLES -> muscles()
                ProgressTab.BODY -> body()
                ProgressTab.ACTIVITY -> activity()
            }
        }
    }
}

/**
 * The scrolling list of one Progreso tab: the margins of a top-level page list, without a title
 * bar of its own (Progreso draws one bar above the tabs). The list ends above the ad banner and the
 * navigation bar; [bottomPadding] adds room after the last item, e.g. for a floating button.
 */
@Composable
fun ProgressTabContent(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    topPadding: Dp = 12.dp,
    bottomPadding: Dp = 24.dp,
    itemSpacing: Dp = 12.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}
