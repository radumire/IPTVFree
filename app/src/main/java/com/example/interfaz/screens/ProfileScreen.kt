package com.example.interfaz.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import com.example.parseHexColor
import com.example.motor.db.UserProfile
import com.example.interfaz.AppThemeColors
import com.example.interfaz.viewmodel.ProfileViewModel
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT

@Composable
fun EditProfileDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (name: String, avatar: String, pin: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    theme: AppThemeColors
) {
    var editName by remember { mutableStateOf(profile.name) }
    var editPin by remember { mutableStateOf(profile.pin) }
    var pinVisible by remember { mutableStateOf(false) }
    val avatars = listOf("👤", "🐱", "🐶", "🦊", "🐻", "🐼", "🤖")
    var selectedAvatar by remember { mutableStateOf(profile.avatar) }
    val isMainProfile = profile.id == 1L

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = parseHexColor(theme.surface),
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isMainProfile) "Editar Perfil Principal" else "Editar Perfil",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )

                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = parseHexColor(theme.bg),
                        unfocusedContainerColor = parseHexColor(theme.bg),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = parseHexColor(theme.accent),
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = editPin,
                    onValueChange = { editPin = it },
                    label = { Text("PIN Parental (Opcional)") },
                    singleLine = true,
                    visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pinVisible = !pinVisible }) {
                            Icon(
                                imageVector = if (pinVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (pinVisible) "Ocultar" else "Mostrar",
                                tint = Color.LightGray
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = parseHexColor(theme.bg),
                        unfocusedContainerColor = parseHexColor(theme.bg),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = parseHexColor(theme.accent),
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Selecciona Avatar:", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val allAvatars = if (selectedAvatar.isNotEmpty() && selectedAvatar !in avatars) {
                        listOf(selectedAvatar) + avatars
                    } else {
                        avatars
                    }
                    allAvatars.distinct().forEach { av ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (selectedAvatar == av) parseHexColor(theme.accent) else Color.Transparent)
                                .clickable { selectedAvatar = av },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(av, fontSize = 18.sp)
                        }
                    }
                }

                if (!isMainProfile && onDelete != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Eliminar Perfil", color = Color.White, fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = Color.LightGray, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalName = editName.trim().ifEmpty { if (isMainProfile) "Principal" else "Perfil" }
                            onSave(finalName, selectedAvatar, editPin)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = parseHexColor(theme.accent)),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Guardar", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileSelectorScreen(
    profiles: List<UserProfile>,
    onProfileSelected: (UserProfile) -> Unit,
    onAddProfile: (name: String, avatar: String, pin: String) -> Unit,
    onDeleteProfile: (id: Long) -> Unit,
    onEditProfile: (id: Long, name: String, avatar: String, pin: String) -> Unit,
    theme: AppThemeColors
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showPinDialogProfile by remember { mutableStateOf<UserProfile?>(null) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(parseHexColor(theme.bg)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 600.dp)
        ) {
            Text(
                text = "Radu IPTV Player",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (isEditMode) "Selecciona un perfil para editar" else "Selecciona tu perfil de entretenimiento",
                color = if (isEditMode) parseHexColor(theme.accent) else Color.LightGray,
                fontSize = 14.sp,
                fontWeight = if (isEditMode) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
            )

            // Grid of profiles
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                profiles.forEach { profile ->
                    var isFocused by remember { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .tvClickable(
                                focusedBackgroundColor = TV_FOCUSED_BG,
                                unfocusedBackgroundColor = Color.Transparent,
                                onFocusChange = { isFocused = it },
                                onClick = {
                                    if (isEditMode) {
                                        editingProfile = profile
                                    } else {
                                        if (profile.pin.isNotEmpty()) {
                                            showPinDialogProfile = profile
                                            enteredPin = ""
                                            pinError = false
                                        } else {
                                            onProfileSelected(profile)
                                        }
                                    }
                                }
                            )
                            .padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(if (isFocused) Color.Black.copy(alpha = 0.15f) else parseHexColor(theme.surface)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = profile.avatar,
                                fontSize = 36.sp,
                                modifier = Modifier.alpha(if (isEditMode) 0.5f else 1.0f)
                            )
                            if (isEditMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Editar",
                                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile.name,
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                            if (profile.pin.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "PIN",
                                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.Gray,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }

                // Add Profile action (only show when not in edit mode to avoid confusion)
                if (!isEditMode) {
                    var isAddFocused by remember { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .tvClickable(
                                focusedBackgroundColor = TV_FOCUSED_BG,
                                unfocusedBackgroundColor = Color.Transparent,
                                onFocusChange = { isAddFocused = it },
                                onClick = { showAddDialog = true }
                            )
                            .padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(if (isAddFocused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Añadir",
                                tint = if (isAddFocused) TV_FOCUSED_TEXT else Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Añadir",
                            color = if (isAddFocused) TV_FOCUSED_TEXT else Color.LightGray,
                            fontSize = 14.sp,
                            fontWeight = if (isAddFocused) FontWeight.ExtraBold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Admin Profiles Button
            TvButton(
                onClick = { isEditMode = !isEditMode },
                containerColor = parseHexColor(theme.surface),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) { isFocused ->
                Icon(
                    imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                    contentDescription = "Administrar Perfiles",
                    tint = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEditMode) "Listo" else "Administrar Perfiles",
                    color = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                    fontSize = 14.sp,
                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Medium
                )
            }
        }

        // Add Profile dialog
        if (showAddDialog) {
            var newName by remember { mutableStateOf("") }
            var newPin by remember { mutableStateOf("") }
            val avatars = listOf("👤", "🐱", "🐶", "🦊", "🐻", "🐼", "🤖")
            var selectedAvatar by remember { mutableStateOf(avatars.first()) }

            Dialog(onDismissRequest = { showAddDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = parseHexColor(theme.surface),
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Nuevo Perfil", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)

                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Nombre") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = parseHexColor(theme.bg),
                                unfocusedContainerColor = parseHexColor(theme.bg),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = parseHexColor(theme.accent),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = parseHexColor(theme.accent),
                                unfocusedLabelColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = newPin,
                            onValueChange = { newPin = it },
                            label = { Text("PIN Parental (Opcional)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = parseHexColor(theme.bg),
                                unfocusedContainerColor = parseHexColor(theme.bg),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = parseHexColor(theme.accent),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = parseHexColor(theme.accent),
                                unfocusedLabelColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Selecciona Avatar:", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            avatars.forEach { av ->
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(if (selectedAvatar == av) parseHexColor(theme.accent) else Color.Transparent)
                                        .clickable { selectedAvatar = av },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(av, fontSize = 18.sp)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showAddDialog = false }) {
                                Text("Cancelar", color = Color.LightGray, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val finalName = newName.trim().ifEmpty { "Perfil" }
                                    onAddProfile(finalName, selectedAvatar, newPin)
                                    showAddDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = parseHexColor(theme.accent)),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text("Crear", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // PIN validation dialog
        if (showPinDialogProfile != null) {
            AlertDialog(
                onDismissRequest = { showPinDialogProfile = null },
                containerColor = parseHexColor(theme.surface),
                title = { Text("Introduce PIN", color = Color.White) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Este perfil está protegido por contraseña parental",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        TextField(
                            value = enteredPin,
                            onValueChange = { enteredPin = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = parseHexColor(theme.bg),
                                unfocusedContainerColor = parseHexColor(theme.bg),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            isError = pinError,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pinError) {
                            Text(
                                "PIN Incorrecto",
                                color = Color.Red,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val prof = showPinDialogProfile!!
                            if (enteredPin == prof.pin) {
                                onProfileSelected(prof)
                                showPinDialogProfile = null
                            } else {
                                pinError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = parseHexColor(theme.accent))
                    ) {
                        Text("Entrar")
                    }
                }
            )
        }

        // Editing Profile Dialog
        if (editingProfile != null) {
            EditProfileDialog(
                profile = editingProfile!!,
                onDismiss = { editingProfile = null },
                onSave = { name, avatar, pin ->
                    onEditProfile(editingProfile!!.id, name, avatar, pin)
                },
                onDelete = {
                    onDeleteProfile(editingProfile!!.id)
                    editingProfile = null
                },
                theme = theme
            )
        }
    }
}

@Composable
fun AccountsScreen(
    viewModel: ProfileViewModel,
    onLogout: () -> Unit,
    theme: AppThemeColors
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profilesList = uiState.profiles
    val currentProfile = uiState.currentProfile
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Gestión de Perfiles y Cuentas", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            items(profilesList) { prof ->
                val isCurrent = prof.id == currentProfile?.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(parseHexColor(theme.surface))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(theme.accent).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(prof.avatar, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prof.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            TvIconButton(
                                onClick = { editingProfile = prof },
                                containerColor = Color.Transparent,
                                modifier = Modifier.size(28.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Editar nombre o pin",
                                    tint = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(if (isCurrent) "Perfil Activo" else "Perfil Secundario", color = if (isCurrent) Color.Green else Color.Gray, fontSize = 11.sp)
                    }
                    if (!isCurrent) {
                        TvButton(
                            onClick = {
                                viewModel.switchProfile(prof)
                            },
                            containerColor = parseHexColor(theme.accent),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) { isFocused ->
                            Text(
                                text = "Cambiar",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                            )
                        }
                    } else {
                        TvButton(
                            onClick = onLogout,
                            containerColor = Color.Red,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) { isFocused ->
                            Text(
                                text = "Desconectar",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (editingProfile != null) {
            EditProfileDialog(
                profile = editingProfile!!,
                onDismiss = { editingProfile = null },
                onSave = { name, avatar, pin ->
                    viewModel.updateProfile(editingProfile!!.id, name, avatar, pin)
                },
                onDelete = {
                    viewModel.deleteProfile(editingProfile!!.id)
                    editingProfile = null
                },
                theme = theme
            )
        }
    }
}
