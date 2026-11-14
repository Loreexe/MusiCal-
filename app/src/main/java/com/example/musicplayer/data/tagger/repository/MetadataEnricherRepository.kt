package com.example.musicplayer.data.tagger.repository

import com.example.musicplayer.data.AudioTrack
import com.example.musicplayer.data.isGenericAlbum
import com.example.musicplayer.data.tagger.client.DeezerApiClient
import com.example.musicplayer.data.tagger.client.LrclibApiClient
import com.example.musicplayer.data.tagger.model.DeezerSearchTrack
import com.example.musicplayer.data.tagger.model.EnrichedMetadata
import com.example.musicplayer.data.tagger.sanitizer.QuerySanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class EnricherOptions(
    val normalizeAlbum: Boolean = true,
    val embedCover: Boolean = true,
    val includeLyrics: Boolean = true,
    val prioritizeStudioAlbum: Boolean = true
)

/**
 * Repository che coordina il recupero parallelo dei metadati da Deezer e LRCLIB
 * e applica le euristiche di selezione, validazione, ordinamento e normalizzazione.
 */
class MetadataEnricherRepository(
    private val deezerClient: DeezerApiClient = DeezerApiClient(),
    private val lrclibClient: LrclibApiClient = LrclibApiClient()
) {

    /**
     * Arricchisce i metadati di una traccia interrogando in parallelo Deezer e LRCLIB,
     * restituendo sia il miglior candidato arricchito (validato per titolo/artista/durata)
     * sia l'elenco completo dei candidati alternativi.
     */
    suspend fun enrichTrackWithCandidates(
        track: AudioTrack,
        options: EnricherOptions = EnricherOptions()
    ): Result<Pair<EnrichedMetadata, List<DeezerSearchTrack>>> = withContext(Dispatchers.IO) {
        try {
            val (cleanArtist, cleanTitle) = QuerySanitizer.getEffectiveSearchTerms(track)

            // 1. Avvio parallelo delle richieste a Deezer e LRCLIB
            val deezerDeferred = async {
                deezerClient.searchTrack(cleanTitle, cleanArtist)
            }

            val lrclibDeferred = async {
                if (options.includeLyrics) {
                    val durationSec = if (track.duration > 0) track.duration / 1000 else null
                    lrclibClient.getLyrics(cleanTitle, cleanArtist, track.album, durationSec).getOrNull()
                } else {
                    null
                }
            }

            val deezerSearchRes = deezerDeferred.await().getOrNull().orEmpty()
            val lrclibRes = lrclibDeferred.await()

            // 2. Selezione rigorosa del miglior candidato Deezer (deve corrispondere per titolo, artista e durata compatibile)
            val bestCandidate = selectBestCandidate(
                track = track,
                candidates = deezerSearchRes,
                cleanTitle = cleanTitle,
                cleanArtist = cleanArtist,
                options = options
            )

            if (bestCandidate == null) {
                // Se nessun candidato Deezer corrisponde in modo affidabile al brano, preserviamo i dati validi della traccia locale
                // evitando di sovrascrivere brani corretti con canzoni sbagliate.
                val isTitleDirty = QuerySanitizer.isTitleUnknownOrDirty(track.title, track.displayName, track.artist)
                val isArtistDirty = QuerySanitizer.isArtistUnknownOrDirty(track.artist)
                val preservedTitle = if (!isTitleDirty) {
                    track.title.trim()
                } else {
                    cleanTitle.ifBlank { track.title.trim() }
                }
                val preservedArtist = if (!isArtistDirty) {
                    track.artist.trim()
                } else {
                    cleanArtist.ifBlank { track.artist.trim() }
                }
                val preservedAlbumArtist = track.albumArtist.trim().takeIf { !QuerySanitizer.isArtistUnknownOrDirty(it) }
                    ?: preservedArtist

                val extractedTrack = QuerySanitizer.extractTrackNumberFromFileName(track.displayName)
                val fallbackMetadata = EnrichedMetadata(
                    title = preservedTitle,
                    artist = preservedArtist,
                    album = if (options.normalizeAlbum && !isGenericAlbum(track.album)) {
                        QuerySanitizer.normalizeAlbumTitle(track.album)
                    } else {
                        track.album
                    },
                    albumArtist = preservedAlbumArtist,
                    releaseYear = null,
                    trackNumber = track.trackNumber.takeIf { it > 0 } ?: extractedTrack,
                    discNumber = track.discNumber.takeIf { it > 0 } ?: 1,
                    genre = track.genre.takeIf { it.isNotBlank() },
                    coverUrl = null,
                    coverBytes = null,
                    plainLyrics = lrclibRes?.plainLyrics,
                    syncedLyrics = lrclibRes?.syncedLyrics,
                    isInstrumental = lrclibRes?.instrumental == true || track.isInstrumental(),
                    source = if (lrclibRes != null) "LRCLIB ( Metadati locali preservati )" else "Metadati locali preservati"
                )
                return@withContext Result.success(Pair(fallbackMetadata, deezerSearchRes))
            }

            val enriched = buildEnrichedFromCandidate(
                track = track,
                candidate = bestCandidate,
                plainLyrics = lrclibRes?.plainLyrics,
                syncedLyrics = lrclibRes?.syncedLyrics,
                isInstrumental = lrclibRes?.instrumental == true || track.isInstrumental(),
                options = options,
                isExplicitSelection = false
            )

            Result.success(Pair(enriched, deezerSearchRes))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Arricchisce i metadati di una traccia interrogando in parallelo Deezer e LRCLIB.
     */
    suspend fun enrichTrack(
        track: AudioTrack,
        options: EnricherOptions = EnricherOptions()
    ): Result<EnrichedMetadata> = enrichTrackWithCandidates(track, options).map { it.first }

    /**
     * Arricchisce la traccia a partire da uno specifico candidato Deezer selezionato manualmente dall'utente.
     */
    suspend fun enrichFromCandidate(
        track: AudioTrack,
        candidate: DeezerSearchTrack,
        options: EnricherOptions = EnricherOptions()
    ): Result<EnrichedMetadata> = withContext(Dispatchers.IO) {
        try {
            val durationSec = if (track.duration > 0) track.duration / 1000 else null
            val lrclibRes = if (options.includeLyrics) {
                lrclibClient.getLyrics(
                    candidate.titleShort.ifBlank { candidate.title },
                    candidate.artistName,
                    candidate.albumTitle,
                    durationSec
                ).getOrNull()
            } else null

            val enriched = buildEnrichedFromCandidate(
                track = track,
                candidate = candidate,
                plainLyrics = lrclibRes?.plainLyrics,
                syncedLyrics = lrclibRes?.syncedLyrics,
                isInstrumental = lrclibRes?.instrumental == true || track.isInstrumental(),
                options = options,
                isExplicitSelection = true
            )
            Result.success(enriched)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Esegue una ricerca manuale su Deezer con termini personalizzati dall'utente.
     */
    suspend fun searchCustom(
        title: String,
        artist: String
    ): Result<List<DeezerSearchTrack>> = withContext(Dispatchers.IO) {
        deezerClient.searchTrack(title.trim(), artist.trim())
    }

    /**
     * Recupera le informazioni dettagliate di un album da Deezer.
     */
    suspend fun getAlbumDetail(albumId: Long): Result<com.example.musicplayer.data.tagger.model.DeezerAlbumDetail> =
        deezerClient.getAlbumDetail(albumId)

    /**
     * Cerca l'ID Deezer di un album a partire da titolo e artista.
     */
    suspend fun searchAlbumId(title: String, artist: String): Result<Long?> =
        deezerClient.searchAlbumId(title, artist)

    /**
     * Scarica i byte dell'immagine di copertina dall'URL remoto specificato.
     */
    suspend fun downloadImage(imageUrl: String): Result<ByteArray> =
        deezerClient.downloadImage(imageUrl)

    /**
     * Allinea un insieme di tracce locali con la tracklist ufficiale di un album Deezer,
     * determinando la posizione esatta (trackPosition e diskNumber) per ciascun brano.
     */
    fun alignAlbumTracks(
        localTracks: List<AudioTrack>,
        officialTracks: List<com.example.musicplayer.data.tagger.model.DeezerAlbumTrackInfo>
    ): Map<Long, com.example.musicplayer.data.tagger.model.DeezerAlbumTrackInfo> {
        val matchedMap = mutableMapOf<Long, com.example.musicplayer.data.tagger.model.DeezerAlbumTrackInfo>()
        val remainingOfficial = officialTracks.toMutableList()

        // Passata 1: corrispondenza per titolo esatto o sanitizzato
        for (local in localTracks) {
            val cleanedFileTitle = QuerySanitizer.cleanFileName(local.displayName).title
            val candidate = remainingOfficial.firstOrNull { off ->
                QuerySanitizer.titlesMatch(local.title, off.title) ||
                    (QuerySanitizer.isTitleUnknownOrDirty(local.title, local.displayName, local.artist) &&
                        QuerySanitizer.titlesMatch(cleanedFileTitle, off.title))
            }
            if (candidate != null) {
                matchedMap[local.id] = candidate
                remainingOfficial.remove(candidate)
            }
        }

        // Passata 2: per le tracce rimaste con titolo ignoto/generico, verifica se il numero traccia corrisponde alla posizione
        for (local in localTracks) {
            if (local.id in matchedMap) continue
            val isTitleDirty = QuerySanitizer.isTitleUnknownOrDirty(local.title, local.displayName, local.artist)
            if (!isTitleDirty) continue

            val inferredNum = QuerySanitizer.extractTrackNumberFromFileName(local.displayName)
                ?: local.trackNumber.takeIf { it > 0 }
            if (inferredNum != null) {
                val candidate = remainingOfficial.firstOrNull { it.trackPosition == inferredNum }
                if (candidate != null) {
                    matchedMap[local.id] = candidate
                    remainingOfficial.remove(candidate)
                }
            }
        }

        return matchedMap
    }

    private suspend fun buildEnrichedFromCandidate(
        track: AudioTrack,
        candidate: DeezerSearchTrack,
        plainLyrics: String?,
        syncedLyrics: String?,
        isInstrumental: Boolean = false,
        options: EnricherOptions,
        isExplicitSelection: Boolean = false
    ): EnrichedMetadata {
        val albumDetailRes = deezerClient.getAlbumDetail(candidate.albumId).getOrNull()

        val albumArtist = albumDetailRes?.albumArtist?.takeIf { it.isNotBlank() }
            ?: candidate.artistName

        val rawAlbumTitle = albumDetailRes?.title?.takeIf { it.isNotBlank() }
            ?: candidate.albumTitle

        val finalAlbumTitle = if (options.normalizeAlbum) {
            QuerySanitizer.normalizeAlbumTitle(rawAlbumTitle)
        } else {
            rawAlbumTitle
        }

        // 1. Cerca la traccia nella lista delle tracce dell'album
        val matchingAlbumTrack = albumDetailRes?.tracks?.firstOrNull { t ->
            t.id == candidate.id ||
            QuerySanitizer.titlesMatch(t.title, candidate.title) ||
            QuerySanitizer.titlesMatch(t.title, candidate.titleShort)
        }

        var trackNumber = matchingAlbumTrack?.trackPosition?.takeIf { it > 0 }
        var discNumber = matchingAlbumTrack?.diskNumber?.takeIf { it > 0 }

        // 2. Se non ancora trovato o non valido, interroga l'endpoint /track/{id} di Deezer
        if (trackNumber == null || discNumber == null) {
            val trackDetail = deezerClient.getTrackDetail(candidate.id).getOrNull()
            if (trackDetail != null) {
                if (trackNumber == null && trackDetail.trackPosition > 0) {
                    trackNumber = trackDetail.trackPosition
                }
                if (discNumber == null && trackDetail.diskNumber > 0) {
                    discNumber = trackDetail.diskNumber
                }
            }
        }

        // Se il candidato Deezer è un singolo (1 sola traccia) ma il brano locale ha già un numero di traccia > 1 nel suo album,
        // non sovrascriviamo il numero di traccia locale con "1".
        val isCandidateSingle = albumDetailRes?.recordType?.equals("single", ignoreCase = true) == true ||
            (albumDetailRes?.tracks?.size == 1)
        if (!isExplicitSelection && isCandidateSingle && track.trackNumber > 1) {
            trackNumber = track.trackNumber
        }

        // 3. Se ancora non trovato, prova a ricavarlo dal nome del file locale
        if (trackNumber == null || trackNumber <= 0) {
            trackNumber = QuerySanitizer.extractTrackNumberFromFileName(track.displayName)
        }

        // 4. Fallback sui metadati preesistenti del file audio
        if (trackNumber == null || trackNumber <= 0) {
            trackNumber = track.trackNumber.takeIf { it > 0 }
        }
        if (discNumber == null || discNumber <= 0) {
            discNumber = track.discNumber.takeIf { it > 0 } ?: 1
        }

        val coverUrl = albumDetailRes?.coverXl ?: candidate.coverXl
        val coverBytes = if (options.embedCover && !coverUrl.isNullOrBlank()) {
            deezerClient.downloadImage(coverUrl).getOrNull()
        } else {
            null
        }

        // Se non è una selezione manuale forzata dall'utente:
        // - Preserviamo l'artista locale se è già pulito e contiene crediti/collaboratori più ricchi (es. "Artista A feat. Artista B")
        //   rispetto al solo artista principale restituito da Deezer.
        // - Preserviamo il titolo locale se è già pulito e coincide col brano trovato.
        val resolvedArtist = if (!isExplicitSelection && QuerySanitizer.hasRicherArtistCredits(track.artist, candidate.artistName)) {
            track.artist.trim()
        } else {
            candidate.artistName.ifBlank { track.artist.trim() }
        }

        val candidateCleanTitle = candidate.titleShort.ifBlank { candidate.title }
        val resolvedTitle = if (
            !isExplicitSelection &&
            !QuerySanitizer.isTitleUnknownOrDirty(track.title, track.displayName, track.artist) &&
            QuerySanitizer.titlesMatch(track.title, candidateCleanTitle)
        ) {
            track.title.trim()
        } else {
            candidateCleanTitle
        }

        return EnrichedMetadata(
            title = resolvedTitle,
            artist = resolvedArtist,
            album = finalAlbumTitle,
            albumArtist = albumArtist,
            releaseYear = albumDetailRes?.releaseYear,
            trackNumber = trackNumber,
            discNumber = discNumber,
            genre = albumDetailRes?.genre?.takeIf { it.isNotBlank() } ?: track.genre.takeIf { it.isNotBlank() },
            coverUrl = coverUrl,
            coverBytes = coverBytes,
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLyrics,
            isInstrumental = isInstrumental,
            recordType = albumDetailRes?.recordType,
            source = "Deezer + LRCLIB"
        )
    }

    /**
     * Seleziona il miglior candidato tra i risultati di ricerca Deezer verificando obbligatoriamente:
     * 1. Corrispondenza del titolo (evitando sostituzioni con altri brani dello stesso artista)
     * 2. Compatibilità dell'artista (quando noto)
     * 3. Coerenza della durata e preferenza per l'album originale rispetto a singoli/compilation
     *
     * Restituisce `null` se nessun candidato soddisfa i requisiti minimi di corrispondenza.
     */
    private fun selectBestCandidate(
        track: AudioTrack,
        candidates: List<DeezerSearchTrack>,
        cleanTitle: String,
        cleanArtist: String,
        options: EnricherOptions
    ): DeezerSearchTrack? {
        if (candidates.isEmpty()) return null

        val actualSec = if (track.duration > 0) track.duration / 1000 else null
        val isLocalTitleClean = !QuerySanitizer.isTitleUnknownOrDirty(track.title, track.displayName, track.artist)
        val isLocalArtistUnknown = QuerySanitizer.isArtistUnknownOrDirty(track.artist) && cleanArtist.isBlank()
        val hasKnownLocalAlbum = !isGenericAlbum(track.album) && !track.album.contains("(Brani singoli)")
        val normalizedLocalAlbum = if (hasKnownLocalAlbum) QuerySanitizer.normalizeAlbumTitle(track.album) else ""

        // Filtra solo i candidati il cui titolo e artista sono effettivamente compatibili col brano cercato
        val compatible = candidates.filter { c ->
            val titleMatch = QuerySanitizer.titlesMatch(cleanTitle, c.title) ||
                QuerySanitizer.titlesMatch(cleanTitle, c.titleShort) ||
                (isLocalTitleClean && (
                    QuerySanitizer.titlesMatch(track.title, c.title) ||
                        QuerySanitizer.titlesMatch(track.title, c.titleShort)
                    ))

            val artistMatch = isLocalArtistUnknown ||
                QuerySanitizer.artistsMatch(cleanArtist, c.artistName) ||
                QuerySanitizer.artistsMatch(track.artist, c.artistName) ||
                (track.albumArtist.isNotBlank() && QuerySanitizer.artistsMatch(track.albumArtist, c.artistName)) ||
                QuerySanitizer.artistsMatch(cleanArtist, c.title)

            val durationAcceptable = if (actualSec != null && c.duration > 0) {
                abs(c.duration - actualSec) <= 25L
            } else {
                true
            }

            titleMatch && artistMatch && durationAcceptable
        }

        if (compatible.isEmpty()) {
            return null
        }

        return compatible.minByOrNull { c ->
            var penalty = 0L

            // 1. Penalità differenza durata
            if (actualSec != null && c.duration > 0) {
                val diff = abs(c.duration - actualSec)
                penalty += when {
                    diff <= 3L -> diff
                    diff <= 6L -> 10L + diff
                    else -> 40L + diff * 3L
                }
            }

            // 2. Forte bonus (riduzione penalità) se l'album del candidato coincide con l'album locale già noto
            if (hasKnownLocalAlbum && c.albumTitle.isNotBlank()) {
                val candAlbumNorm = QuerySanitizer.normalizeAlbumTitle(c.albumTitle)
                if (QuerySanitizer.titlesMatch(normalizedLocalAlbum, candAlbumNorm)) {
                    penalty -= 60L
                }
            }

            // 3. Se si dà priorità agli album in studio, lieve penalità per i singoli omonimi del brano
            if (options.prioritizeStudioAlbum && c.albumTitle.equals(c.titleShort, ignoreCase = true)) {
                penalty += 8L
            }

            penalty
        }
    }
}
