package com.wayfarer.android.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.ui.components.WfCard
import com.wayfarer.android.ui.components.WfDimens
import com.wayfarer.android.ui.components.WfErrorBanner
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun MarkDetailScreen(
    mark: LifeEventEntity,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: (label: String, note: String?) -> Unit,
    onDelete: () -> Unit,
    onEndRange: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable(mark.eventId) { mutableStateOf(mark.label) }
    var note by rememberSaveable(mark.eventId) { mutableStateOf(mark.note.orEmpty()) }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDeleteConfirm = false },
            title = { Text("确认删除？") },
            text = { Text("删除后将无法恢复。若已同步到服务器，将同时删除服务器端标记。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(WfDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(enabled = !busy, onClick = onBack) { Text("返回") }
            Spacer(modifier = Modifier.weight(1f))
            if (!editing) {
                FilledTonalButton(enabled = !busy, onClick = { editing = true }) { Text("编辑") }
            } else {
                FilledTonalButton(enabled = !busy, onClick = { editing = false }) { Text("取消编辑") }
            }
        }

        if (!error.isNullOrBlank()) {
            WfErrorBanner(message = error)
        }

        WfCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(WfDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = "标记详情", style = MaterialTheme.typography.titleLarge)

                val typeText =
                    when (mark.eventType.trim().uppercase()) {
                        LifeEventEntity.EventType.MARK_POINT -> "时间点"
                        LifeEventEntity.EventType.MARK_RANGE -> "区间标记"
                        else -> mark.eventType
                    }
                Text(
                    text = "类型：$typeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = "时间：${formatUtc(mark.startAtUtc)} → ${formatUtc(mark.endAtUtc) ?: "…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )

                if (!editing) {
                    Text(text = "标签：${mark.label}", style = MaterialTheme.typography.bodyMedium)
                    val n = mark.note?.trim().orEmpty()
                    if (n.isNotBlank()) {
                        Text(
                            text = "备注：$n",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("标签（必填）") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("备注（可选）") },
                        enabled = !busy,
                    )
                }

                val hasCoord = mark.latitudeWgs84 != null && mark.longitudeWgs84 != null
                if (hasCoord) {
                    Text(
                        text = "坐标：${formatCoord(mark.latitudeWgs84)} , ${formatCoord(mark.longitudeWgs84)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy && label.trim().isNotBlank(),
                    onClick = {
                        val l = label.trim()
                        val n = note.trim().takeIf { it.isNotBlank() }
                        onSave(l, n)
                    },
                ) {
                    Text(if (busy) "保存中…" else "保存")
                }
                Spacer(modifier = Modifier.weight(1f))
                FilledTonalButton(
                    enabled = !busy,
                    onClick = {
                        label = mark.label
                        note = mark.note.orEmpty()
                        editing = false
                    },
                ) {
                    Text("重置")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mark.eventType == LifeEventEntity.EventType.MARK_RANGE && mark.endAtUtc.isNullOrBlank()) {
                Button(enabled = !busy, onClick = onEndRange) {
                    Text("结束区间")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                enabled = !busy,
                onClick = { showDeleteConfirm = true },
            ) {
                Text("删除")
            }
        }
    }
}

private val MARK_DETAIL_DTF: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatUtc(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val i = runCatching { Instant.parse(value.trim()) }.getOrNull() ?: return value
    val zdt = ZonedDateTime.ofInstant(i, ZoneId.systemDefault())
    return MARK_DETAIL_DTF.format(zdt)
}

private fun formatCoord(v: Double?): String {
    if (v == null) return "-"
    return String.format("%.6f", v)
}

