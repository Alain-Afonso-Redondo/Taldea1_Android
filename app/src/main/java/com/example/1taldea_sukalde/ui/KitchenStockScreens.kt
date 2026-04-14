package com.example.osiskitchen.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun KitchenPlatosStockScreen(
        viewModel: KitchenPlatosStockViewModel,
        onGoComandas: () -> Unit,
        onGoIngredientes: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var step by remember { mutableStateOf(1) }
    var query by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var stockDialogPlato by remember { mutableStateOf<KitchenPlatoStock?>(null) }
    var stockDialogText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (!error.isNullOrBlank()) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val categories =
    remember(uiState.platos) {
        uiState.platos
            .mapNotNull { p ->
                val id = p.kategoriaId ?: return@mapNotNull null
                val label = p.kategoriaIzena?.trim().takeUnless { it.isNullOrBlank() } ?: "Kategoria $id"
                id to label
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }

    val filtered =
        remember(uiState.platos, query, selectedCategoryId) {
            val q = query.trim().lowercase()
            uiState.platos.filter { p ->
                val matchesQuery =
                    q.isBlank() ||
                        p.izena.lowercase().contains(q) ||
                        (p.kategoriaIzena?.lowercase()?.contains(q) == true)
                val matchesCategory = selectedCategoryId == null || p.kategoriaId == selectedCategoryId
                matchesQuery && matchesCategory
            }
        }

    KitchenChrome(
            selectedTab = KitchenTab.Platos,
            onSelectTab = {
                when (it) {
                    KitchenTab.Comandas -> onGoComandas()
                    KitchenTab.Platos -> Unit
                    KitchenTab.Ingredientes -> onGoIngredientes()
                }
            },
            onLogoClick = { viewModel.refresh() },
            actionIcon = Icons.Filled.Refresh,
            actionIconContentDescription = "Eguneratu",
            onAction = { viewModel.refresh() }
    ) { modifier ->
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                stockDialogPlato?.let { plato ->
                    AlertDialog(
                            onDismissRequest = { stockDialogPlato = null },
                            title = { Text(text = plato.izena) },
                            text = {
                                OutlinedTextField(
                                        value = stockDialogText,
                                        onValueChange = { stockDialogText = it },
                                        singleLine = true,
                                        label = { Text(text = "Stock") },
                                        keyboardOptions =
                                                KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {
                                TextButton(
                                        onClick = {
                                            val parsed = stockDialogText.trim().toIntOrNull()
                                            if (parsed != null) {
                                                val target = parsed.coerceAtLeast(0)
                                                val delta = target - plato.stock
                                                if (delta != 0)
                                                        viewModel.adjustStock(plato.id, delta)
                                            }
                                            stockDialogPlato = null
                                        }
                                ) { Text(text = "Gorde") }
                            },
                            dismissButton = {
                                TextButton(onClick = { stockDialogPlato = null }) {
                                    Text(text = "Utzi")
                                }
                            }
                    )
                }

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(text = "Platerak bilatu") }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { step = (step - 1).coerceAtLeast(1) }) {
                            Icon(
                                    imageVector = Icons.Filled.Remove,
                                    contentDescription = "Pausoa jaitsi"
                            )
                        }
                        Text(
                                text = step.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                        )
                        IconButton(onClick = { step = (step + 1).coerceAtMost(50) }) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = "Pausoa igo")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = "Kategoria:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { categoryMenuExpanded = true }) {
                            val selectedLabel = categories.firstOrNull { it.first == selectedCategoryId }?.second
                            Text(text = selectedLabel ?: "Denak")
                        }
                        DropdownMenu(
                                expanded = categoryMenuExpanded,
                                onDismissRequest = { categoryMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                    text = { Text(text = "Denak") },
                                    onClick = {
                                        selectedCategoryId = null
                                        categoryMenuExpanded = false
                                    }
                            )
                            categories.forEach { (catId, catLabel) ->
                                DropdownMenuItem(
                                        text = { Text(text = catLabel) },
                                        onClick = {
                                            selectedCategoryId = catId
                                            categoryMenuExpanded = false
                                        }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.isLoading && uiState.platos.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Surface
                }

                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                                text = "Ez dago platerarik",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Surface
                }

                LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered) { plato ->
                        val isUpdating = uiState.updatingIds.contains(plato.id)
                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                        )
                        ) {
                            Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                            text = plato.izena,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                    )
                                    val cat = plato.kategoriaIzena
                                    if (!cat.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                                text = cat,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (isUpdating) {
                                    CircularProgressIndicator(
                                            modifier = Modifier.width(26.dp).height(26.dp),
                                            strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }

                                IconButton(
                                        enabled = !isUpdating && plato.stock - step >= 0,
                                        onClick = { viewModel.adjustStock(plato.id, -step) }
                                ) {
                                    Icon(
                                            imageVector = Icons.Filled.Remove,
                                            contentDescription = "Stock-a kendu"
                                    )
                                }

                                Text(
                                        text = plato.stock.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier =
                                                Modifier.width(56.dp).clickable(
                                                                enabled = !isUpdating
                                                        ) {
                                                    stockDialogPlato = plato
                                                    stockDialogText = plato.stock.toString()
                                                },
                                        fontWeight = FontWeight.SemiBold
                                )

                                IconButton(
                                        enabled = !isUpdating,
                                        onClick = { viewModel.adjustStock(plato.id, step) }
                                ) {
                                    Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = "Stock-a gehitu"
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

@Composable
fun KitchenIngredientesStockScreen(
        viewModel: KitchenIngredientesStockViewModel,
        onGoComandas: () -> Unit,
        onGoPlatos: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var step by remember { mutableStateOf(1) }
    var query by remember { mutableStateOf("") }
    var stockDialogIngrediente by remember { mutableStateOf<KitchenIngredienteStock?>(null) }
    var stockDialogText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (!error.isNullOrBlank()) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val filtered =
            remember(uiState.ingredientes, query) {
                val q = query.trim().lowercase()
                if (q.isBlank()) uiState.ingredientes
                else uiState.ingredientes.filter { it.izena.lowercase().contains(q) }
            }

    KitchenChrome(
            selectedTab = KitchenTab.Ingredientes,
            onSelectTab = {
                when (it) {
                    KitchenTab.Comandas -> onGoComandas()
                    KitchenTab.Platos -> onGoPlatos()
                    KitchenTab.Ingredientes -> Unit
                }
            },
            onLogoClick = { viewModel.refresh() },
            actionIcon = Icons.Filled.Refresh,
            actionIconContentDescription = "Eguneratu",
            onAction = { viewModel.refresh() }
    ) { modifier ->
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                stockDialogIngrediente?.let { ingrediente ->
                    AlertDialog(
                            onDismissRequest = { stockDialogIngrediente = null },
                            title = { Text(text = ingrediente.izena) },
                            text = {
                                OutlinedTextField(
                                        value = stockDialogText,
                                        onValueChange = { stockDialogText = it },
                                        singleLine = true,
                                        label = { Text(text = "Stock") },
                                        keyboardOptions =
                                                KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {
                                TextButton(
                                        onClick = {
                                            val parsed = stockDialogText.trim().toIntOrNull()
                                            if (parsed != null) {
                                                val target = parsed.coerceAtLeast(0)
                                                val delta = target - ingrediente.stock
                                                if (delta != 0)
                                                        viewModel.adjustStock(ingrediente.id, delta)
                                            }
                                            stockDialogIngrediente = null
                                        }
                                ) { Text(text = "Gorde") }
                            },
                            dismissButton = {
                                TextButton(onClick = { stockDialogIngrediente = null }) {
                                    Text(text = "Utzi")
                                }
                            }
                    )
                }

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(text = "Osagaiak bilatu") }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { step = (step - 1).coerceAtLeast(1) }) {
                            Icon(
                                    imageVector = Icons.Filled.Remove,
                                    contentDescription = "Pausoa jaitsi"
                            )
                        }
                        Text(
                                text = step.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                        )
                        IconButton(onClick = { step = (step + 1).coerceAtMost(50) }) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = "Pausoa igo")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.isLoading && uiState.ingredientes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Surface
                }

                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                                text = "Ez dago osagairik",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Surface
                }

                LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered) { ingrediente ->
                        val isUpdating = uiState.updatingIds.contains(ingrediente.id)
                        val isLow = ingrediente.stock <= ingrediente.gutxienekoStock
                        val containerColor =
                                if (ingrediente.eskatu) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else if (isLow) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }

                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = containerColor)
                        ) {
                            Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                            text = ingrediente.izena,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                            text = "Gutx: ${ingrediente.gutxienekoStock}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isUpdating) {
                                    CircularProgressIndicator(
                                            modifier = Modifier.width(26.dp).height(26.dp),
                                            strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }

                                IconButton(
                                        enabled = !isUpdating && ingrediente.stock - step >= 0,
                                        onClick = { viewModel.adjustStock(ingrediente.id, -step) }
                                ) {
                                    Icon(
                                            imageVector = Icons.Filled.Remove,
                                            contentDescription = "Stock-a kendu"
                                    )
                                }

                                Text(
                                        text = ingrediente.stock.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier =
                                                Modifier.width(56.dp).clickable(
                                                                enabled = !isUpdating
                                                        ) {
                                                    stockDialogIngrediente = ingrediente
                                                    stockDialogText = ingrediente.stock.toString()
                                                },
                                        fontWeight = FontWeight.SemiBold
                                )

                                IconButton(
                                        enabled = !isUpdating,
                                        onClick = { viewModel.adjustStock(ingrediente.id, step) }
                                ) {
                                    Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = "Stock-a gehitu"
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Switch(
                                        checked = ingrediente.eskatu,
                                        enabled = !isUpdating,
                                        onCheckedChange = { viewModel.toggleEskatu(ingrediente.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
