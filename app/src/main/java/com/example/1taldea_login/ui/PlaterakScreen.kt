package com.example.osislogin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
fun PlaterakScreen(
    tableId: Int,
    fakturaId: Int,
    kategoriId: Int,
    viewModel: PlaterakViewModel,
    onLogout: () -> Unit,
    onChat: () -> Unit,
    chatUnreadCount: Int,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp >= configuration.screenHeightDp

    LaunchedEffect(tableId, fakturaId, kategoriId) {
        viewModel.load(tableId = tableId, fakturaId = fakturaId, kategoriId = kategoriId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopAutoRefresh() }
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onBack,
        rightIconResId = com.example.osislogin.R.drawable.chat,
        rightIconContentDescription = "Chat",
        onRightAction = onChat,
        rightBadgeCount = chatUnreadCount
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            val cellMinSize = if (isLandscape) 240.dp else 200.dp
            val columns = remember(configuration) {
                max(2, (configuration.screenWidthDp / cellMinSize.value).toInt())
            }

            var noteDialogFor by remember { mutableStateOf<Platera?>(null) }
            var noteText by remember { mutableStateOf("") }

            Column(modifier = Modifier.fillMaxSize()) {
                val tableLabel = uiState.tableLabel?.takeIf { it.isNotBlank() } ?: tableId.toString()
                val guestsText = uiState.guestCount?.toString() ?: "—"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.TableRestaurant,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tableLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Filled.People,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = guestsText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = uiState.platerak, key = { it.id }) { platera ->
                        val komandas = uiState.komandakByPlateraId[platera.id].orEmpty()
                        val committedQty = komandas.sumOf { it.kopurua }
                        val committedDoneQty = komandas.filter { it.egoera != 0 }.sumOf { it.kopurua }
                        val qty = uiState.pendingQtyByPlateraId[platera.id] ?: committedQty
                        val pendingNote = uiState.pendingNotesByPlateraId[platera.id]
                        val hasNote = !pendingNote.isNullOrBlank() || komandas.any { !it.oharrak.isNullOrBlank() }
                        val orange = remember { Color(0xFFF3863A) }

                        Surface(
                            color = Color(0xFF1B345D),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = platera.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "${platera.price}€",
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Stock: ${platera.stock}",
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val minusEnabled = qty > committedDoneQty
                                    val minusInteraction = remember { MutableInteractionSource() }
                                    val minusPressed by minusInteraction.collectIsPressedAsState()
                                    val minusBg by animateColorAsState(
                                        targetValue = if (!minusEnabled) Color.Transparent else if (minusPressed) orange else Color.Transparent,
                                        label = "minusBg"
                                    )
                                    val minusTint = if (!minusEnabled) Color.White.copy(alpha = 0.22f) else if (minusPressed) Color.White else Color.White
                                    IconButton(
                                        interactionSource = minusInteraction,
                                        onClick = { viewModel.changeQuantity(platera.id, -1) },
                                        enabled = minusEnabled,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(minusBg)
                                    ) {
                                        Icon(imageVector = Icons.Filled.Remove, contentDescription = null, tint = minusTint)
                                    }

                                    Text(
                                        text = qty.toString(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.headlineSmall
                                    )

                                    val pendingAdd = (qty - committedQty).coerceAtLeast(0)
                                    val effectiveStock = platera.stock - pendingAdd
                                    val plusEnabled = effectiveStock > 0
                                    val plusInteraction = remember { MutableInteractionSource() }
                                    val plusPressed by plusInteraction.collectIsPressedAsState()
                                    val plusBg by animateColorAsState(
                                        targetValue = if (!plusEnabled) Color.Transparent else if (plusPressed) orange else Color.Transparent,
                                        label = "plusBg"
                                    )
                                    val plusTint = if (!plusEnabled) Color.White.copy(alpha = 0.22f) else if (plusPressed) Color.White else Color.White
                                    IconButton(
                                        interactionSource = plusInteraction,
                                        onClick = { viewModel.changeQuantity(platera.id, +1) },
                                        enabled = plusEnabled,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(plusBg)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = null,
                                            tint = plusTint
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            if (qty > 0) {
                                                noteDialogFor = platera
                                                val existingNote =
                                                    pendingNote
                                                        ?: komandas
                                                            .sortedByDescending { it.id }
                                                            .firstNotNullOfOrNull { it.oharrak }
                                                noteText = existingNote.orEmpty()
                                            }
                                        },
                                        enabled = qty > 0
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = if (hasNote) Color(0xFFF3863A) else Color.White.copy(alpha = 0.35f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            if (!uiState.error.isNullOrBlank() && !uiState.isLoading) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                )
            }

            FloatingActionButton(
                onClick = { viewModel.commitPendingChanges(onDone = onBack) },
                containerColor = Color(0xFFF3863A),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null)
            }

            noteDialogFor?.let { platera ->
                AlertDialog(
                    onDismissRequest = { noteDialogFor = null },
                    title = { Text(text = "Oharrak - ${platera.name}") },
                    text = {
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.updateNote(platera.id, noteText)
                                noteDialogFor = null
                            }
                        ) { Text("Gorde") }
                    },
                    dismissButton = {
                        TextButton(onClick = { noteDialogFor = null }) { Text("Utzi") }
                    }
                )
            }
        }
    }
}
