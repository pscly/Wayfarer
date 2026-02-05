package com.wayfarer.android.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wayfarer.android.sync.SyncStateStore

@Composable
fun SyncBanner(
    snapshot: SyncStateStore.Snapshot,
    onOpenDetails: () -> Unit,
    onRetry: () -> Unit,
    onResumeBackfill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = snapshot.phase
    val show =
        phase == SyncStateStore.SyncPhase.BOOTSTRAP_RECENT ||
            phase == SyncStateStore.SyncPhase.BACKFILLING ||
            phase == SyncStateStore.SyncPhase.PAUSED ||
            phase == SyncStateStore.SyncPhase.ERROR

    if (!show) return

    val tone =
        when (phase) {
            SyncStateStore.SyncPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
            SyncStateStore.SyncPhase.PAUSED -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surfaceVariant
        }

    val onTone =
        when (phase) {
            SyncStateStore.SyncPhase.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenDetails() },
        tonalElevation = 1.dp,
        color = tone,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val title =
                when (phase) {
                    SyncStateStore.SyncPhase.BOOTSTRAP_RECENT -> "正在同步"
                    SyncStateStore.SyncPhase.BACKFILLING -> "正在回填历史"
                    SyncStateStore.SyncPhase.PAUSED -> "回填已暂停"
                    SyncStateStore.SyncPhase.ERROR -> "同步失败"
                    else -> "同步"
                }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = onTone,
            )

            val subtitle =
                when (phase) {
                    SyncStateStore.SyncPhase.ERROR -> snapshot.lastError
                    else -> snapshot.progressText
                }

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = onTone,
                    maxLines = 2,
                )
            }

            if (phase == SyncStateStore.SyncPhase.BOOTSTRAP_RECENT || phase == SyncStateStore.SyncPhase.BACKFILLING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(2.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (phase) {
                    SyncStateStore.SyncPhase.ERROR -> {
                        Button(onClick = onRetry) { Text("重试") }
                        FilledTonalButton(onClick = onOpenDetails) { Text("详情") }
                    }

                    SyncStateStore.SyncPhase.PAUSED -> {
                        Button(onClick = onResumeBackfill) { Text("继续回填") }
                        FilledTonalButton(onClick = onOpenDetails) { Text("详情") }
                    }

                    else -> {
                        FilledTonalButton(onClick = onOpenDetails) { Text("查看") }
                    }
                }
            }
        }
    }
}

