package com.example.osislogin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.example.osislogin.R

@Composable
fun CategoriesScreen(
    tableId: Int,
    viewModel: CategoriesViewModel,
    onLogout: () -> Unit,
    onChat: () -> Unit,
    chatUnreadCount: Int,
    onBack: () -> Unit,
    onCategorySelected: (tableId: Int, fakturaId: Int, kategoriId: Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var goBackAfterClose by remember { mutableStateOf(false) }
    var showClosePreviewDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tableId) {
        viewModel.load(tableId)
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onBack,
        navigationIcon = Icons.Filled.Apps,
        navigationIconContentDescription = "Mahaiak",
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
                val fakturaId = session?.fakturaId ?: 0
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
                                if (!uiState.isClosePreviewLoading && fakturaId > 0) {
                                    viewModel.closeFactura(fakturaId)
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
                val c0 = categories.getOrNull(0)
                val c1 = categories.getOrNull(1)
                val c2 = categories.getOrNull(2)
                val c3 = categories.getOrNull(3)

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
                    fun CategoryTile(category: Category?, iconResId: Int, modifier: Modifier) {
                        if (category == null) {
                            Box(modifier = modifier)
                            return
                        }

                        Surface(
                            color = Color.White,
                            shape = shape,
                            modifier =
                                modifier
                                    .shadow(elevation = elevation, shape = shape)
                                    .clickable {
                                        val s = session ?: return@clickable
                                        if (s.requiresDecision) return@clickable
                                        onCategorySelected(tableId, s.fakturaId, category.id)
                                    }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(iconResId),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(orange),
                                    modifier = Modifier.size(92.dp)
                                )
                            }
                        }
                    }

                    CategoryTile(c0, R.drawable.primero, Modifier.fillMaxWidth().weight(1f))
                    CategoryTile(c1, R.drawable.segundo, Modifier.fillMaxWidth().weight(1f))
                    CategoryTile(c2, R.drawable.postre, Modifier.fillMaxWidth().weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CategoryTile(c3, R.drawable.bebidas, Modifier.weight(1f).fillMaxHeight())

                        Surface(
                            color = orange,
                            shape = shape,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .shadow(elevation = elevation, shape = shape)
                                    .clickable {
                                        val s = session ?: return@clickable
                                        if (s.requiresDecision) return@clickable
                                        showClosePreviewDialog = true
                                        viewModel.loadClosePreview(s.fakturaId)
                                    }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(R.drawable.recibo),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier.size(92.dp)
                                )
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
