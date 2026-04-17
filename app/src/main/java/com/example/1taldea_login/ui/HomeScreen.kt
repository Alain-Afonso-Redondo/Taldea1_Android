package com.example.osislogin.ui

import android.app.DatePickerDialog
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.osislogin.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onLogout: () -> Unit,
    onChat: () -> Unit,
    canAccessChat: Boolean,
    chatUnreadCount: Int,
    onTableClick: (tableId: Int, komensalak: Int, erreserbaId: Int?, data: String, txanda: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var guestDialogTable by remember { mutableStateOf<Mahaia?>(null) }
    var guestInput by remember { mutableStateOf("1") }

    LaunchedEffect(Unit) { viewModel.loadTables() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val dateTimeFormatter = remember { SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault()) }
    val selectedDateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }

    val orange = remember { Color(0xFFF3863A) }
    val freeColor = remember { Color(0xFF1B345D) }
    val occupiedColor = remember { Color(0xFF5B1C1C) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTabletLandscape = isLandscape && configuration.screenWidthDp >= 840
    val bottomBarHeight = if (isLandscape) 110.dp else 150.dp

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                    painter = painterResource(R.drawable.logo_osis),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(100.dp).clickable { viewModel.loadTables() }
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                    text = dateTimeFormatter.format(now),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                    onClick = {
                        viewModel.logout()
                        onLogout()
                    }
            ) {
                Icon(
                        modifier = Modifier.size(40.dp),
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Saioa itxi",
                        tint = freeColor
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    val current = runCatching {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(uiState.selectedDateYmd)
                    }.getOrNull() ?: Date()
                    val calendar = Calendar.getInstance().apply { time = current }
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            val picked = Calendar.getInstance().apply {
                                set(year, month, dayOfMonth, 0, 0, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(picked.time)
                            viewModel.updateSelectedDate(ymd)
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }
            ) {
                val displayDate = runCatching {
                    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(uiState.selectedDateYmd)
                    selectedDateFormatter.format(parsed ?: Date())
                }.getOrElse { uiState.selectedDateYmd }
                Text(text = displayDate)
            }

            FilterChip(
                selected = uiState.selectedTxanda == "Bazkaria",
                onClick = { viewModel.updateSelectedTxanda("Bazkaria") },
                label = { Text("Bazkaria") }
            )
            FilterChip(
                selected = uiState.selectedTxanda == "Afaria",
                onClick = { viewModel.updateSelectedTxanda("Afaria") },
                label = { Text("Afaria") }
            )
        }

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            if (uiState.tables.isEmpty() && !uiState.isLoading && uiState.error.isNullOrBlank()) {
                Text(
                        text = "Ez dago mahairik.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val containerMaxWidth = maxWidth
                val containerMaxHeight = maxHeight
                val maxColumns = if (isLandscape) 10 else 5
                val gridPaddingHorizontal = if (isLandscape) 24.dp else 16.dp
                val gridPaddingVertical = if (isLandscape) 16.dp else 12.dp
                val sidebarWidth = 160.dp
                val gap = if (isTabletLandscape) 16.dp else 12.dp
                val gridAvailableWidth =
                        if (isTabletLandscape) (containerMaxWidth - sidebarWidth).coerceAtLeast(0.dp) else containerMaxWidth

                val tabletLayout =
                        remember(uiState.tables, gridAvailableWidth, containerMaxHeight, gap) {
                            computeBestTabletGridLayout(
                                    tables = uiState.tables,
                                    maxColumns = maxColumns,
                                    availableWidth = gridAvailableWidth,
                                    availableHeight = containerMaxHeight,
                                    gap = gap,
                                    paddingHorizontal = gridPaddingHorizontal,
                                    paddingVertical = gridPaddingVertical
                            )
                        }

                val columns = if (isTabletLandscape) tabletLayout?.columns ?: maxColumns else run {
                    val cellMinSize = 120.dp
                    val computedColumns = max(1, (containerMaxWidth / cellMinSize).toInt())
                    min(maxColumns, computedColumns)
                }

                val entries = remember(uiState.tables, columns) { buildGridEntries(uiState.tables, columns) }

                if (isTabletLandscape) {
                    val tabletGridScale = 0.90f
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center
                        ) {
                            TableGrid(
                                    entries = entries,
                                    columns = columns,
                                    gap = gap,
                                    gridPaddingHorizontal = gridPaddingHorizontal,
                                    gridPaddingVertical = gridPaddingVertical,
                                    freeColor = freeColor,
                                    occupiedColor = occupiedColor,
                                    onTableClick = { table ->
                                        val guests = table.pertsonaKopurua ?: table.pertsonaMax
                                        if (table.erreserbaId != null || table.isOccupied) {
                                            onTableClick(
                                                table.id,
                                                guests.coerceAtLeast(1),
                                                table.erreserbaId,
                                                uiState.selectedDateYmd,
                                                uiState.selectedTxanda
                                            )
                                        } else {
                                            guestDialogTable = table
                                            guestInput = "1"
                                        }
                                    },
                                    centerVertically = true,
                                    userScrollEnabled = false,
                                    modifier =
                                            Modifier
                                                    .width((tabletLayout?.gridWidth ?: gridAvailableWidth) * tabletGridScale)
                                                    .height(containerMaxHeight)
                            )
                        }
                        Column(
                                modifier = Modifier.width(160.dp).fillMaxHeight().background(orange),
                                verticalArrangement = Arrangement.SpaceEvenly,
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier =
                                    Modifier.drawBehind {
                                        drawCircle(
                                            brush =
                                                Brush.radialGradient(
                                                    colorStops =
                                                        arrayOf(
                                                            0f to Color.White.copy(alpha = 0.95f),
                                                            0.30f to Color.White.copy(alpha = 0.45f),
                                                            0.65f to Color.White.copy(alpha = 0.18f),
                                                            1f to Color.Transparent
                                                        ),
                                                    center = center,
                                                    radius = size.minDimension * 0.70f
                                                ),
                                            radius = size.minDimension * 0.70f,
                                            center = center
                                        )
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.size(72.dp).clickable(onClick = { viewModel.loadTables() }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        modifier = Modifier.size(56.dp),
                                        imageVector = Icons.Filled.Apps,
                                        contentDescription = "Mahaiak",
                                        tint = orange
                                    )
                                }
                            }

                            if (canAccessChat && chatUnreadCount > 0) {
                                val label = if (chatUnreadCount > 99) "99+" else chatUnreadCount.toString()
                                BadgedBox(badge = { Badge { Text(text = label) } }) {
                                    Box(
                                        modifier = Modifier.size(72.dp).clickable(onClick = onChat),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(56.dp),
                                            painter = painterResource(R.drawable.chat),
                                            contentDescription = "Chat",
                                            tint = Color.White
                                        )
                                    }
                                }
                            } else if (canAccessChat) {
                                Box(
                                    modifier = Modifier.size(72.dp).clickable(onClick = onChat),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        modifier = Modifier.size(56.dp),
                                        painter = painterResource(R.drawable.chat),
                                        contentDescription = "Chat",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                } else {
                    TableGrid(
                            entries = entries,
                            columns = columns,
                            gap = gap,
                            gridPaddingHorizontal = gridPaddingHorizontal,
                            gridPaddingVertical = gridPaddingVertical,
                            freeColor = freeColor,
                            occupiedColor = occupiedColor,
                            onTableClick = { table ->
                                val guests = table.pertsonaKopurua ?: table.pertsonaMax
                                if (table.erreserbaId != null || table.isOccupied) {
                                    onTableClick(
                                        table.id,
                                        guests.coerceAtLeast(1),
                                        table.erreserbaId,
                                        uiState.selectedDateYmd,
                                        uiState.selectedTxanda
                                    )
                                } else {
                                    guestDialogTable = table
                                    guestInput = "1"
                                }
                            },
                            centerVertically = true,
                            userScrollEnabled = true,
                            modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            uiState.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        if (!isTabletLandscape) {
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .height(bottomBarHeight)
                                    .background(orange)
                                    .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier =
                        Modifier.drawBehind {
                            drawCircle(
                                brush =
                                    Brush.radialGradient(
                                        colorStops =
                                            arrayOf(
                                                0f to Color.White.copy(alpha = 0.95f),
                                                0.30f to Color.White.copy(alpha = 0.45f),
                                                0.65f to Color.White.copy(alpha = 0.18f),
                                                1f to Color.Transparent
                                            ),
                                        center = center,
                                        radius = size.minDimension * 0.70f
                                    ),
                                radius = size.minDimension * 0.70f,
                                center = center
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier.size(72.dp).clickable(onClick = { viewModel.loadTables() }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            modifier = Modifier.size(56.dp),
                            imageVector = Icons.Filled.Apps,
                            contentDescription = "Mahaiak",
                            tint = orange
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                if (canAccessChat && chatUnreadCount > 0) {
                    val label = if (chatUnreadCount > 99) "99+" else chatUnreadCount.toString()
                    BadgedBox(badge = { Badge { Text(text = label) } }) {
                        Box(
                            modifier = Modifier.size(72.dp).clickable(onClick = onChat),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                modifier = Modifier.size(56.dp),
                                painter = painterResource(R.drawable.chat),
                                contentDescription = "Chat",
                                tint = Color.White
                            )
                        }
                    }
                } else if (canAccessChat) {
                    Box(
                        modifier = Modifier.size(72.dp).clickable(onClick = onChat),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            modifier = Modifier.size(56.dp),
                            painter = painterResource(R.drawable.chat),
                            contentDescription = "Chat",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    guestDialogTable?.let { table ->
        AlertDialog(
            onDismissRequest = { guestDialogTable = null },
            title = { Text("Komensalak") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Zenbat komensal izango dira mahai honetan?")
                    Text("Gehienez ${table.pertsonaMax} pertsona")
                    OutlinedTextField(
                        value = guestInput,
                        onValueChange = { value ->
                            guestInput = value.filter { it.isDigit() }.take(2)
                        },
                        singleLine = true,
                        label = { Text("Komensalak") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val guests = guestInput.toIntOrNull()
                        when {
                            guests == null || guests <= 0 -> {
                                Toast.makeText(context, "Sartu komensal kopuru zuzena", Toast.LENGTH_LONG).show()
                            }
                            guests > table.pertsonaMax -> {
                                Toast.makeText(
                                    context,
                                    "Mahai honek gehienez ${table.pertsonaMax} pertsona onartzen ditu",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            else -> {
                                guestDialogTable = null
                                onTableClick(
                                    table.id,
                                    guests,
                                    null,
                                    uiState.selectedDateYmd,
                                    uiState.selectedTxanda
                                )
                            }
                        }
                    }
                ) { Text("Jarraitu") }
            },
            dismissButton = {
                TextButton(onClick = { guestDialogTable = null }) { Text("Utzi") }
            }
        )
    }
}

private sealed interface GridEntry {
    data class Table(val table: Mahaia) : GridEntry
    data class Gap(val span: Int) : GridEntry
}

@Composable
private fun TableGrid(
        entries: List<GridEntry>,
        columns: Int,
        gap: Dp,
        gridPaddingHorizontal: Dp,
        gridPaddingVertical: Dp,
        freeColor: Color,
        occupiedColor: Color,
        onTableClick: (Mahaia) -> Unit,
        centerVertically: Boolean,
        userScrollEnabled: Boolean,
        modifier: Modifier
) {
    LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            userScrollEnabled = userScrollEnabled,
            modifier = modifier.padding(horizontal = gridPaddingHorizontal, vertical = gridPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement =
                    if (centerVertically) {
                        Arrangement.spacedBy(gap, alignment = Alignment.CenterVertically)
                    } else {
                        Arrangement.spacedBy(gap)
                    },
    ) {
        items(
                count = entries.size,
                key = { index ->
                    when (val entry = entries[index]) {
                        is GridEntry.Gap -> "gap_$index"
                        is GridEntry.Table -> entry.table.id
                    }
                },
                span = { index ->
                    when (val entry = entries[index]) {
                        is GridEntry.Gap -> GridItemSpan(entry.span)
                        is GridEntry.Table -> {
                            val blocks = max(1, ceil(entry.table.pertsonaMax / 4.0).toInt())
                            GridItemSpan(min(columns, blocks))
                        }
                    }
                }
        ) { index ->
            when (val entry = entries[index]) {
                is GridEntry.Gap -> Spacer(modifier = Modifier.fillMaxWidth().height(0.dp))
                is GridEntry.Table -> {
                    val table = entry.table
                    val bg = if (table.isOccupied) occupiedColor else freeColor
                    val blocks = max(1, ceil(table.pertsonaMax / 4.0).toInt())
                    val span = min(columns, blocks)
                    val rows = max(1, ceil(blocks / span.toDouble()).toInt())
                    val aspect = span.toFloat() / rows.toFloat()
                    Surface(
                            color = bg,
                            shape = RoundedCornerShape(14.dp),
                            modifier =
                                            Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(aspect)
                                            .clickable {
                                                onTableClick(table)
                                            }
                    ) {
                        Column(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                    text = table.label,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                    text =
                                            "${if (table.isOccupied) (table.pertsonaKopurua?.toString() ?: "—") else "—"}/${table.pertsonaMax}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildGridEntries(tables: List<Mahaia>, columns: Int): List<GridEntry> {
    val entries = ArrayList<GridEntry>(tables.size)
    var remaining = columns

    for (table in tables) {
        val blocks = max(1, ceil(table.pertsonaMax / 4.0).toInt())
        val span = min(columns, blocks)

        if (span > remaining) {
            entries.add(GridEntry.Gap(remaining))
            remaining = columns
        }

        entries.add(GridEntry.Table(table))
        remaining -= span
        if (remaining == 0) remaining = columns
    }

    return entries
}

private data class GridMetrics(val rows: Int, val heightUnits: Int)

private fun measureGridMetrics(tables: List<Mahaia>, columns: Int): GridMetrics {
    var remaining = columns
    var rowMaxUnits = 1
    var rows = 0
    var heightUnits = 0

    fun flushRow() {
        if (remaining == columns) return
        rows += 1
        heightUnits += rowMaxUnits
        remaining = columns
        rowMaxUnits = 1
    }

    for (table in tables) {
        val blocks = max(1, ceil(table.pertsonaMax / 4.0).toInt())
        val span = min(columns, blocks)
        if (span > remaining) {
            flushRow()
        }

        val tableUnits = max(1, ceil(blocks / span.toDouble()).toInt())
        rowMaxUnits = max(rowMaxUnits, tableUnits)
        remaining -= span

        if (remaining == 0) {
            flushRow()
        }
    }

    flushRow()
    return GridMetrics(rows = rows, heightUnits = max(1, heightUnits))
}

private data class TabletGridLayout(val columns: Int, val gridWidth: Dp, val gridHeight: Dp)

private fun computeBestTabletGridLayout(
        tables: List<Mahaia>,
        maxColumns: Int,
        availableWidth: Dp,
        availableHeight: Dp,
        gap: Dp,
        paddingHorizontal: Dp,
        paddingVertical: Dp
): TabletGridLayout? {
    if (tables.isEmpty()) return null

    var best: TabletGridLayout? = null
    var bestCell = 0.dp

    for (columns in 1..maxColumns) {
        val metrics = measureGridMetrics(tables, columns)
        if (metrics.rows <= 0) continue

        val usableWidth = (availableWidth - paddingHorizontal * 2 - gap * (columns - 1)).coerceAtLeast(0.dp)
        val usableHeight = (availableHeight - paddingVertical * 2 - gap * (metrics.rows - 1)).coerceAtLeast(0.dp)
        if (usableWidth <= 0.dp || usableHeight <= 0.dp) continue

        val cellFromWidth = usableWidth / columns
        val cellFromHeight = usableHeight / metrics.heightUnits
        val cell = if (cellFromWidth < cellFromHeight) cellFromWidth else cellFromHeight

        if (cell <= 0.dp) continue

        if (cell > bestCell) {
            bestCell = cell
            val gridWidth = paddingHorizontal * 2 + cell * columns + gap * (columns - 1)
            val gridHeight = paddingVertical * 2 + cell * metrics.heightUnits + gap * (metrics.rows - 1)
            best = TabletGridLayout(columns = columns, gridWidth = gridWidth, gridHeight = gridHeight)
        }
    }

    return best
}
