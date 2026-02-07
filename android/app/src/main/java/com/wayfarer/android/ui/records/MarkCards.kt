package com.wayfarer.android.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wayfarer.android.db.LifeEventEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun MarkCard(
    mark: LifeEventEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = mark.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = markTypeLabel(mark),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val subtitle = markTimeSubtitle(mark)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            val note = mark.note?.trim().orEmpty()
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun markTypeLabel(mark: LifeEventEntity): String {
    return when (mark.eventType.trim().uppercase()) {
        LifeEventEntity.EventType.MARK_POINT -> "时间点"
        LifeEventEntity.EventType.MARK_RANGE -> if (mark.endAtUtc.isNullOrBlank()) "区间（进行中）" else "区间"
        else -> mark.eventType.trim().ifBlank { "事件" }
    }
}

private val MARK_DTF: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun markTimeSubtitle(mark: LifeEventEntity): String {
    val zone = ZoneId.systemDefault()
    val start = parseInstant(mark.startAtUtc) ?: return ""
    val end = parseInstant(mark.endAtUtc)

    val startText = formatInstant(start, zone)
    val endText = end?.let { formatInstant(it, zone) } ?: "…"
    val dur =
        runCatching {
            val endForDur = end ?: Instant.now()
            val sec = Duration.between(start, endForDur).seconds.coerceAtLeast(0L)
            formatDurationShort(sec)
        }.getOrDefault("")

    return if (dur.isBlank()) {
        "$startText → $endText"
    } else {
        "$startText → $endText  ·  $dur"
    }
}

private fun parseInstant(s: String?): Instant? {
    if (s.isNullOrBlank()) return null
    return runCatching { Instant.parse(s.trim()) }.getOrNull()
}

private fun formatInstant(i: Instant, zone: ZoneId): String {
    val zdt = ZonedDateTime.ofInstant(i, zone)
    return MARK_DTF.format(zdt)
}

private fun formatDurationShort(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h}小时${m}分"
        else -> "${m}分"
    }
}

