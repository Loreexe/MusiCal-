package com.example.musicplayer.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.example.musicplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dialogo a schermo intero per visualizzare e zoomare la copertina di una playlist (o dei Preferiti)
 * con sfocatura (blur) del background sottostante, pinch-to-zoom e doppio tocco.
 */
@Composable
fun CoverZoomDialog(
    title: String,
    coverModel: Any?,
    isFavorites: Boolean = false,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Attiva anche il blur nativo di finestra di Android 16 (API 36) oltre al Modifier.blur della vista sottostante
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 56
                }
            }
            onDispose {}
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var containerSize by remember { mutableStateOf(IntSize.Zero) }

        val animatedScale by animateFloatAsState(
            targetValue = scale,
            animationSpec = tween(durationMillis = 120),
            label = "CoverZoomScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            // Alone atmosferico sfocato della copertina stessa sullo sfondo
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp)
                    .graphicsLayer { alpha = 0.28f }
            ) {
                if (isFavorites) {
                    Image(
                        painter = painterResource(R.drawable.cover_favorites),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Pulsante Chiudi in alto a destra
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 20.dp)
                    .size(42.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Contenitore copertina zoomabile
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .aspectRatio(1f)
                        .onSizeChanged { containerSize = it }
                        .clip(RoundedCornerShape(20.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { tapOffset ->
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        val targetScale = 2.5f
                                        val centerX = containerSize.width / 2f
                                        val centerY = containerSize.height / 2f
                                        val maxDx = (containerSize.width * (targetScale - 1f)) / 2f
                                        val maxDy = (containerSize.height * (targetScale - 1f)) / 2f
                                        offsetX = ((centerX - tapOffset.x) * (targetScale - 1f)).coerceIn(-maxDx, maxDx)
                                        offsetY = ((centerY - tapOffset.y) * (targetScale - 1f)).coerceIn(-maxDy, maxDy)
                                        scale = targetScale
                                    }
                                },
                                onTap = {
                                    // Evita che il tap dentro la copertina chiuda il dialogo
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 4f)
                                val maxDx = (containerSize.width * (newScale - 1f)) / 2f
                                val maxDy = (containerSize.height * (newScale - 1f)) / 2f
                                scale = newScale
                                if (newScale <= 1.01f) {
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    offsetX = (offsetX + pan.x).coerceIn(-maxDx, maxDx)
                                    offsetY = (offsetY + pan.y).coerceIn(-maxDy, maxDy)
                                }
                            }
                        },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 16.dp
                ) {
                    val context = LocalContext.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .graphicsLayer {
                                scaleX = animatedScale
                                scaleY = animatedScale
                                translationX = offsetX
                                translationY = offsetY
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isFavorites) {
                            Image(
                                painter = painterResource(R.drawable.cover_favorites),
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (coverModel != null) {
                            val hiResRequest = remember(coverModel) {
                                ImageRequest.Builder(context)
                                    .data(coverModel)
                                    .size(Size.ORIGINAL)
                                    .allowHardware(true)
                                    .build()
                            }
                            AsyncImage(
                                model = hiResRequest,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.zoom_cover_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Dialogo interattivo per ritagliare, spostare (pan), zoomare e ruotare liberamente
 * un'immagine scelta dalla galleria prima di applicarla come copertina 1:1 di una playlist.
 */
@Composable
fun PlaylistCoverCropperDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (Uri) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(sourceUri) { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    // Stato di trasformazione dell'inquadratura
    var userZoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotationQuarterTurns by remember { mutableIntStateOf(0) } // 0, 1 (90°), 2 (180°), 3 (270°)
    var viewportPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(sourceUri) {
        isLoading = true
        val decoded = withContext(Dispatchers.IO) {
            decodeSampledBitmapFromUri(context, sourceUri, maxDimension = 2048)
        }
        sourceBitmap = decoded
        isLoading = false
        if (decoded == null) {
            onDismiss()
        }
    }

    val bmp = sourceBitmap
    val rotatedWidth = remember(bmp, rotationQuarterTurns) {
        if (bmp == null) 1f
        else if (rotationQuarterTurns % 2 == 0) bmp.width.toFloat() else bmp.height.toFloat()
    }
    val rotatedHeight = remember(bmp, rotationQuarterTurns) {
        if (bmp == null) 1f
        else if (rotationQuarterTurns % 2 == 0) bmp.height.toFloat() else bmp.width.toFloat()
    }

    // Calcola i limiti massimi di traslazione (in pixel del viewport) per evitare bordi vuoti nel quadrato
    fun clampOffsets(
        candidateX: Float,
        candidateY: Float,
        zoom: Float,
        vpSize: Float
    ): Pair<Float, Float> {
        if (vpSize <= 0f || rotatedWidth <= 0f || rotatedHeight <= 0f) return 0f to 0f
        val baseScale = max(vpSize / rotatedWidth, vpSize / rotatedHeight)
        val totalScale = baseScale * zoom
        val scaledW = rotatedWidth * totalScale
        val scaledH = rotatedHeight * totalScale
        val maxDx = max(0f, (scaledW - vpSize) / 2f)
        val maxDy = max(0f, (scaledH - vpSize) / 2f)
        return candidateX.coerceIn(-maxDx, maxDx) to candidateY.coerceIn(-maxDy, maxDy)
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.crop_cover_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = { if (!isSaving) onDismiss() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.crop_cover_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Area di ritaglio quadrata 1:1
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val density = LocalDensity.current
                    val currentVpPx = with(density) { maxWidth.toPx() }
                    if (viewportPx != currentVpPx && currentVpPx > 0f) {
                        viewportPx = currentVpPx
                    }

                    if (isLoading || bmp == null) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                        val baseScale = if (currentVpPx > 0f) {
                            max(currentVpPx / rotatedWidth, currentVpPx / rotatedHeight)
                        } else 1f

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .pointerInput(bmp, rotationQuarterTurns, currentVpPx) {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        val newZoom = (userZoom * zoomChange).coerceIn(1f, 4f)
                                        val (clampedX, clampedY) = clampOffsets(
                                            candidateX = offsetX + pan.x,
                                            candidateY = offsetY + pan.y,
                                            zoom = newZoom,
                                            vpSize = currentVpPx
                                        )
                                        userZoom = newZoom
                                        offsetX = clampedX
                                        offsetY = clampedY
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val totalScale = baseScale * userZoom

                                drawContext.canvas.nativeCanvas.apply {
                                    save()
                                    translate(cx + offsetX, cy + offsetY)
                                    rotate((rotationQuarterTurns * 90).toFloat())
                                    scale(totalScale, totalScale)
                                    drawBitmap(
                                        bmp,
                                        -bmp.width / 2f,
                                        -bmp.height / 2f,
                                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                                    )
                                    restore()
                                }

                                // Griglia guida dei terzi (Rule of Thirds)
                                val gridColor = Color.White.copy(alpha = 0.28f)
                                val strokeWidth = 1.dp.toPx()
                                val thirdW = size.width / 3f
                                val thirdH = size.height / 3f
                                drawLine(gridColor, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth)
                                drawLine(gridColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), strokeWidth)
                                drawLine(gridColor, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth)
                                drawLine(gridColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), strokeWidth)
                            }
                        }

                        // Cornice quadrata di riferimento
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Slider di Zoom per controllo preciso anche a una mano
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = stringResource(R.string.crop_cover_zoom),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Slider(
                        value = userZoom,
                        onValueChange = { newZoom ->
                            userZoom = newZoom
                            val (clampedX, clampedY) = clampOffsets(offsetX, offsetY, newZoom, viewportPx)
                            offsetX = clampedX
                            offsetY = clampedY
                        },
                        valueRange = 1f..4f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${((userZoom * 10f).roundToInt() / 10f)}x",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.End
                    )
                }

                // Pulsanti Ruota 90° e Centra/Reset
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                ) {
                    OutlinedButton(
                        onClick = {
                            rotationQuarterTurns = (rotationQuarterTurns + 1) % 4
                            offsetX = 0f
                            offsetY = 0f
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.crop_cover_rotate))
                    }

                    OutlinedButton(
                        onClick = {
                            userZoom = 1f
                            offsetX = 0f
                            offsetY = 0f
                            rotationQuarterTurns = 0
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.crop_cover_reset))
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Pulsanti Annulla / Applica
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSaving
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val currentBitmap = sourceBitmap ?: return@Button
                            if (viewportPx <= 0f || isSaving) return@Button
                            isSaving = true
                            coroutineScope.launch {
                                val croppedUri = withContext(Dispatchers.IO) {
                                    renderAndSaveCroppedSquare(
                                        context = context,
                                        source = currentBitmap,
                                        viewportSizePx = viewportPx,
                                        userZoom = userZoom,
                                        offsetX = offsetX,
                                        offsetY = offsetY,
                                        rotationQuarterTurns = rotationQuarterTurns,
                                        outputSizePx = 1024
                                    )
                                }
                                isSaving = false
                                if (croppedUri != null) {
                                    onConfirm(croppedUri)
                                } else {
                                    onDismiss()
                                }
                            }
                        },
                        enabled = !isLoading && !isSaving && sourceBitmap != null
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.crop_cover_apply))
                    }
                }
            }
        }
    }
}

