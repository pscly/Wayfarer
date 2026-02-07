package com.wayfarer.android.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.ui.components.WfDimens
import com.wayfarer.android.ui.components.WfEmptyState
import com.wayfarer.android.ui.components.WfErrorBanner

enum class MarksRange {
    TODAY,
    LAST_7D,
    LAST_30D,
}

@Composable
fun MarksListScreen(
    range: MarksRange,
    loading: Boolean,
    error: String?,
    marks: List<LifeEventEntity>,
    onRangeChange: (MarksRange) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (LifeEventEntity) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(WfDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onBack) { Text("返回") }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onRefresh) { Text(if (loading) "刷新中…" else "刷新") }
        }

        Text(text = "标记", style = MaterialTheme.typography.titleLarge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RangeChip(
                selected = range == MarksRange.TODAY,
                label = "今天",
                onClick = { onRangeChange(MarksRange.TODAY) },
            )
            RangeChip(
                selected = range == MarksRange.LAST_7D,
                label = "近 7 天",
                onClick = { onRangeChange(MarksRange.LAST_7D) },
            )
            RangeChip(
                selected = range == MarksRange.LAST_30D,
                label = "近 30 天",
                onClick = { onRangeChange(MarksRange.LAST_30D) },
            )
        }

        if (!error.isNullOrBlank()) {
            WfErrorBanner(message = error, onRetry = onRefresh)
        }

        when {
            loading -> {
                Text(
                    text = "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            marks.isEmpty() -> {
                WfEmptyState(
                    title = "暂无标记",
                    body = "你可以在“记录”页使用「快速标记」随手记录时间点或区间。",
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = marks,
                        key = { it.eventId },
                    ) { m ->
                        MarkCard(mark = m, onClick = { onSelect(m) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(onClick = onClick) { Text(label) }
    } else {
        Button(onClick = onClick) { Text(label) }
    }
}
