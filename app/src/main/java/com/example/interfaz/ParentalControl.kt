package com.example.interfaz

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.Translator
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.rememberTvInitialFocusRequester
import com.example.interfaz.components.tvClickable
import com.example.motor.config.PlayerSettings
import com.example.motor.db.IptvItem
import com.example.parseHexColor

object ParentalControl {
    private const val TAG = "ParentalControl"

    // Adult category/group keywords regex (always restricted regardless of age threshold)
    private val ADULT_GROUP_REGEX = Regex(
        """(?i)(^|[^\w])(adult(?:o?s|a?s)?|xxx|porn[o]?|erotic[ao]?|nsfw|playboy|penthouse|venus|brazzers|hustler|redlight|dorcel|man-x|vivid|hardcore)($|[^\w])"""
    )

    // Channel name adult keywords regex (conservative to prevent false positives)
    private val ADULT_NAME_REGEX = Regex(
        """(?i)(^|[^\w])(xxx|porn[o]?|erotic[ao]?|nsfw|playboy|penthouse|venus|brazzers|hustler|redlight|dorcel|man-x|vivid)($|[^\w])"""
    )

    // Explicit age markers like 18+, +18, 16+, +16, 12+, +12, 7+, +7, PEGI 16, TV-MA, TV-14, NC-17, PG-13
    private val PLUS_AGE_REGEX = Regex("""(?i)(?:^|[^\w])(?:\+(\d{1,2})|(\d{1,2})\+)(?:$|[^\w])""")
    private val PEGI_AGE_REGEX = Regex("""(?i)\b(?:pegi|tp)?\s*(7|12|13|14|16|18)\+?\b""")
    private val TV_MA_REGEX = Regex("""(?i)(^|[^\w])(tv-ma|nc-17|nc17)($|[^\w])""")
    private val TV_14_REGEX = Regex("""(?i)(^|[^\w])(tv-14|tv14|pg-13|pg13)($|[^\w])""")
    private val TV_PG_REGEX = Regex("""(?i)(^|[^\w])(tv-pg|tvpg)($|[^\w])""")

    internal fun maxAllowedAgeForSetting(parentalRating: String): Int {
        val norm = parentalRating.trim().lowercase()
        return when {
            norm.contains("apto") || norm == "all" || norm == "tp" || norm == "0+" -> 0
            norm.contains("18") -> 18
            norm.contains("16") -> 16
            norm.contains("12") -> 12
            else -> 12
        }
    }

    internal fun extractRequiredAge(text: String): Int? {
        if (text.isBlank()) return null
        var maxDetected: Int? = null
        fun record(age: Int) {
            if (maxDetected == null || age > maxDetected!!) maxDetected = age
        }

        PLUS_AGE_REGEX.findAll(text).forEach { m ->
            val num = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
            if (num != null && num in 6..21) record(num)
        }
        if (TV_MA_REGEX.containsMatchIn(text)) record(18)
        if (TV_14_REGEX.containsMatchIn(text)) record(14)
        if (TV_PG_REGEX.containsMatchIn(text)) record(7)
        if (Regex("""(?i)\bpegi\s*\d{1,2}\b""").containsMatchIn(text)) {
            PEGI_AGE_REGEX.findAll(text).forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { record(it) }
            }
        }
        return maxDetected
    }

    /**
     * Checks if a channel should be restricted based on adult patterns and settings.parentalRating.
     */
    fun isChannelRestricted(
        channel: IptvItem,
        settings: PlayerSettings
    ): Boolean {
        val group = channel.group.trim()
        val name = channel.name.trim()

        // 1. Explicit adult category/group or channel keywords are always restricted
        if (group.isNotBlank() && ADULT_GROUP_REGEX.containsMatchIn(group)) {
            return true
        }
        if (name.isNotBlank() && ADULT_NAME_REGEX.containsMatchIn(name)) {
            return true
        }

        // 2. Age rating evaluation against settings.parentalRating
        val maxAllowedAge = maxAllowedAgeForSetting(settings.parentalRating)
        val groupAge = extractRequiredAge(group)
        if (groupAge != null && groupAge > maxAllowedAge) {
            return true
        }
        val nameAge = extractRequiredAge(name)
        if (nameAge != null && nameAge > maxAllowedAge) {
            return true
        }

        return false
    }


    /**
     * Temporary log for monitoring parental control events as requested.
     * Note: Never logs the actual PIN.
     */
    fun logParental(
        enabled: Boolean,
        channel: String,
        restricted: Boolean,
        pinRequired: Boolean,
        pinSuccess: Boolean
    ) {
        Log.d(
            TAG,
            "PARENTAL: enabled=$enabled, channel=\"$channel\", restricted=$restricted, pinRequired=$pinRequired, pinSuccess=$pinSuccess"
        )
    }
}

