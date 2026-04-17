package com.example.osislogin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.osislogin.R

@Composable
fun CategoriesScreen(
    tableId: Int,
    komensalak: Int,
    erreserbaId: Int?,
    data: String,
    txanda: String,
    viewModel: CategoriesViewModel,
    onLogout: () -> Unit,
    onChat: () -> Unit,
    canAccessChat: Boolean,
    chatUnreadCount: Int,
    onBack: () -> Unit,
    onCategorySelected: (
        tableId: Int,
        fakturaId: Int,
        kategoriId: Int,
        komensalak: Int,
        erreserbaId: Int?,
        data: String,
        txanda: String
    ) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var goBackAfterClose by remember { mutableStateOf(false) }
    var showClosePreviewDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tableId, komensalak, erreserbaId, data, txanda) {
        viewModel.load(tableId, komensalak, erreserbaId, data, txanda)
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onBack,
        navigationIcon = Icons.Filled.Apps,
        navigationIconContentDescription = "Mahaiak",
        showRightAction = canAccessChat,
        rightIconResId = R.drawable.chat,
        rightIconContentDescription = "Chat",
        onRightAction = onChat,
        rightBadgeCount = chatUnreadCount
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            val categories = uiState.categories
            val session = uiState.session
            val orange = remember { Color(0xFFF3863A) }
            val shape = remember { RoundedCornerShape(18.dp) }
            val elevation = 10.dp

            session?.takeIf { it.requiresDecision }?.let { s ->
                AlertDialog(
                    onDismissRequest = onBack,
                    title = { Text(text = "Faktura itxita") },
                    text = { Text(text = "Totala: ${s.fakturaTotala}€") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.reopenFactura(tableId) }) { Text("Berriro ireki") }
                    },
                    dismissButton = {
                        TextButton(onClick = onBack) { Text("Bueltatu") }
                    }
                )
            }

            if (showClosePreviewDialog) {
                val eskaeraId = session?.eskaeraId ?: 0
                AlertDialog(
                    onDismissRequest = { showClosePreviewDialog = false },
                    title = { Text(text = "Faktura itxi") },
                    text = {
                        val lines = uiState.closePreviewLines
                        val totalText =
                            session?.fakturaTotala?.let { totala ->
                                "Totala: ${"%.2f".format(totala)}€"
                            }

                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when {
                                uiState.isClosePreviewLoading -> Text(text = "Kontsumizioak kargatzen…")
                                lines.isEmpty() -> Text(text = "Ez daude kontsumizioak")
                                else -> {
                                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        lines.forEach { line ->
                                            Text(text = "x${line.qty} · ${line.name}")
                                        }
                                    }
                                }
                            }

                            if (!totalText.isNullOrBlank()) {
                                Text(text = totalText)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (!uiState.isClosePreviewLoading && eskaeraId > 0) {
                                    viewModel.closeFactura(eskaeraId)
                                    goBackAfterClose = true
                                    showClosePreviewDialog = false
                                }
                            }
                        ) { Text("Faktura itxi") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClosePreviewDialog = false }) { Text("Jarraitu") }
                    }
                )
            }

            if (uiState.error != null && !uiState.isLoading) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (!uiState.isLoading && categories.isEmpty()) {
                Text(
                    text = "Ez daude kategoriak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val tableLabel = uiState.tableLabel?.takeIf { it.isNotBlank() } ?: tableId.toString()
                    val guestsText = uiState.guestCount?.toString() ?: "—"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                    @Composable
                    fun CategoryTile(category: Category, modifier: Modifier) {
                        Surface(
                            color = Color.White,
                            shape = shape,
                            modifier =
                                modifier
                                    .shadow(elevation = elevation, shape = shape)
                                    .clickable {
                                        val s = session ?: return@clickable
                                        if (s.requiresDecision) return@clickable
                                        onCategorySelected(
                                            tableId,
                                            s.fakturaId,
                                            category.id,
                                            uiState.guestCount ?: komensalak,
                                            s.erreserbaId,
                                            s.data,
                                            s.txanda
                                        )
                                    }
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(14.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Image(
                                    painter = painterResource(categoryIconRes(category)),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(orange),
                                    modifier = Modifier.size(72.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = formatCategoryName(category.name),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(categories, key = { it.id }) { category ->
                            CategoryTile(
                                category = category,
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        }

                        item {
                            Surface(
                                color = orange,
                                shape = shape,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .shadow(elevation = elevation, shape = shape)
                                        .clickable {
                                            val s = session ?: return@clickable
                                            if (s.eskaeraId <= 0) return@clickable
                                            if (s.requiresDecision) return@clickable
                                            showClosePreviewDialog = true
                                            viewModel.loadClosePreview(s.eskaeraId)
                                        }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(14.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.recibo),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(Color.White),
                                        modifier = Modifier.size(72.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Faktura itxi",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            LaunchedEffect(goBackAfterClose, uiState.isLoading, uiState.error) {
                if (goBackAfterClose && !uiState.isLoading && uiState.error.isNullOrBlank()) {
                    onBack()
                }
            }
        }
    }
}

private fun categoryIconRes(category: Category): Int {
    val normalized = category.name.lowercase()
    return when {
        "lehen" in normalized -> R.drawable.primero
        "bigarren" in normalized -> R.drawable.segundo
        "postre" in normalized -> R.drawable.postre
        "edari" in normalized || "kafe" in normalized -> R.drawable.bebidas
        "pintxo" in normalized -> R.drawable.primero
        "razio" in normalized -> R.drawable.segundo
        else -> R.drawable.primero
    }
}

private fun formatCategoryName(raw: String): String {
    return raw
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { ch -> ch.uppercase() }
        }
}
