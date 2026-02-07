package com.wayfarer.android.ui.records

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.ui.components.WfDimens
import com.wayfarer.android.ui.components.WfErrorBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickMarkBottomSheet(
    visible: Boolean,
    activeRangeMark: LifeEventEntity?,
    recentLabels: List<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (type: String, label: String, note: String?, useLocation: Boolean) -> Unit,
    onEndActiveRange: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var type by rememberSaveable { mutableStateOf(LifeEventEntity.EventType.MARK_POINT) }
    var label by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var useLocation by rememberSaveable { mutableStateOf(true) }
    var noteExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(activeRangeMark?.eventId) {
        // 当用户正在做区间标记时，默认仍然用“点标记”，避免误触开始第二个区间。
        type = LifeEventEntity.EventType.MARK_POINT
    }

    fun submit() {
        val trimmed = label.trim()
        val noteTrimmed = note.trim().takeIf { it.isNotBlank() }
        onCreate(type, trimmed, noteTrimmed, useLocation)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = WfDimens.PagePadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "快速标记",
                style = MaterialTheme.typography.titleLarge,
            )

            if (activeRangeMark != null) {
                Text(
                    text = "正在进行区间标记：${activeRangeMark.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = onEndActiveRange,
                    ) {
                        Text("结束区间")
                    }
                }
                HorizontalDivider()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    enabled = !busy,
                    onClick = { type = LifeEventEntity.EventType.MARK_POINT },
                ) {
                    Text(if (type == LifeEventEntity.EventType.MARK_POINT) "时间点 ✓" else "时间点")
                }
                Button(
                    enabled = !busy && activeRangeMark == null,
                    onClick = { type = LifeEventEntity.EventType.MARK_RANGE },
                ) {
                    Text(if (type == LifeEventEntity.EventType.MARK_RANGE) "区间（开始） ✓" else "区间（开始）")
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标签（必填）") },
                placeholder = { Text("例如：通勤 / 买菜 / 散步") },
                singleLine = true,
                enabled = !busy,
            )

            if (recentLabels.isNotEmpty()) {
                val scroll = rememberScrollState()
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (s in recentLabels) {
                        AssistChip(
                            enabled = !busy,
                            onClick = { label = s },
                            label = { Text(s) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "记录当前位置",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = useLocation,
                    onCheckedChange = { useLocation = it },
                    enabled = !busy,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = { noteExpanded = !noteExpanded },
                ) {
                    Text(if (noteExpanded) "收起备注" else "添加备注")
                }
            }

            if (noteExpanded) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注（可选）") },
                    singleLine = false,
                    enabled = !busy,
                )
            }

            if (!error.isNullOrBlank()) {
                WfErrorBanner(message = error, onRetry = null)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = onDismiss,
                ) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    enabled = !busy && label.trim().isNotBlank(),
                    onClick = { submit() },
                ) {
                    Text(if (busy) "提交中…" else "创建")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