private fun decodeSampledBitmapFromUri(
    context: android.content.Context,
    uri: Uri,
    maxDimension: Int
): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val width = info.size.width
            val height = info.size.height
            if (width > maxDimension || height > maxDimension) {
                val ratio = min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
                val targetW = (width * ratio).roundToInt().coerceAtLeast(1)
                val targetH = (height * ratio).roundToInt().coerceAtLeast(1)
                decoder.setTargetSize(targetW, targetH)
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun renderAndSaveCroppedSquare(
    context: android.content.Context,
    source: Bitmap,
    viewportSizePx: Float,
    userZoom: Float,
    offsetX: Float,
    offsetY: Float,
    rotationQuarterTurns: Int,
    outputSizePx: Int
): Uri? {
    return try {
        val rotW = if (rotationQuarterTurns % 2 == 0) source.width.toFloat() else source.height.toFloat()
        val rotH = if (rotationQuarterTurns % 2 == 0) source.height.toFloat() else source.width.toFloat()
        val baseScale = max(viewportSizePx / rotW, viewportSizePx / rotH)
        val totalViewportScale = baseScale * userZoom

        // Fattore di conversione tra le coordinate del viewport a schermo e il Bitmap di output (es. 1024x1024)
        val outputRatio = outputSizePx.toFloat() / viewportSizePx

        val outputBitmap = Bitmap.createBitmap(outputSizePx, outputSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val matrix = Matrix().apply {
            postTranslate(-source.width / 2f, -source.height / 2f)
            postRotate((rotationQuarterTurns * 90).toFloat())
            val finalScale = totalViewportScale * outputRatio
            postScale(finalScale, finalScale)
            postTranslate(
                outputSizePx / 2f + offsetX * outputRatio,
                outputSizePx / 2f + offsetY * outputRatio
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        val tempDir = File(context.cacheDir, "cropped_covers").apply {
            if (!exists()) mkdirs()
            // Pulisce eventuali file temporanei più vecchi di 10 minuti
            listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() > 600_000L) {
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        }
        val outFile = File(tempDir, "cropped_${System.currentTimeMillis()}.jpg")
        outFile.outputStream().use { out ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        outputBitmap.recycle()
        Uri.fromFile(outFile)
    } catch (_: Exception) {
        null
    }
}
