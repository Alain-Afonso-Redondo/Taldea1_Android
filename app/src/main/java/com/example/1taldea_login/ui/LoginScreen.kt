package com.example.osislogin.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.osislogin.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        viewModel.loadUsers()
    }

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            viewModel.resetSuccess()
            onLoginSuccess()
        }
    }

    val dateTimeFormatter = remember { SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault()) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = dateTimeFormatter.format(now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
            Image(
                painter = painterResource(R.drawable.logo_osis),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(top = 15.dp, bottom = 16.dp)
                    .clickable { viewModel.loadUsers() }
            )

            Box(
                modifier = Modifier.weight(1f, fill = true),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.users.isEmpty() && !uiState.isLoading) {
                    Text(
                        text = "Ez dago erabiltzailerik eskuragarri",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    val leftColor = remember { Color(0xFF1B345D) }
                    val rightColor = remember { Color(0xFFF3863A) }
                    if (isLandscape) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            itemsIndexed(items = uiState.users, key = { _, user -> user.id }) { index, user ->
                                val borderColor = if (index % 2 == 0) leftColor else rightColor
                                UserGridItem(
                                    user = user,
                                    borderColor = borderColor,
                                    onClick = { viewModel.selectUser(user) },
                                    modifier = Modifier.width(240.dp)
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(60.dp),
                            verticalArrangement = Arrangement.spacedBy(60.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(count = uiState.users.size, key = { uiState.users[it].id }) { index ->
                                val user = uiState.users[index]
                                val rowIndex = index / 2
                                val columnIndex = index % 2
                                val isOddRow = rowIndex % 2 == 0
                                val borderColor = when {
                                    isOddRow && columnIndex == 0 -> leftColor
                                    isOddRow && columnIndex == 1 -> rightColor
                                    !isOddRow && columnIndex == 0 -> rightColor
                                    else -> leftColor
                                }
                                UserGridItem(
                                    user = user,
                                    borderColor = borderColor,
                                    onClick = { viewModel.selectUser(user) }
                                )
                            }
                        }
                    }
                }
            }

            uiState.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        if (uiState.isLoading && uiState.users.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (uiState.showPinDialog) {
            val selectedUserName = uiState.selectedUserId
                ?.let { id -> uiState.users.firstOrNull { it.id == id }?.displayName }
                .orEmpty()

            PinDialog(
                userName = selectedUserName,
                pin = uiState.pin,
                onPinChange = { viewModel.updatePin(it) },
                onConfirm = { viewModel.verifyPinWithApi() },
                onDismiss = { viewModel.cancelPinDialog() },
                error = uiState.error,
                isLoading = uiState.isLoading
            )
        }
    }
}

@Composable
fun UserGridItem(
    user: ApiUser,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = remember { RoundedCornerShape(topStart = 80.dp, topEnd = 80.dp, bottomEnd = 80.dp, bottomStart = 0.dp) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .border(width = 5.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.icono),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(130.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = user.displayName,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp)
        )
    }
}

@Composable
fun PinDialog(
    userName: String,
    pin: String,
    onPinChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    error: String?,
    isLoading: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "PIN - $userName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { newValue ->
                        onPinChange(newValue.filter { it.isDigit() })
                    },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!error.isNullOrBlank()) {
                    Text(text = error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isLoading) {
                Text("Sartu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Bekitu")
            }
        }
    )
}

