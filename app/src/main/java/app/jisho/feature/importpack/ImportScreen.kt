package app.jisho.feature.importpack

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jisho.R
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.datapack.DataPackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Copying(val name: String, val index: Int, val total: Int) : ImportUiState
    data class Error(val messageRes: Int, val arg: String = "") : ImportUiState
    data object Done : ImportUiState
}

class ImportViewModel(
    private val packs: DataPackManager,
    private val repository: DictionaryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state

    fun import(tree: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                packs.importFromTree(tree) { p ->
                    _state.value = when (p) {
                        is DataPackManager.Progress.Copying -> ImportUiState.Copying(p.name, p.index, p.total)
                        DataPackManager.Progress.NoCore -> ImportUiState.Error(R.string.import_no_core)
                        is DataPackManager.Progress.BadVersion -> ImportUiState.Error(R.string.import_bad_version, p.name)
                        DataPackManager.Progress.Done -> { repository.reopen(); ImportUiState.Done }
                    }
                }
            }.onFailure { _state.value = ImportUiState.Error(R.string.import_failed, it.message.orEmpty()) }
        }
    }
}

/** Where `adb push datapacks/ /sdcard/Download/jisho/` puts the packs; the picker opens there. */
private val DEFAULT_FOLDER: Uri = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents", "primary:Download/jisho",
)

@Composable
fun ImportScreen(viewModel: ImportViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    LaunchedEffect(state) { if (state is ImportUiState.Done) onDone() }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.import_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodyLarge)
        when (val s = state) {
            is ImportUiState.Copying -> {
                Text(stringResource(R.string.import_copying, s.name, s.index, s.total))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is ImportUiState.Error -> Text(
                stringResource(s.messageRes, s.arg), color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
        if (state !is ImportUiState.Copying) {
            Button(onClick = { picker.launch(DEFAULT_FOLDER) }) { Text(stringResource(R.string.import_pick_folder)) }
        }
    }
}
