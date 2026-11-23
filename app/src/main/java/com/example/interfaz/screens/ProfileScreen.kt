package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import com.example.parseHexColor
import com.example.motor.db.UserProfile
import com.example.interfaz.AppThemeColors
import com.example.interfaz.viewmodel.ProfileViewModel
import com.example.interfaz.components.DEFAULT_PROFILE_AVATAR
import com.example.interfaz.components.PROFILE_AVATARS
import com.example.interfaz.components.ProfileAvatarImage
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TV_FOCUSED_BORDER
import com.example.interfaz.components.rememberTvInitialFocusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag

@Composable
fun EditProfileDialog(
    profile: UserProfile,
    isMainProfile: Boolean = (profile.id == 0L || profile.id == 1L),
    onDismiss: () -> Unit,
    onSave: (name: String, avatar: String, pin: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    theme: AppThemeColors
) {
    var editName by remember { mutableStateOf(profile.name) }
    val initialAvatarId = remember(profile.avatar) {
        PROFILE_AVATARS.find { it.id == profile.avatar }?.id ?: DEFAULT_PROFILE_AVATAR
    }
    var selectedAvatar by remember { mutableStateOf(initialAvatarId) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = parseHexColor(theme.surface),
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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

                Text("Elige tu Personaje:", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                AvatarPickerGrid(
                    selectedAvatar = selectedAvatar,
                    accentColor = parseHexColor(theme.accent),
                    onSelectAvatar = { selectedAvatar = it }
                )

                if (!isMainProfile && onDelete != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    TvButton(
                        onClick = onDelete,
                        containerColor = Color(0x38EF4444),
                        unfocusedBorderColor = Color(0x88EF4444),
                        focusedContainerColor = Color(0xFFE53935),
                        focusedBorderColor = Color.White,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar", modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Eliminar Perfil", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvButton(
                        onClick = onDismiss,
                        containerColor = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) { isFocused ->
                        Text(
                            text = "Cancelar",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    TvButton(
                        onClick = {
                            val finalName = editName.trim().ifEmpty { if (isMainProfile) "Principal" else "Perfil" }
                            onSave(finalName, selectedAvatar, profile.pin)
                            onDismiss()
                        },
                        containerColor = parseHexColor(theme.accent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) { isFocused ->
                        Text(
                            text = "Guardar",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, avatar: String, pin: String) -> Unit,
    theme: AppThemeColors
) {
    var newName by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf(DEFAULT_PROFILE_AVATAR) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = parseHexColor(theme.surface),
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Nuevo Perfil",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )

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

                Text("Elige tu Personaje:", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                AvatarPickerGrid(
                    selectedAvatar = selectedAvatar,
                    accentColor = parseHexColor(theme.accent),
                    onSelectAvatar = { selectedAvatar = it }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvButton(
                        onClick = onDismiss,
                        containerColor = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) { isFocused ->
                        Text(
                            text = "Cancelar",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    TvButton(
                        onClick = {
                            val finalName = newName.trim().ifEmpty { "Nuevo Perfil" }
                            onSave(finalName, selectedAvatar, "")
                            onDismiss()
                        },
                        containerColor = parseHexColor(theme.accent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) { isFocused ->
                        Text(
                            text = "Crear",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarPickerGrid(
    selectedAvatar: String,
    accentColor: Color,
    onSelectAvatar: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        PROFILE_AVATARS.chunked(5).forEach { rowOptions ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { option ->
                    val isSel = selectedAvatar == option.id
                    var isAvFocused by remember { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(78.dp)
                            .testTag("avatar_option_${option.id}")
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSel) accentColor.copy(alpha = 0.25f)
                                else Color.Transparent
                            )
                            .tvClickable(
                                shape = RoundedCornerShape(12.dp),
                                onFocusChange = { isAvFocused = it }
                            ) { onSelectAvatar(option.id) }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        ProfileAvatarImage(
                            avatarKey = option.id,
                            size = 54.dp,
                            accentColor = accentColor,
                            isFocused = isAvFocused,
                            isSelected = isSel,
                            showAnimatedRing = isSel || isAvFocused,
                            contentDescription = option.label
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = option.label,
                            color = if (isAvFocused || isSel) Color.White else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = if (isSel || isAvFocused) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileSelectorScreen(
    viewModel: ProfileViewModel,
    onProfileSelected: (UserProfile) -> Unit,
    theme: AppThemeColors
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileSelectorScreen(
        profiles = uiState.profiles,
        onProfileSelected = { p ->
            onProfileSelected(p)
        },
        onAddProfile = { name, avatar, pin -> viewModel.addProfile(name, avatar, pin, autoSelect = false) },
        onDeleteProfile = { id -> viewModel.deleteProfile(id) },
        onEditProfile = { id, name, avatar, pin -> viewModel.updateProfile(id, name, avatar, pin) },
        theme = theme
    )
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
    var isEditMode by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }
    val initialFocusRequester = rememberTvInitialFocusRequester()
    val mainProfileId = profiles.firstOrNull()?.id

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(parseHexColor(theme.bg)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
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
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                profiles.forEachIndexed { index, profile ->
                    var isFocused by remember { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .testTag("profile_card_${profile.id}")
                            .tvCardFocusable(
                                shape = RoundedCornerShape(14.dp),
                                scaleFocused = 1.06f,
                                elevationFocused = 8.dp,
                                unfocusedBackgroundColor = Color.Transparent,
                                focusRequester = if (index == 0) initialFocusRequester else null,
                                onFocusChange = { isFocused = it },
                                onClick = {
                                    if (isEditMode) {
                                        editingProfile = profile
                                    } else {
                                        onProfileSelected(profile)
                                    }
                                }
                            )
                            .padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(96.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ProfileAvatarImage(
                                avatarKey = profile.avatar,
                                size = 96.dp,
                                accentColor = parseHexColor(theme.accent),
                                isFocused = isFocused,
                                showAnimatedRing = isFocused,
                                dimOverlay = isEditMode,
                                contentDescription = profile.name
                            )
                            if (isEditMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Editar",
                                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        modifier = Modifier.size(28.dp)
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
                        }
                    }
                }

                // Add Profile action (only show when not in edit mode to avoid confusion)
                if (!isEditMode) {
                    var isAddFocused by remember { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .tvCardFocusable(
                                shape = RoundedCornerShape(12.dp),
                                scaleFocused = 1.05f,
                                elevationFocused = 8.dp,
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
                containerColor = parseHexColor(theme.card).copy(alpha = 0.60f),
                focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
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
            AddProfileDialog(
                onDismiss = { showAddDialog = false },
                onSave = { name, avatar, pin ->
                    onAddProfile(name, avatar, pin)
                },
                theme = theme
            )
        }

        // Editing Profile Dialog
        if (editingProfile != null) {
            EditProfileDialog(
                profile = editingProfile!!,
                isMainProfile = (editingProfile!!.id == mainProfileId),
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
    theme: AppThemeColors,
    onBack: () -> Unit = {}
) {
    // Capture back button to return to Home
    BackHandler {
        onBack()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profilesList = uiState.profiles
    val currentProfile = uiState.currentProfile
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val addProfileRequester = remember { FocusRequester() }
    val firstItemActionRequester = remember { FocusRequester() }
    val initialFocusRequester = rememberTvInitialFocusRequester()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gestión de Perfiles y Cuentas",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            TvButton(
                onClick = { showAddDialog = true },
                focusRequester = if (profilesList.isEmpty()) initialFocusRequester else addProfileRequester,
                modifier = Modifier
                    .focusProperties {
                        down = if (profilesList.isNotEmpty()) firstItemActionRequester else FocusRequester.Default
                    }
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.20f),
                        shape = RoundedCornerShape(8.dp)
                    ),
                containerColor = parseHexColor(theme.card).copy(alpha = 0.60f),
                focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) { isFocused ->
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Añadir perfil",
                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Añadir perfil",
                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .focusProperties {
                    up = addProfileRequester
                },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(profilesList) { index, prof ->
                val isCurrent = prof.id == currentProfile?.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(parseHexColor(theme.surface))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatarImage(
                        avatarKey = prof.avatar,
                        size = 48.dp,
                        accentColor = parseHexColor(theme.accent),
                        isSelected = isCurrent,
                        showAnimatedRing = isCurrent,
                        contentDescription = prof.name
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prof.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            TvIconButton(
                                onClick = { editingProfile = prof },
                                containerColor = Color.Transparent,
                                modifier = Modifier
                                    .size(28.dp)
                                    .then(
                                        if (index == 0) {
                                            Modifier.focusProperties { up = addProfileRequester }
                                        } else Modifier
                                    )
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Editar perfil",
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
                            focusRequester = if (index == 0) initialFocusRequester else null,
                            modifier = if (index == 0) {
                                Modifier
                                    .focusRequester(firstItemActionRequester)
                                    .focusProperties { up = addProfileRequester }
                            } else Modifier,
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
                            focusRequester = if (index == 0) initialFocusRequester else null,
                            modifier = if (index == 0) {
                                Modifier
                                    .focusRequester(firstItemActionRequester)
                                    .focusProperties { up = addProfileRequester }
                            } else Modifier,
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

        val accountsMainId = profilesList.firstOrNull()?.id
        if (editingProfile != null) {
            EditProfileDialog(
                profile = editingProfile!!,
                isMainProfile = (editingProfile!!.id == accountsMainId),
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

        if (showAddDialog) {
            AddProfileDialog(
                onDismiss = { showAddDialog = false },
                onSave = { name, avatar, pin ->
                    viewModel.addProfile(name, avatar, pin, autoSelect = false)
                },
                theme = theme
            )
        }
    }
}
