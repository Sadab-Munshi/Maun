package org.maun.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.floor
import org.maun.messenger.crypto.QrCodec

/** The page layout every screen shares: optional back link, title, then content. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (onBack != null) {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text("‹ Back")
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                content()
            }
        }
    }
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A Maun ID split into groups of four so that two people can compare it by eye. */
fun groupedId(id: String): String =
    id.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

/**
 * A message's time the way the phone's language writes it: just the time for
 * today, date and time for anything older.
 */
fun formatWhen(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
    val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val format = if (day == today) {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    return format.format(Date(atMillis))
}

/** The first and last few characters, enough to tell contacts apart in a list. */
fun shortId(id: String): String = if (id.length <= 12) id else "${id.take(6)}…${id.takeLast(6)}"

@Composable
fun IdText(id: String, modifier: Modifier = Modifier) {
    Text(
        text = groupedId(id),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Draws [text] as a QR code. Always black on white with a clear border,
 * whatever the theme, because that is what cameras read best.
 */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrCodec.encode(text) }
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .semantics { contentDescription = description },
    ) {
        val modules = matrix.width
        val quietZone = 4
        // Whole pixels per module, so neighbouring modules meet without gaps.
        val cell = floor(size.minDimension / (modules + 2 * quietZone))
        if (cell < 1f) return@Canvas
        val left = floor((size.width - cell * modules) / 2)
        val top = floor((size.height - cell * modules) / 2)
        for (y in 0 until modules) {
            var x = 0
            while (x < modules) {
                if (!matrix[x, y]) {
                    x++
                    continue
                }
                var end = x
                while (end + 1 < modules && matrix[end + 1, y]) end++
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(left + x * cell, top + y * cell),
                    size = Size((end - x + 1) * cell, cell),
                )
                x = end + 1
            }
        }
    }
}