/**
 * TV Dialog to unlock a restricted channel with PIN.
 */
@Composable
fun ParentalPinDialog(
    channelName: String,
    expectedPin: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    theme: AppThemeColors,
    lang: String = "es"
) {
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val initialFocusRequester = rememberTvInitialFocusRequester()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = parseHexColor(theme.surface),
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = parseHexColor(theme.accent),
                    modifier = Modifier.size(34.dp)
                )

                Text(
                    text = Translator.translate("parental", lang),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (expectedPin.trim().isEmpty()) {
                        when (lang.lowercase()) {
                            "en" -> "Enter PIN (default: 0000)"
                            "ro" -> "Introdu PIN-ul (implicit: 0000)"
                            else -> "Introduce el PIN (por defecto: 0000)"
                        }
                    } else {
                        when (lang.lowercase()) {
                            "en" -> "Enter 4-digit PIN"
                            "ro" -> "Introdu PIN-ul de 4 cifre"
                            else -> "Introduce el PIN de 4 dígitos"
                        }
                    },
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        if (it.length <= 8) {
                            enteredPin = it
                            pinError = false
                            errorMessage = ""
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = pinError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = parseHexColor(theme.bg),
                        unfocusedContainerColor = parseHexColor(theme.bg),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (pinError && errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TvButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        containerColor = Color.White.copy(alpha = 0.1f)
                    ) { isFocused ->
                        Text(
                            text = when (lang.lowercase()) {
                                "en" -> "Cancel"
                                "ro" -> "Anulează"
                                else -> "Cancelar"
                            },
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray,
                            fontSize = 13.sp
                        )
                    }

                    TvButton(
                        onClick = {
                            val cleanEntered = enteredPin.trim()
                            val cleanExpected = expectedPin.trim()
                            // If no PIN has been set yet, require the default PIN "0000" strictly
                            val isValid = if (cleanExpected.isEmpty()) {
                                cleanEntered == "0000"
                            } else {
                                cleanEntered == cleanExpected
                            }

                            if (isValid) {
                                ParentalControl.logParental(
                                    enabled = true,
                                    channel = channelName,
                                    restricted = true,
                                    pinRequired = true,
                                    pinSuccess = true
                                )
                                onSuccess()
                            } else {
                                ParentalControl.logParental(
                                    enabled = true,
                                    channel = channelName,
                                    restricted = true,
                                    pinRequired = true,
                                    pinSuccess = false
                                )
                                pinError = true
                                errorMessage = when (lang.lowercase()) {
                                    "en" -> "Incorrect PIN"
                                    "ro" -> "PIN incorect"
                                    else -> "PIN incorrecto"
                                }
                            }
                        },
                        focusRequester = initialFocusRequester,
                        modifier = Modifier.weight(1f),
                        containerColor = parseHexColor(theme.accent),
                        focusedContainerColor = parseHexColor(theme.accentLight)
                    ) { isFocused ->
                        Text(
                            text = when (lang.lowercase()) {
                                "en" -> "Unlock"
                                "ro" -> "Deblochează"
                                else -> "Desbloquear"
                            },
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Settings Card for managing Parental PIN with [GUARDAR] and [CAMBIAR] actions.
 */
@Composable
fun ParentalPinSettingsCard(
    currentPin: String,
    onSavePin: (String) -> Unit,
    lang: String,
    theme: AppThemeColors
) {
    var newPinInput by remember { mutableStateOf("") }
    var pinVisible by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var successNotice by remember { mutableStateOf<String?>(null) }
    var showChangeDialog by remember { mutableStateOf(false) }

    val hasConfiguredPin = currentPin.trim().isNotEmpty()

    Card(
        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = parseHexColor(theme.accent),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = Translator.translate("parental_pin", lang),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "PIN de seguridad para desbloquear canales adultos y restringidos.",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            if (!hasConfiguredPin) {
                // Initial PIN configuration: enter PIN and click [GUARDAR]
                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = {
                        if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                            newPinInput = it
                            validationError = null
                            successNotice = null
                        }
                    },
                    label = { Text("Nuevo PIN (4 a 6 dígitos)", color = Color.Gray) },
                    placeholder = { Text("Ej. 1234", color = Color.DarkGray) },
                    visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pinVisible = !pinVisible }) {
                            Icon(
                                imageVector = if (pinVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.LightGray
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = validationError != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = parseHexColor(theme.bg),
                        unfocusedContainerColor = parseHexColor(theme.bg),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (validationError != null) {
                    Text(text = validationError!!, color = Color(0xFFEF4444), fontSize = 11.sp)
                }

                if (successNotice != null) {
                    Text(text = successNotice!!, color = Color(0xFF10B981), fontSize = 11.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TvButton(
                        onClick = {
                            val cleanPin = newPinInput.trim()
                            if (cleanPin.length < 4) {
                                validationError = "El PIN debe tener al menos 4 dígitos numéricos."
                                return@TvButton
                            }
                            onSavePin(cleanPin)
                            newPinInput = ""
                            validationError = null
                            successNotice = "PIN de Control Parental guardado correctamente."
                        },
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.20f),
                            shape = RoundedCornerShape(6.dp)
                        ),
                        containerColor = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) { isFocused ->
                        Text(
                            text = "GUARDAR",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                // PIN already configured: compact status container with horizontal [CAMBIAR] button below
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(parseHexColor(theme.bg))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Estado del PIN: Configurado y guardado",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        TvButton(
                            onClick = { showChangeDialog = true },
                            modifier = Modifier.border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.20f),
                                shape = RoundedCornerShape(6.dp)
                            ),
                            containerColor = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)
                        ) { isFocused ->
                            Text(
                                text = "CAMBIAR",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (successNotice != null) {
                        Text(text = successNotice!!, color = Color(0xFF10B981), fontSize = 11.sp)
                    }
                }
            }
        }
    }

    if (showChangeDialog) {
        ChangeParentalPinDialog(
            currentPin = currentPin,
            onDismiss = { showChangeDialog = false },
            onPinChanged = { newPin ->
                onSavePin(newPin)
                showChangeDialog = false
                successNotice = "PIN cambiado exitosamente."
            },
            theme = theme
        )
    }
}

/**
 * Dialog to change Parental PIN requiring current PIN validation.
 */
@Composable
fun ChangeParentalPinDialog(
    currentPin: String,
    onDismiss: () -> Unit,
    onPinChanged: (String) -> Unit,
    theme: AppThemeColors
) {
    var oldPinInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val initialFocusRequester = rememberTvInitialFocusRequester()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = parseHexColor(theme.surface),
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Cambiar PIN de Control Parental",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = oldPinInput,
                    onValueChange = {
                        if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                            oldPinInput = it
                            errorMessage = null
                        }
                    },
                    label = { Text("PIN actual", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = parseHexColor(theme.bg),
                        unfocusedContainerColor = parseHexColor(theme.bg),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = {
                        if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                            newPinInput = it
                            errorMessage = null
                        }
                    },
                    label = { Text("Nuevo PIN (4 a 6 dígitos)", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = parseHexColor(theme.bg),
                        unfocusedContainerColor = parseHexColor(theme.bg),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = confirmPinInput,
                    onValueChange = {
                        if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                            confirmPinInput = it
                            errorMessage = null
                        }
                    },
                    label = { Text("Confirmar Nuevo PIN", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = parseHexColor(theme.bg),
                        unfocusedContainerColor = parseHexColor(theme.bg),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TvButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        containerColor = Color.White.copy(alpha = 0.1f)
                    ) { isFocused ->
                        Text(
                            text = "Cancelar",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray,
                            fontSize = 13.sp
                        )
                    }

                    TvButton(
                        onClick = {
                            if (oldPinInput.trim() != currentPin.trim()) {
                                errorMessage = "El PIN actual es incorrecto."
                                return@TvButton
                            }
                            if (newPinInput.trim().length < 4) {
                                errorMessage = "El nuevo PIN debe tener al menos 4 dígitos."
                                return@TvButton
                            }
                            if (newPinInput.trim() != confirmPinInput.trim()) {
                                errorMessage = "La confirmación no coincide con el nuevo PIN."
                                return@TvButton
                            }
                            onPinChanged(newPinInput.trim())
                        },
                        focusRequester = initialFocusRequester,
                        modifier = Modifier.weight(1f),
                        containerColor = parseHexColor(theme.accent),
                        focusedContainerColor = parseHexColor(theme.accentLight)
                    ) { isFocused ->
                        Text(
                            text = "Confirmar",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
