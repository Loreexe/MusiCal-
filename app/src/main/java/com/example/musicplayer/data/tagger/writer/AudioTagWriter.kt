package com.example.musicplayer.data.tagger.writer

import android.graphics.BitmapFactory
import com.example.musicplayer.data.tagger.model.EnrichedMetadata
import com.example.musicplayer.data.tagger.repository.EnricherOptions
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File

/**
 * Gestisce la scrittura fisica dei metadati e dell'artwork all'interno dei file audio.
 * Supporta MP3 (ID3v2), FLAC (Vorbis Comments + Picture Block), M4A/MP4 (atomi iTunes) e OGG.
 */
object AudioTagWriter {

    /**
     * Scrive i campi di metadati e incorpora l'eventuale copertina nel file audio specificato.
     */
    fun writeTags(
        file: File,
        metadata: EnrichedMetadata,
        options: EnricherOptions = EnricherOptions()
    ): Boolean {
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = if (audioFile is MP3File) {
                audioFile.tagOrCreateAndSetDefault
            } else {
                audioFile.tagOrCreateAndSetDefault ?: audioFile.createDefaultTag().also { audioFile.tag = it }
            }

            // 1. Titolo traccia (TIT2 / Vorbis TITLE / MP4 ©nam)
            if (metadata.title.isNotBlank()) {
                tag.setField(FieldKey.TITLE, metadata.title)
            }

            // 2. Artista specifico della traccia (TPE1 / Vorbis ARTIST / MP4 ©ART)
            if (metadata.artist.isNotBlank()) {
                tag.setField(FieldKey.ARTIST, metadata.artist)
            }

            // 3. Titolo dell'album (TALB / Vorbis ALBUM / MP4 ©alb)
            if (metadata.album.isNotBlank()) {
                tag.setField(FieldKey.ALBUM, metadata.album)
            }

            // 4. Artista principale dell'album (TPE2 / Vorbis ALBUMARTIST / MP4 aART)
            // Risolve la frammentazione degli album in Android MediaStore!
            if (metadata.albumArtist.isNotBlank()) {
                try {
                    tag.setField(FieldKey.ALBUM_ARTIST, metadata.albumArtist)
                } catch (_: Exception) {}
            }

            // 5. Anno di uscita (TYER / TDRC / Vorbis DATE / MP4 ©day)
            metadata.releaseYear?.let { year ->
                if (year > 0) {
                    try {
                        tag.setField(FieldKey.YEAR, year.toString())
                    } catch (_: Exception) {}
                }
            }

            // 6. Numero di traccia (TRCK / Vorbis TRACKNUMBER / MP4 trkn)
            metadata.trackNumber?.let { trackNum ->
                if (trackNum > 0) {
                    try {
                        tag.setField(FieldKey.TRACK, trackNum.toString())
                    } catch (_: Exception) {}
                }
            }

            // 7. Numero di disco (TPOS / Vorbis DISCNUMBER / MP4 disk)
            metadata.discNumber?.let { discNum ->
                if (discNum > 0) {
                    try {
                        tag.setField(FieldKey.DISC_NO, discNum.toString())
                    } catch (_: Exception) {}
                }
            }

            // 8. Genere (TCON / Vorbis GENRE / MP4 ©gen)
            if (!metadata.genre.isNullOrBlank()) {
                try {
                    tag.setField(FieldKey.GENRE, metadata.genre)
                } catch (_: Exception) {}
            }

            // 9. Testi traccia (USLT / Vorbis LYRICS / MP4 ©lyr)
            val lyricsToWrite = metadata.syncedLyrics ?: metadata.plainLyrics
            if (!lyricsToWrite.isNullOrBlank() && options.includeLyrics) {
                try {
                    tag.setField(FieldKey.LYRICS, lyricsToWrite)
                } catch (_: Exception) {}
            }

            // 10. Incorporamento copertina JPEG/PNG HD (APIC / Picture Block FLAC / MP4 covr / Vorbis METADATA_BLOCK_PICTURE)
            if (options.embedCover && metadata.coverBytes != null && metadata.coverBytes.isNotEmpty()) {
                try {
                    val (mimeType, width, height) = inspectImageHeader(metadata.coverBytes)
                    val artwork = object : AndroidArtwork() {
                        // Evita UnsupportedOperationException in FlacTag e VorbisCommentTag (che altrimenti invocano ImageIO)
                        override fun setImageFromData(): Boolean = true
                    }.apply {
                        binaryData = metadata.coverBytes
                        this.mimeType = mimeType
                        description = "Cover (front)"
                        pictureType = 3 // 3 = Front Cover
                        this.width = width
                        this.height = height
                    }

                    // Crea prima il nuovo campo artwork: elimina il precedente solo se la creazione ha avuto successo
                    val artworkField = tag.createField(artwork)
                    if (artworkField != null) {
                        try {
                            tag.deleteArtworkField()
                        } catch (_: Exception) {}
                        tag.setField(artworkField)
                    }
                } catch (_: Exception) {
                    // Preserva la scrittura degli altri tag e la copertina preesistente in caso di anomalia sull'artwork
                }
            }

            audioFile.commit()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Estrae i byte della copertina già incorporata nel file audio, se presente.
     */
    fun extractEmbeddedCoverBytes(file: File): ByteArray? {
        return try {
            val audioFile = AudioFileIO.read(file)
            audioFile.tag?.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Determina MIME type, larghezza e altezza dell'immagine dai magic bytes (JPEG/PNG) o tramite BitmapFactory,
     * con fallback sicuro a ("image/jpeg", 1000, 1000).
     */
    internal fun inspectImageHeader(bytes: ByteArray): Triple<String, Int, Int> {
        val isPng = bytes.size >= 24 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

        val mime = if (isPng) "image/png" else "image/jpeg"

        if (isPng) {
            val w = ((bytes[16].toInt() and 0xFF) shl 24) or
                ((bytes[17].toInt() and 0xFF) shl 16) or
                ((bytes[18].toInt() and 0xFF) shl 8) or
                (bytes[19].toInt() and 0xFF)
            val h = ((bytes[20].toInt() and 0xFF) shl 24) or
                ((bytes[21].toInt() and 0xFF) shl 16) or
                ((bytes[22].toInt() and 0xFF) shl 8) or
                (bytes[23].toInt() and 0xFF)
            if (w > 0 && h > 0) {
                return Triple(mime, w, h)
            }
        }

        parseJpegDimensions(bytes)?.let { (w, h) ->
            if (w > 0 && h > 0) {
                return Triple(mime, w, h)
            }
        }

        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val detectedMime = opts.outMimeType?.takeIf { it.isNotBlank() } ?: mime
                return Triple(detectedMime, opts.outWidth, opts.outHeight)
            }
        } catch (_: Exception) {}

        return Triple(mime, 1000, 1000)
    }

    private fun parseJpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 8 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) {
                i++
            }
            if (i >= bytes.size) break
            val marker = bytes[i].toInt() and 0xFF
            i++

            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) {
                continue
            }
            if (i + 1 >= bytes.size) break
            val segmentLength = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (segmentLength < 2 || i + segmentLength > bytes.size) break

            // SOF0..SOF3, SOF5..SOF7, SOF9..SOF11, SOF13..SOF15
            if (marker in listOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)) {
                if (i + 6 < bytes.size) {
                    val height = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                    val width = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    if (width > 0 && height > 0) {
                        return width to height
                    }
                }
            }
            i += segmentLength
        }
        return null
    }
}
