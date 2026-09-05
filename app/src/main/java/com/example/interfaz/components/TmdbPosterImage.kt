package com.example.interfaz.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.motor.repository.TmdbRepository

@Composable
fun TmdbPosterImage(
    title: String,
    isSeries: Boolean,
    defaultLogo: String?,
    modifier: Modifier = Modifier,
    onYearResolved: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    var posterUrl by remember(title) { mutableStateOf<String?>(null) }
    var isLoading by remember(title) { mutableStateOf(true) }

    LaunchedEffect(title) {
        isLoading = true
        val (poster, realYear) = TmdbRepository.getInstance().fetchPosterAndYear(title, isSeries)
        posterUrl = poster
        if (realYear != null && realYear > 1900) {
            onYearResolved?.invoke(realYear)
        }
        isLoading = false
    }

    Box(
        modifier = modifier.background(Color(0xFF1E1E1E)),
        contentAlignment = Alignment.Center
    ) {
        val modelToLoad = posterUrl ?: defaultLogo?.takeIf { it.isNotBlank() }
        if (modelToLoad != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(modelToLoad)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (isSeries) Icons.Default.VideoLibrary else Icons.Default.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(36.dp)
            )
        }

        if (isLoading && posterUrl == null && defaultLogo.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}
