package com.example.musicplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * BitmapLoader personalizzato per AndroidX Media3 su Android 16 (API 36).
 *
 * Risolve la copertina dell'album per la notifica MediaStyle di sistema e la schermata di blocco
 * utilizzando l'API moderna ContentResolver.loadThumbnail, con fallback su openInputStream
 * ed estrazione diretta di frame ID3/FLAC tramite MediaMetadataRetriever.
 */
@OptIn(UnstableApi::class)
class Media3BitmapLoader(private val context: Context) : BitmapLoader {

    private val executor = Executors.newSingleThreadExecutor()

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        executor.execute {
            try {
                val bitmap = decodeSampledBitmapFromBytes(data)
                if (bitmap != null) {
                    future.set(bitmap)
                } else {
                    future.setException(IllegalArgumentException("Impossibile decodificare bitmap da byte array"))
                }
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        executor.execute {
            try {
                val bitmap = loadBitmapFromUri(context, uri)
                if (bitmap != null) {
                    future.set(bitmap)
                } else {
                    future.setException(IllegalArgumentException("Nessuna copertina trovata per $uri"))
                }
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        if (metadata.artworkData != null) {
            return decodeBitmap(metadata.artworkData!!)
        }
        val uri = metadata.artworkUri ?: return null
        return loadBitmap(uri)
    }

    companion object {
        const val MAX_NOTIFICATION_ART_PX = 512

        /**
         * Calcola il fattore di sottocampionamento (potenza di 2) per limitare le dimensioni della copertina
         * in notifica ed evitare TransactionTooLargeException / Bad notification.
         */
        fun calculateInSampleSize(
            rawWidth: Int,
            rawHeight: Int,
            maxSize: Int = MAX_NOTIFICATION_ART_PX
        ): Int {
            if (rawWidth <= 0 || rawHeight <= 0 || maxSize <= 0) return 1
            var inSampleSize = 1
            while ((rawWidth / inSampleSize) > maxSize * 2 || (rawHeight / inSampleSize) > maxSize * 2) {
                inSampleSize *= 2
            }
            if (inSampleSize == 1 && (rawWidth > maxSize || rawHeight > maxSize)) {
                inSampleSize = 2
            }
            return inSampleSize
        }

        private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxSize: Int = MAX_NOTIFICATION_ART_PX): Bitmap {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= maxSize && height <= maxSize) return bitmap
            val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            if (scaled !== bitmap) {
                bitmap.recycle()
            }
            return scaled
        }

        fun decodeSampledBitmapFromBytes(
            data: ByteArray,
            maxSize: Int = MAX_NOTIFICATION_ART_PX
        ): Bitmap? {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                return BitmapFactory.decodeByteArray(data, 0, data.size)?.let { scaleBitmapIfNeeded(it, maxSize) }
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, maxSize)
            }
            return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)?.let {
                scaleBitmapIfNeeded(it, maxSize)
            }
        }

        /**
         * Tenta di caricare il bitmap della copertina con strategie progressive su Android 16.
         */
        fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
            val contentResolver = context.contentResolver

            // 1. API moderna Android 10+ (API 29..36) per MediaStore
            try {
                return contentResolver.loadThumbnail(uri, Size(MAX_NOTIFICATION_ART_PX, MAX_NOTIFICATION_ART_PX), null)
            } catch (_: Exception) {
            }

            // 2. Stream diretto (supporta file://, content:// legacy e risorse) con downsampling sicuro
            try {
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOptions)
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    if (boundsOptions.outWidth > 0 && boundsOptions.outHeight > 0) {
                        inSampleSize = calculateInSampleSize(
                            boundsOptions.outWidth,
                            boundsOptions.outHeight,
                            MAX_NOTIFICATION_ART_PX
                        )
                    }
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
                    if (bitmap != null) return scaleBitmapIfNeeded(bitmap, MAX_NOTIFICATION_ART_PX)
                }
            } catch (_: Exception) {
            }

            // 3. Fallback tramite MediaMetadataRetriever su file audio con downsampling sicuro
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    val rawPicture = retriever.embeddedPicture
                    if (rawPicture != null) {
                        val bitmap = decodeSampledBitmapFromBytes(rawPicture, MAX_NOTIFICATION_ART_PX)
                        if (bitmap != null) return bitmap
                    }
                }
            } catch (_: Exception) {
            }

            return null
        }
    }
}
