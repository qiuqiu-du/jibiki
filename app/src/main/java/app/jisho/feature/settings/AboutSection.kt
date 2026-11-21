package app.jisho.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.jisho.R
import app.jisho.core.designsystem.ja
import app.jisho.feature.search.InkModelState
import java.util.Locale

/**
 * The read-only part of 数据与关于 (design-spec 5): data packs, the handwriting model (with
 * deletion), storage and version. [model] is the handwriting panel's state; the figures come
 * from the files themselves ([AppInfo]).
 */
@Composable
internal fun AboutSection(info: AppInfo, model: InkModelState, onDeleteModel: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    SettingCard(R.string.settings_datapacks) {
        info.packs.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = CARD_INSET, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.title ?: stringResource(R.string.settings_core_pack),
                    style = if (p.title != null) MaterialTheme.typography.bodyLarge.ja() else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    stringResource(R.string.settings_pack_line, megabytes(p.bytes), p.builtAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // The folder is what counts: the panel's state is only known once it has been opened.
    val downloaded = info.modelBytes > 0 && model != InkModelState.NotDownloaded
    SettingCard(R.string.settings_handwriting) {
        Text(
            if (downloaded) stringResource(R.string.settings_handwriting_ready, megabytes(info.modelBytes))
            else stringResource(R.string.settings_handwriting_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = CARD_INSET),
        )
        if (downloaded) CardButtons { FilledTonalButton(onClick = { confirming = true }) { Text(stringResource(R.string.settings_handwriting_delete)) } }
    }

    SettingCard(R.string.settings_storage) {
        Column(Modifier.padding(horizontal = CARD_INSET), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    R.string.settings_storage_total,
                    megabytes(info.packBytes + info.modelBytes + info.userBytes),
                    megabytes(info.packBytes), megabytes(info.modelBytes), megabytes(info.userBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.settings_version, info.version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_handwriting_delete_title)) },
            text = { Text(stringResource(R.string.settings_handwriting_delete_text, megabytes(info.modelBytes))) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDeleteModel() }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** "44.4 MB"; one decimal is enough for every figure on this page. */
private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
