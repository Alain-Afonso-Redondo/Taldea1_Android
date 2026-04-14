package com.example.osiskitchen.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay

@Composable
fun KitchenOrdersScreen(
    viewModel: KitchenOrdersViewModel,
    onGoPlatos: () -> Unit,
    onGoIngredientes: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    data class NoteDialogData(val title: String, val note: String)
    var noteDialog by remember { mutableStateOf<NoteDialogData?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(30_000)
        }
    }

    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (!error.isNullOrBlank()) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    KitchenChrome(
        selectedTab = KitchenTab.Comandas,
        onSelectTab = {
            when (it) {
                KitchenTab.Comandas -> Unit
                KitchenTab.Platos -> onGoPlatos()
                KitchenTab.Ingredientes -> onGoIngredientes()
            }
        },
        onLogoClick = { viewModel.refresh() },
        actionIcon = Icons.Filled.Refresh,
        actionIconContentDescription = "Eguneratu",
        onAction = { viewModel.refresh() }
    ) { modifier ->
        Surface(modifier = modifier.fillMaxSize()) {
            noteDialog?.let { dialog ->
                AlertDialog(
                    onDismissRequest = { noteDialog = null },
                    title = { Text(text = dialog.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    text = {
                        Text(
                            text = dialog.note,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = { TextButton(onClick = { noteDialog = null }) { Text(text = "Itxi") } }
                )
            }
            when {
                uiState.isLoading && uiState.groups.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.groups.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Ez daude komandak txanda honetarako",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.groups) { group ->
                            val reservaColor = Color(0xFF5B1C1C)
                            data class CategoryColumn(val id: Int?, val label: String)

                            val columns =
                                run {
                                    val distinct =
                                        group.komandak
                                            .mapNotNull { k ->
                                                val id = k.kategoriaId ?: return@mapNotNull null
                                                id to (k.kategoriaIzena?.takeIf { it.isNotBlank() } ?: "Kategoria $id")
                                            }
                                            .distinctBy { it.first }
                                            .sortedBy { it.first }
                                            .take(4)
                                            .map { (id, label) -> CategoryColumn(id = id, label = label) }
                                            .toMutableList()

                                    while (distinct.size < 4) distinct.add(CategoryColumn(id = null, label = ""))
                                    distinct
                                }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                    Surface(
                                        color = reservaColor,
                                        contentColor = Color.White,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                            Text(
                                                text = group.tablesLabel ?: "Erreserba ${group.erreserbaId}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val line =
                                                    listOfNotNull(
                                                        group.customerName?.takeIf { it.isNotBlank() },
                                                        group.txanda?.takeIf { it.isNotBlank() }
                                                    ).joinToString(" · ")
                                                if (line.isNotBlank()) {
                                                    Text(
                                                        text = line,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                                val people = group.personCount
                                                if (people != null) {
                                                    Text(
                                                        text = "$people",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        columns.forEach { col ->
                                            val komandas =
                                                if (col.id == null) {
                                                    emptyList()
                                                } else {
                                                    group.komandak
                                                        .filter { it.kategoriaId == col.id }
                                                        .sortedBy { it.id }
                                                }

                                            Column(modifier = Modifier.weight(1f)) {
                                                if (col.label.isNotBlank()) {
                                                    val categoryBg = lerp(Color(0xFF5B1C1C), Color.White, 0.25f)
                                                    Surface(
                                                        color = categoryBg,
                                                        contentColor = Color.White,
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(
                                                            text = col.label,
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                }

                                                komandas.forEach { komanda ->
                                                    val isUpdating = uiState.updatingKomandaIds.contains(komanda.id)
                                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = komanda.plateraIzena,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.Medium,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            val notes = komanda.oharrak
                                                            val hasNotes =
                                                                !notes.isNullOrBlank() && !notes.equals("null", ignoreCase = true)
                                                            if (hasNotes) {
                                                                IconButton(
                                                                    onClick = {
                                                                        noteDialog =
                                                                            NoteDialogData(
                                                                                title = komanda.plateraIzena,
                                                                                note = notes
                                                                            )
                                                                    },
                                                                    modifier = Modifier.width(32.dp).height(32.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Filled.ErrorOutline,
                                                                        contentDescription = "Oharra ikusi",
                                                                        tint = Color(0xFF5B1C1C)
                                                                    )
                                                                }
                                                            }
                                                            Text(
                                                                text = "x${komanda.kopurua}",
                                                                style = MaterialTheme.typography.headlineSmall,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        val baseButtonColor = Color(0xFF5B1C1C)
                                                        val doneBackground = lerp(baseButtonColor, Color.White, 0.65f)
                                                        val buttonContainerColor =
                                                            if (komanda.egoera) doneBackground else baseButtonColor
                                                        val buttonContentColor =
                                                            if (komanda.egoera) Color.Black else Color.White
                                                        FilledTonalButton(
                                                            onClick = { viewModel.setKomandaEgoera(komanda.id, !komanda.egoera) },
                                                            enabled = !isUpdating,
                                                            modifier = Modifier.fillMaxWidth().height(64.dp),
                                                            colors =
                                                                ButtonDefaults.filledTonalButtonColors(
                                                                    containerColor = buttonContainerColor,
                                                                    contentColor = buttonContentColor,
                                                                    disabledContainerColor = buttonContainerColor.copy(alpha = 0.5f),
                                                                    disabledContentColor = buttonContentColor.copy(alpha = 0.65f)
                                                                )
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.Center
                                                            ) {
                                                                if (isUpdating) {
                                                                    CircularProgressIndicator(
                                                                        modifier = Modifier.size(22.dp),
                                                                        strokeWidth = 3.dp,
                                                                        color = buttonContentColor
                                                                    )
                                                                    Spacer(modifier = Modifier.width(12.dp))
                                                                }
                                                                Text(
                                                                    text = if (komanda.egoera) "Atzera" else "Egina",
                                                                    style = MaterialTheme.typography.titleLarge,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
