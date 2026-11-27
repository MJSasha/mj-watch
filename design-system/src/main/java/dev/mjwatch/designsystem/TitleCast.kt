package dev.mjwatch.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import dev.mjwatch.core.CastMember
import dev.mjwatch.core.Config
import dev.mjwatch.core.HttpFailure
import dev.mjwatch.core.managed
import dev.mjwatch.core.Metadata
import kotlinx.coroutines.CancellationException

@Composable
internal fun TitleCast(
    title: Metadata,
    config: Config,
    model: WatchModel,
    tv: Boolean,
    inset: Dp,
    header: @Composable () -> Unit,
) = key(title.id, config) {
    var actor by remember { mutableStateOf<CastMember?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val cast = rememberCredits(attempt) { model.cast(title.id, config) }
    LazyColumn(Modifier.fillMaxSize().testTag("detail-cast-list"), contentPadding = PaddingValues(bottom = 24.dp + LocalFloatingNavigationClearance.current)) {
        item(key = "detail-header") { header() }
        item {
            val members = cast?.getOrNull().orEmpty()
            if (members.isEmpty()) {
                Box(Modifier.padding(horizontal = inset, vertical = 16.dp)) {
                    val error = cast?.exceptionOrNull()
                    val message = if (config.managed && error is HttpFailure && error.status == 404) {
                        "Для просмотра актёрского состава обновите сервер MJWatch."
                    } else {
                        "Не удалось загрузить актёрский состав."
                    }
                    CreditsStatus(cast, "Загрузка актёрского состава…", "Актёрский состав пока недоступен.",
                        message, tv) { attempt++ }
                }
            } else {
                val cards: LazyListScope.() -> Unit = {
                    items(members, key = { it.id }) { member ->
                        ActorCard(member, tv) { actor = member }
                    }
                }
                val spacing = 12.dp
                Box(Modifier.padding(vertical = 16.dp)) {
                    if (tv) TvCarousel(inset, spacing, content = cards)
                    else LazyRow(contentPadding = PaddingValues(horizontal = inset),
                        horizontalArrangement = Arrangement.spacedBy(spacing), content = cards)
                }
            }
        }
    }
    actor?.let { member ->
        ActorFilmography(member, config, model, tv, onClose = { actor = null }) {
            actor = null
            if (it.id != title.id) model.metadata(it)
        }
    }
}

@Composable
private fun ActorCard(actor: CastMember, tv: Boolean, onClick: () -> Unit) {
    NativeCard(tv, Modifier.width(if (tv) tvUnit(168f) else 132.dp).testTag("cast-member-${actor.id}"), onClick = onClick) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            actor.profile?.let { profile ->
                AsyncImage(profile, null, LocalImages.current, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(actor.name, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(actor.character, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActorFilmography(
    actor: CastMember,
    config: Config,
    model: WatchModel,
    tv: Boolean,
    onClose: () -> Unit,
    onTitle: (Metadata) -> Unit,
) {
    var attempt by remember(actor.id) { mutableIntStateOf(0) }
    val filmography = rememberCredits(attempt) {
        model.actorFilmography(actor.id, config).sortedByDescending { it.year }
    }
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.8f)
                .testTag("actor-filmography"),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(actor.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        Text("Фильмография", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)
                        .tvFocusRing(tv, RoundedCornerShape(24.dp))) {
                        Icon(Icons.Outlined.Close, "Закрыть фильмографию")
                    }
                }
                val titles = filmography?.getOrNull().orEmpty()
                if (titles.isEmpty()) {
                    CreditsStatus(filmography, "Загрузка фильмографии…", "Фильмография пока недоступна.",
                        "Не удалось загрузить фильмографию.", tv) { attempt++ }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (tv) 160.dp else 110.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(if (tv) 4.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(titles, key = { it.id }) { title ->
                            MetadataCard(title, config, tv, modifier = Modifier.testTag("filmography-title-${title.id}")) {
                                onTitle(title)
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(tv) { if (tv) closeFocus.requestFocus() }
    }
}

@Composable
private fun <T> rememberCredits(attempt: Int, load: suspend () -> List<T>): Result<List<T>>? {
    var result by remember(attempt) { mutableStateOf<Result<List<T>>?>(null) }
    LaunchedEffect(attempt) {
        result = try {
            Result.success(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    return result
}

@Composable
private fun CreditsStatus(
    result: Result<*>?,
    loading: String,
    empty: String,
    error: String,
    tv: Boolean,
    retry: () -> Unit,
) {
    when {
        result == null -> LoadingIndicator(loading)
        result.isFailure -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Action("Повторить загрузку", tv, secondary = true, onClick = retry)
        }
        else -> Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
