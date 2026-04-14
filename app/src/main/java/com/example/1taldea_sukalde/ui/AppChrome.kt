package com.example.osiskitchen.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.osiskitchen.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

enum class KitchenTab {
    Comandas,
    Platos,
    Ingredientes
}

@Composable
fun KitchenChrome(
    selectedTab: KitchenTab,
    onSelectTab: (KitchenTab) -> Unit,
    onLogoClick: () -> Unit,
    actionIcon: ImageVector? = null,
    actionIconResId: Int? = null,
    actionIconContentDescription: String? = null,
    actionBadgeCount: Int = 0,
    onAction: () -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    val orange = remember { Color(0xFFF3863A) }
    val freeColor = remember { Color(0xFF5B1C1C) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTabletLandscape = isLandscape && configuration.screenWidthDp >= 840
    val bottomBarHeight = if (isLandscape) 110.dp else 150.dp

    val dateTimeFormatter = remember { SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault()) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }

    fun tabTint(tab: KitchenTab): Color {
        return if (tab == selectedTab) Color.White else Color.White.copy(alpha = 0.65f)
    }

    @Composable
    fun TabIcon(tab: KitchenTab, icon: ImageVector, contentDescription: String) {
        Box(
            modifier = Modifier.size(72.dp).clickable { onSelectTab(tab) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                modifier = Modifier.size(56.dp),
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tabTint(tab)
            )
        }
    }

    @Composable
    fun ActionIcon() {
        if (actionIcon == null && actionIconResId == null) return

        val badgeCount = actionBadgeCount
        if (badgeCount > 0) {
            val label = if (badgeCount > 99) "99+" else badgeCount.toString()
            BadgedBox(badge = { Badge { Text(text = label) } }) {
                IconButton(onClick = onAction) {
                    if (actionIconResId != null) {
                        Icon(
                            modifier = Modifier.size(40.dp),
                            painter = painterResource(actionIconResId),
                            contentDescription = actionIconContentDescription,
                            tint = freeColor
                        )
                    } else {
                        Icon(
                            modifier = Modifier.size(40.dp),
                            imageVector = actionIcon ?: Icons.Filled.Settings,
                            contentDescription = actionIconContentDescription,
                            tint = freeColor
                        )
                    }
                }
            }
        } else {
            IconButton(onClick = onAction) {
                if (actionIconResId != null) {
                    Icon(
                        modifier = Modifier.size(40.dp),
                        painter = painterResource(actionIconResId),
                        contentDescription = actionIconContentDescription,
                        tint = freeColor
                    )
                } else {
                    Icon(
                        modifier = Modifier.size(40.dp),
                        imageVector = actionIcon ?: Icons.Filled.Settings,
                        contentDescription = actionIconContentDescription,
                        tint = freeColor
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.logo_osis),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(100.dp).clickable(onClick = onLogoClick)
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = dateTimeFormatter.format(now),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            ActionIcon()
        }

        if (isTabletLandscape) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.width(160.dp).fillMaxHeight().background(orange),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TabIcon(
                        tab = KitchenTab.Comandas,
                        icon = Icons.Filled.ReceiptLong,
                        contentDescription = "Komandak"
                    )
                    TabIcon(
                        tab = KitchenTab.Platos,
                        icon = Icons.Filled.RestaurantMenu,
                        contentDescription = "Platerak"
                    )
                    TabIcon(
                        tab = KitchenTab.Ingredientes,
                        icon = Icons.Filled.Inventory2,
                        contentDescription = "Osagaiak"
                    )
                }
            }
        } else {
            content(Modifier.weight(1f).fillMaxWidth())

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(bottomBarHeight)
                        .background(orange)
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TabIcon(
                    tab = KitchenTab.Comandas,
                    icon = Icons.Filled.ReceiptLong,
                    contentDescription = "Komandak"
                )
                TabIcon(
                    tab = KitchenTab.Platos,
                    icon = Icons.Filled.RestaurantMenu,
                    contentDescription = "Platerak"
                )
                TabIcon(
                    tab = KitchenTab.Ingredientes,
                    icon = Icons.Filled.Inventory2,
                    contentDescription = "Osagaiak"
                )
            }
        }
    }
}
