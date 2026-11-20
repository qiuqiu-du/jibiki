package app.jisho.feature.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jisho.R
import app.jisho.core.designsystem.ja
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface InkModelState {
    data object Checking : InkModelState
    data object NotDownloaded : InkModelState
    data object Downloading : InkModelState
    data object Ready : InkModelState
    data class Failed(val message: String) : InkModelState
}

data class HandwritingUiState(
    val model: InkModelState = InkModelState.Checking,
    val strokes: List<List<InkPoint>> = emptyList(),
    val candidates: List<String> = emptyList(),
)

/** Whether the handwriting model was there last time (kept in the settings store). */
interface InkModelMemory {
    suspend fun wasReady(): Boolean
    suspend fun remember(ready: Boolean)
}

class HandwritingViewModel(private val recognizer: InkEngine, private val memory: InkModelMemory) : ViewModel() {
    private val _state = MutableStateFlow(HandwritingUiState())
    // Read as soon as the search screen exists, so opening the panel does not wait for the disk.
    private val remembered = viewModelScope.async { runCatching { memory.wasReady() }.getOrDefault(false) }
    val state: StateFlow<HandwritingUiState> = _state
    private var size = 1f to 1f
    private var preContext = ""
    private var recognizing: Job? = null

    /**
     * ML Kit's first check in each app run takes about 0.7 s. If the model was there last time,
     * the panel is ready at once and the check runs behind it; only if the model has gone since
     * (app data cleared) does the panel switch to the download button.
     */
    fun checkModel() = viewModelScope.launch {
        if (_state.value.model == InkModelState.Ready || _state.value.model == InkModelState.Downloading) return@launch
        if (remembered.await()) {
            _state.update { it.copy(model = InkModelState.Ready) }
            if (runCatching { recognizer.isModelReady() }.getOrNull() == false) {
                memory.remember(false)
                _state.update { it.copy(model = InkModelState.NotDownloaded) }
            }
            return@launch
        }
        val ready = runCatching { recognizer.isModelReady() }
        if (ready.getOrNull() == true) memory.remember(true)
        _state.update {
            it.copy(model = ready.fold({ ok -> if (ok) InkModelState.Ready else InkModelState.NotDownloaded }) { e -> InkModelState.Failed(e.message.orEmpty()) })
        }
    }

    fun download() = viewModelScope.launch {
        _state.update { it.copy(model = InkModelState.Downloading) }
        val r = runCatching { recognizer.downloadModel() }
        if (r.isSuccess) memory.remember(true)
        _state.update { it.copy(model = r.fold({ InkModelState.Ready }) { e -> InkModelState.Failed(e.message.orEmpty()) }) }
    }

    fun addStroke(points: List<InkPoint>, width: Float, height: Float, typed: String) {
        if (points.isEmpty()) return
        size = width to height
        preContext = typed
        _state.update { it.copy(strokes = it.strokes + listOf(points)) }
        recognize()
    }

    fun undo() {
        _state.update { it.copy(strokes = it.strokes.dropLast(1)) }
        recognize()
    }

    fun clear() {
        recognizing?.cancel()
        _state.update { it.copy(strokes = emptyList(), candidates = emptyList()) }
    }

    private fun recognize() {
        recognizing?.cancel()
        val strokes = _state.value.strokes
        if (strokes.isEmpty()) { _state.update { it.copy(candidates = emptyList()) }; return }
        recognizing = viewModelScope.launch {
            val result = runCatching { recognizer.recognize(strokes, size.first, size.second, preContext) }.getOrDefault(emptyList())
            _state.update { if (it.strokes == strokes) it.copy(candidates = result) else it }
        }
    }

    override fun onCleared() = recognizer.close()
}

/** Bottom sheet: candidate row, writing area, undo / clear (design-spec 4.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingPanel(
    viewModel: HandwritingViewModel,
    typed: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.checkModel() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val m = state.model) {
                InkModelState.Ready -> {
                    CandidateBar(state.candidates) { onPick(it); viewModel.clear() }
                    WritingArea(state.strokes) { pts, w, h -> viewModel.addStroke(pts, w, h, typed) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.hw_undo), enabled = state.strokes.isNotEmpty(), onClick = viewModel::undo)
                        PillButton(stringResource(R.string.hw_clear), enabled = state.strokes.isNotEmpty(), onClick = viewModel::clear)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.hw_close)) }
                    }
                }
                InkModelState.Checking -> Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
                InkModelState.NotDownloaded -> Message(stringResource(R.string.hw_download_prompt), stringResource(R.string.hw_download), viewModel::download)
                InkModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text(stringResource(R.string.hw_downloading))
                }
                is InkModelState.Failed -> Message(stringResource(R.string.hw_failed, m.message), stringResource(R.string.hw_retry), viewModel::download)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        modifier = Modifier.height(36.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Recognized candidates as chips; the best guess is highlighted. */
@Composable
private fun CandidateBar(candidates: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.hw_candidates_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        candidates.forEachIndexed { i, c ->
            Surface(
                onClick = { onPick(c) },
                shape = RoundedCornerShape(12.dp),
                color = if (i == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (i == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            ) {
                Text(c, style = MaterialTheme.typography.titleLarge.ja(), modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun WritingArea(strokes: List<List<InkPoint>>, onStroke: (List<InkPoint>, Float, Float) -> Unit) {
    val current = remember { mutableStateListOf<Offset>() }
    val ink = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guide = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, guide, RoundedCornerShape(24.dp))
            // Faint dashed cross, like the guide lines of a writing grid.
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx()))
                val c = guide.copy(alpha = 0.6f)
                drawLine(c, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx(), pathEffect = dash)
                drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = dash)
            },
    ) {
        if (strokes.isEmpty() && current.isEmpty()) {
            Text(
                stringResource(R.string.hw_hint),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                color = hintColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Canvas(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val points = ArrayList<InkPoint>()
                    val down = awaitFirstDown()
                    fun add(p: Offset, t: Long) { points += InkPoint(p.x, p.y, t); current += p }
                    add(down.position, down.uptimeMillis)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        add(change.position, change.uptimeMillis)
                        change.consume()
                        if (!change.pressed) break
                    }
                    onStroke(points, size.width.toFloat(), size.height.toFloat())
                    current.clear()
                }
            },
        ) {
            val style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            (strokes.map { s -> s.map { Offset(it.x, it.y) } } + listOf(current.toList())).forEach { pts ->
                if (pts.isEmpty()) return@forEach
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, ink, style = style)
            }
        }
    }
}
