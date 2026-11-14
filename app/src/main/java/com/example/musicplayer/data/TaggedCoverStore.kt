package com.example.musicplayer.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Archivio locale persistente e deduplicato per le copertine HD applicate tramite Smart Auto-Tagger
 * (singolo brano, Uniforma Album, Dividi Album o copertina da Galleria).
 *
 * Garantisce che la copertina incorporata nei file audio sia immediatamente e stabilmente
 * disponibile per la UI (Coil) e per Media3 anche quando il database MediaStore di Android 16
 * ricalcola l'ALBUM_ID o ritarda l'estrazione della miniatura APIC/Vorbis/MP4.
 */
object TaggedCoverStore {

    private const val PREFS_NAME = "tagged_covers_prefs"
    private const val COVERS_DIR_NAME = "tagged_covers"

    private val trackCoverUriCache = ConcurrentHashMap<Long, Uri>()
    @Volatile
    private var isInitialized = false

    private fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val appContext = context.applicationContext ?: context
                val coversDir = File(appContext.filesDir, COVERS_DIR_NAME)
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val staleKeys = mutableListOf<String>()

                for ((key, value) in prefs.all) {
                    val trackId = key.toLongOrNull() ?: continue
                    val fileName = value as? String ?: continue
                    val file = File(coversDir, fileName)
                    if (file.exists() && file.length() > 0L) {
                        trackCoverUriCache[trackId] = Uri.fromFile(file)
                    } else {
                        staleKeys.add(key)
                    }
                }

                if (staleKeys.isNotEmpty()) {
                    prefs.edit().apply {
                        staleKeys.forEach { remove(it) }
                    }.apply()
                }
            } catch (_: Exception) {
            } finally {
                isInitialized = true
            }
        }
    }

    /**
     * Salva i byte JPEG/PNG della copertina deduplicandoli per hash SHA-1 e associa l'URI locale al brano [trackId].
     */
    fun saveTrackCover(context: Context, trackId: Long, coverBytes: ByteArray): Uri? {
        if (trackId <= 0L || coverBytes.isEmpty()) return null
        ensureInitialized(context)
        return try {
            val appContext = context.applicationContext ?: context
            val coversDir = File(appContext.filesDir, COVERS_DIR_NAME).apply { mkdirs() }
            val sha1Hex = sha1Hex(coverBytes)
            val fileName = "cover_$sha1Hex.jpg"
            val targetFile = File(coversDir, fileName)

            synchronized(this) {
                if (!targetFile.exists() || targetFile.length() != coverBytes.size.toLong()) {
                    val tempFile = File(coversDir, "${fileName}.tmp")
                    tempFile.writeBytes(coverBytes)
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                }

                val uri = Uri.fromFile(targetFile)
                trackCoverUriCache[trackId] = uri
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(trackId.toString(), fileName)
                    .apply()
                pruneUnreferencedFilesLocked(appContext, coversDir)
                uri
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Restituisce l'URI locale della copertina associata a [trackId], se presente su disco.
     */
    fun getCoverUri(context: Context, trackId: Long): Uri? {
        if (trackId <= 0L) return null
        ensureInitialized(context)
        val cached = trackCoverUriCache[trackId] ?: return null
        val path = cached.path ?: return null
        return if (File(path).exists()) {
            cached
        } else {
            trackCoverUriCache.remove(trackId)
            null
        }
    }

    /**
     * Rimuove le associazioni per i brani eliminati e ripulisce eventuali file orfani.
     */
    fun removeTracksCovers(context: Context, trackIds: Collection<Long>) {
        if (trackIds.isEmpty()) return
        ensureInitialized(context)
        try {
            val appContext = context.applicationContext ?: context
            val coversDir = File(appContext.filesDir, COVERS_DIR_NAME)
            synchronized(this) {
                val editor = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                for (id in trackIds) {
                    trackCoverUriCache.remove(id)
                    editor.remove(id.toString())
                }
                editor.apply()
                pruneUnreferencedFilesLocked(appContext, coversDir)
            }
        } catch (_: Exception) {}
    }

    private fun pruneUnreferencedFilesLocked(appContext: Context, coversDir: File) {
        if (!coversDir.exists()) return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val referencedNames = prefs.all.values.mapNotNull { it as? String }.toSet()
        coversDir.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.endsWith(".tmp") && file.name !in referencedNames) {
                file.delete()
            }
        }
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
