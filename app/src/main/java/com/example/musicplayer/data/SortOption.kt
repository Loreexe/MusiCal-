package com.example.musicplayer.data

import com.example.musicplayer.R
import java.text.Normalizer
import java.util.Locale

private val LEADING_IGNORED_CHARS = setOf('"', '\'', '(', '[', '{', '«', '»', '“', '”', '‘', '’', '`', '¡', '¿')
private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")

/**
 * Rimuove spazi e caratteri di punteggiatura/apertura iniziali (virgolette, parentesi)
 * così che titoli come `"Heroes"` o `(Jump)` vengano ordinati sotto la lettera reale.
 */
fun stripLeadingSortPunctuation(text: String): String {
    var idx = 0
    val len = text.length
    while (idx < len) {
        val c = text[idx]
        if (c.isWhitespace() || c in LEADING_IGNORED_CHARS) {
            idx++
        } else {
            break
        }
    }
    return if (idx == 0) text.trimEnd() else text.substring(idx).trimEnd()
}

/**
 * Genera la chiave di ordinamento alfabetico coerente con [com.example.musicplayer.ui.components.extractAlphabeticalHeader]:
 * - gruppo `'0'` per numeri e simboli (`#`), ordinati prima delle lettere in A-Z e dopo in Z-A;
 * - gruppo `'1'` per le lettere normalizzate NFD (accenti e punteggiatura iniziale rimossi).
 */
fun alphabeticalSortKey(text: String): String {
    if (text.isEmpty()) return "0"
    val first = text[0]
    if (first in 'a'..'z' || first in 'A'..'Z') {
        var allAscii = true
        for (i in 0 until text.length) {
            if (text[i].code >= 128) {
                allAscii = false
                break
            }
        }
        if (allAscii) {
            return "1" + text.lowercase(Locale.ROOT)
        }
    }

    val stripped = stripLeadingSortPunctuation(text)
    if (stripped.isEmpty()) return "0"

    val normalized = Normalizer.normalize(stripped, Normalizer.Form.NFD)
    val withoutMarks = COMBINING_MARKS_REGEX.replace(normalized, "").lowercase(Locale.ROOT)
    val firstClean = withoutMarks.firstOrNull() ?: return "0"
    return if (firstClean.isLetter()) {
        "1$withoutMarks"
    } else {
        "0$withoutMarks"
    }
}

/**
 * Criteri di ordinamento disponibili per la libreria musicale.
 */
enum class SortOption {
    RECENTLY_PLAYED,
    MOST_PLAYED,
    RECENTLY_ADDED,
    ALPHABETICAL;

    val label: String
        get() = getLabel(isAscending = true)

    fun getLabel(isAscending: Boolean): String = when (this) {
        RECENTLY_PLAYED -> "Ascoltati di recente"
        MOST_PLAYED -> "Più ascoltati"
        RECENTLY_ADDED -> "Aggiunti di recente"
        ALPHABETICAL -> if (isAscending) "Alfabetico (A-Z)" else "Alfabetico (Z-A)"
    }

    fun getLabelResId(isAscending: Boolean): Int = when (this) {
        RECENTLY_PLAYED -> R.string.sort_recently_played
        MOST_PLAYED -> R.string.sort_most_played
        RECENTLY_ADDED -> R.string.sort_recently_added
        ALPHABETICAL -> if (isAscending) R.string.sort_az else R.string.sort_za
    }
}

/**
 * Ordina una lista di [AudioTrack] in base al criterio selezionato, statistiche d'ascolto e direzione.
 */
fun List<AudioTrack>.sortTracks(
    sortOption: SortOption,
    stats: Map<Long, TrackStats> = emptyMap(),
    isAscending: Boolean = true
): List<AudioTrack> {
    val list = when (sortOption) {
        SortOption.ALPHABETICAL -> sortedBy { alphabeticalSortKey(it.title) }
        SortOption.RECENTLY_PLAYED -> sortedByDescending { stats[it.id]?.lastPlayedTimestamp ?: 0L }
        SortOption.RECENTLY_ADDED -> sortedByDescending { it.dateAdded }
        SortOption.MOST_PLAYED -> sortedByDescending { stats[it.id]?.playCount ?: 0 }
    }
    return if (isAscending) list else list.reversed()
}

/**
 * Ordina una lista di [Album] in base al criterio selezionato, statistiche d'ascolto e direzione.
 */
fun List<Album>.sortAlbums(
    sortOption: SortOption,
    stats: Map<Long, TrackStats> = emptyMap(),
    isAscending: Boolean = true
): List<Album> {
    val list = when (sortOption) {
        SortOption.ALPHABETICAL -> sortedBy { alphabeticalSortKey(it.title) }
        SortOption.RECENTLY_PLAYED -> sortedByDescending { it.tracks.maxOfOrNull { t -> stats[t.id]?.lastPlayedTimestamp ?: 0L } ?: 0L }
        SortOption.RECENTLY_ADDED -> sortedByDescending { it.tracks.maxOfOrNull { t -> t.dateAdded } ?: 0L }
        SortOption.MOST_PLAYED -> sortedByDescending { it.tracks.sumOf { t -> stats[t.id]?.playCount ?: 0 } }
    }
    return if (isAscending) list else list.reversed()
}

/**
 * Ordina una lista di [Playlist] in base al criterio selezionato, statistiche d'ascolto e direzione.
 * La playlist predefinita 'Brani preferiti' (FAVORITES_PLAYLIST_ID) viene sempre estratta,
 * le restanti playlist vengono ordinate secondo il criterio e la direzione specificati,
 * e 'Brani preferiti' viene riposizionata inderogabilmente all'indice 0.
 */
fun List<Playlist>.sortPlaylists(
    sortOption: SortOption,
    stats: Map<Long, TrackStats> = emptyMap(),
    isAscending: Boolean = true
): List<Playlist> {
    val favorites = find { it.id == FAVORITES_PLAYLIST_ID }
    val others = filterNot { it.id == FAVORITES_PLAYLIST_ID }

    val sortedOthers = when (sortOption) {
        SortOption.ALPHABETICAL -> others.sortedBy { alphabeticalSortKey(it.name) }
        SortOption.RECENTLY_PLAYED -> others.sortedByDescending { it.trackIds.maxOfOrNull { id -> stats[id]?.lastPlayedTimestamp ?: 0L } ?: 0L }
        SortOption.RECENTLY_ADDED -> others
        SortOption.MOST_PLAYED -> others.sortedByDescending { it.trackIds.sumOf { id -> stats[id]?.playCount ?: 0 } }
    }
    val directedOthers = if (isAscending) sortedOthers else sortedOthers.reversed()

    return if (favorites != null) {
        listOf(favorites) + directedOthers
    } else {
        directedOthers
    }
}


