package io.ensomi.android.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.chart.ChartRequestRules
import io.ensomi.android.model.ModelPanel
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.ready

/**
 * The home screen: the songs the player added, each opening the screen that makes its chart. Until the chart model is
 * installed, its download panel comes first, so a first run reads as two steps: get the model, add songs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songs: List<Song>,
    model: ModelState,
    onAddSongs: () -> Unit,
    onOpenSong: (Song) -> Unit,
    onFindAgain: (Song) -> Unit,
    onRemoveSong: (Song) -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallModelFromFolder: (android.net.Uri) -> Unit,
    onRemoveModel: () -> Unit = {},
    onRetryModelOpen: () -> Unit = {},
    onShareModelDiagnostics: (() -> Unit)? = null,
    preparedSongs: Set<String> = emptySet(),
    onListen: (() -> Unit)? = null,
    /** Optional action in the top app bar. */
    extraAction: @Composable () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredSongs = remember(songs, searchQuery) { SongSearch.filter(songs, searchQuery) }
    val focusManager = LocalFocusManager.current
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    extraAction()
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (songs.isNotEmpty() && !model.ready) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.library_add_songs)) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    onClick = onAddSongs,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!model.ready && model !is ModelState.Checking) {
                    item(key = "model") {
                        ModelPanel(
                            state = model,
                            onDownload = onDownloadModel,
                            onCancel = onCancelDownload,
                            onRemove = onRemoveModel,
                            onInstallFromFolder = onInstallModelFromFolder,
                            onRetryOpen = onRetryModelOpen,
                            onShareDiagnostics = onShareModelDiagnostics,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (model.ready) {
                    item(key = "actions") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onAddSongs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.library_add_songs)) }
                            onListen?.let { open ->
                                OutlinedButton(onClick = open, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.recognition_title)) }
                            }
                        }
                    }
                }
                if (songs.isEmpty()) {
                    item(key = "empty") { EmptySongs(onAddSongs, !model.ready) }
                } else {
                    item(key = "search") {
                        SongSearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClear = { searchQuery = "" },
                            onSearch = { focusManager.clearFocus() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    item(key = "header") {
                        Text(
                            stringResource(R.string.library_songs),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                    if (filteredSongs.isEmpty()) {
                        item(key = "search_empty") {
                            Text(
                                stringResource(R.string.library_search_empty, searchQuery.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        }
                    } else {
                        items(filteredSongs, key = { it.id }) { song ->
                            SwipeToRemoveSongBox(song = song, onRemove = { onRemoveSong(song) }) {
                                if (song.missing) {
                                    MissingSongRow(
                                        song = song,
                                        onFindAgain = { onFindAgain(song) },
                                        onRemove = { onRemoveSong(song) },
                                    )
                                } else {
                                    SongRow(
                                        song = song,
                                        prepared = song.id in preparedSongs,
                                        onClick = { onOpenSong(song) },
                                        onRemove = { onRemoveSong(song) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = onClear) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.library_search_clear),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemoveSongBox(
    song: Song,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val currentOnRemove by rememberUpdatedState(onRemove)
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Plain remember (not rememberSwipeToDismissBoxState's rememberSaveable): LazyColumn's SaveableStateHolder must not
    // restore EndToStart and immediately dismiss the row again when Undo puts the song back.
    val state = remember(song.id, positionalThreshold) {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold)
    }
    var dismissed by remember(song.id) { mutableStateOf(false) }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        gesturesEnabled = !dismissed,
        onDismiss = { direction ->
            if (!dismissed && direction == SwipeToDismissBoxValue.EndToStart) {
                dismissed = true
                currentOnRemove()
            }
        },
        backgroundContent = {
            val active = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (active) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.library_remove_song, song.title),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        },
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(song: Song, prepared: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val length = ChartRequestRules.formatDuration(song.durationMs)
    val haptics = LocalHapticFeedback.current
    val removeLabel = stringResource(R.string.library_remove_song, song.title)
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    },
                )
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true })
                },
            headlineContent = { Text(song.title) },
            supportingContent = {
                Text(if (song.artist != null) stringResource(R.string.library_song_detail, song.artist, length) else length)
            },
            leadingContent = {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    if (prepared) Text(
                        stringResource(R.string.library_prepared),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (song.chartCount > 0) Text(
                        pluralStringResource(R.plurals.library_chart_count, song.chartCount, song.chartCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        SongContextMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }, onRemove = onRemove)
    }
}

@Composable
private fun SongContextMenu(expanded: Boolean, onDismiss: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_remove), color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                onDismiss()
                onRemove()
            },
        )
    }
}

/** A song whose file has gone: it says so and offers to pick the file anew or to forget the song. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MissingSongRow(song: Song, onFindAgain: () -> Unit, onRemove: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    },
                ),
        ) {
            ListItem(
                headlineContent = { Text(song.title) },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(R.string.library_file_not_found, song.fileName),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                leadingContent = {
                    Box(
                        Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
            Row(
                Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onFindAgain) { Text(stringResource(R.string.library_find_again)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.library_remove)) }
            }
        }
        SongContextMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }, onRemove = onRemove)
    }
}

@Composable
private fun EmptySongs(onAddSongs: () -> Unit, showAdd: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.library_empty_body), style = MaterialTheme.typography.bodyMedium)
            if (showAdd) Button(onClick = onAddSongs, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.library_add_songs), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
