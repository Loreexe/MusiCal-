package com.example.musicplayer.data.tagger.sanitizer

import com.example.musicplayer.data.AudioTrack
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Risultato della pulizia euristica del nome file o tag audio.
 */
data class CleanQuery(
    val artist: String?,
    val title: String,
    val rawCleaned: String
)

/**
 * Utility per la sanitizzazione di query di ricerca, pulizia nomi file sporchi
 * e normalizzazione dei titoli degli album per evitare frammentazioni.
 */
object QuerySanitizer {

    private val AUDIO_EXTENSIONS_REGEX = Regex("""\.(mp3|flac|m4a|ogg|wav|aac|opus|wma)$""", RegexOption.IGNORE_CASE)

    /**
     * Rimuove il numero di traccia iniziale solo se seguito da un separatore esplicito (-, _, .)
     * oppure se è un numero a 2-3 cifre con zero iniziale (es. "01 Brano"), evitando di troncare
     * titoli autentici che iniziano con un numero (es. "21 Guns", "7 Rings", "99 Problems", "505", "1979").
     */
    private val LEADING_TRACK_NUMBER_REGEX = Regex(
        """^(?:\d{1,2}[-_.]\d{1,3}(?:\s*[-_.]+\s*|\s+)|\d{1,3}\s*[-_.]+\s*|0\d{1,2}\s+)"""
    )

    private val QUALITY_AND_BITRATE_REGEX = Regex(
        """(?i)\b(320\s*kbps|256\s*kbps|192\s*kbps|128\s*kbps|320k|128k|flac|lossless|24bit|vbr|cbr)\b"""
    )

    private val NOISE_TAGS_REGEX = Regex(
        """(?i)[\(\[]\s*(official\s*(music\s*)?(video|audio|lyric\s*video)?|lyrics?\s*(video)?|visualizer|clip\s*officiel|((hd|hq|4k|hi-res|master)\s*(audio|video)?)|audio|video|full\s*track|youtube\s*rip)\s*[\)\]]"""
    )

    private val ALBUM_NORMALIZATION_REGEX = Regex(
        """(?i)\s*[\(\[]\s*(deluxe(\s*(edition|version))?|remaster(ed)?(\s*\d{4})?|(\d{1,2}(th|nd|rd|st)\s*)?anniversary(\s*edition)?|expanded(\s*edition)?|special\s*edition|bonus(\s*track)?\s*version|explicit(\s*version)?|clean(\s*version)?)\s*[\)\]]"""
    )

    private val FEAT_SUFFIX_REGEX = Regex(
        """(?i)(\s*[\(\[]\s*(feat\.?|ft\.?|featuring|with|prod\.?\s*by)\s+[^\)\]]+[\)\]]|\s+(feat\.?|ft\.?|featuring)\s+.*)$"""
    )

    private val ARTIST_SPLIT_REGEX = Regex(
        """(?i)(?:\s*(?:,|&|\+|/|;)\s*|\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|x|and)\s+)"""
    )

    private val UNKNOWN_TITLE_REGEX = Regex(
        """(?i)^(sconosciuto|unknown|untitled|<unknown>|(track|traccia|pista|brano|audio)\s*[-_#]?\s*\d+)$"""
    )

    private val UNKNOWN_ARTIST_SET = setOf(
        "artista sconosciuto",
        "unknown artist",
        "unknown",
        "<unknown>",
        "sconosciuto"
    )

    private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val EMPTY_BRACKETS_REGEX = Regex("""[\(\[]\s*[\)\]]""")
    private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

    /**
     * Pulisce una stringa generica (es. nome file o titolo sporco) rimuovendo estensioni,
     * prefissi di numero traccia da file, diciture video/qualità e parentesi vuote.
     */
    fun cleanRawString(input: String): String {
        var text = input.trim()
        text = text.replace(AUDIO_EXTENSIONS_REGEX, "")
        text = text.replace(LEADING_TRACK_NUMBER_REGEX, "")
        text = text.replace(NOISE_TAGS_REGEX, "")
        text = text.replace(QUALITY_AND_BITRATE_REGEX, "")
        text = text.replace(EMPTY_BRACKETS_REGEX, "")
        text = text.replace(MULTI_SPACE_REGEX, " ")
        return text.trim(' ', '-', '_', '.')
    }

    /**
     * Rimuove suffissi di featuring e di edizione/remaster per confrontare il nucleo del titolo.
     */
    fun stripFeaturingAndEdition(title: String): String {
        var text = cleanRawString(title)
        text = text.replace(FEAT_SUFFIX_REGEX, "")
        text = text.replace(ALBUM_NORMALIZATION_REGEX, "")
        text = text.replace(EMPTY_BRACKETS_REGEX, "")
        text = text.replace(MULTI_SPACE_REGEX, " ")
        return text.trim(' ', '-', '_', '.')
    }

    /**
     * Estrae artista e titolo da un nome file (displayName).
     * Se è presente un delimitatore " - " separa artista e titolo.
     */
    fun cleanFileName(displayName: String): CleanQuery {
        val cleaned = cleanRawString(displayName)

        // Verifichiamo la presenza del pattern standard "Artista - Titolo"
        val dashIndex = cleaned.indexOf(" - ")
        if (dashIndex > 0 && dashIndex < cleaned.length - 3) {
            val artistPart = cleaned.substring(0, dashIndex).trim(' ', '-', '_')
            val titlePart = cleaned.substring(dashIndex + 3).trim(' ', '-', '_')
            if (artistPart.isNotBlank() && titlePart.isNotBlank()) {
                return CleanQuery(
                    artist = artistPart,
                    title = titlePart,
                    rawCleaned = cleaned
                )
            }
        }

        return CleanQuery(
            artist = null,
            title = cleaned.ifBlank { displayName },
            rawCleaned = cleaned
        )
    }

    /**
     * Normalizza il titolo di un album rimuovendo suffissi quali "(Deluxe Edition)" o "[Remastered 2023]",
     * consentendo di unificare le tracce sotto il titolo canonico del disco in studio.
     */
    fun normalizeAlbumTitle(rawAlbumTitle: String): String {
        var normalized = rawAlbumTitle.replace(ALBUM_NORMALIZATION_REGEX, "")
        normalized = normalized.replace(EMPTY_BRACKETS_REGEX, "")
        normalized = normalized.replace(MULTI_SPACE_REGEX, " ")
        return normalized.trim()
    }

    /**
     * Determina se la durata reale del file locale (in ms) corrisponde alla durata del candidato online (in sec)
     * entro la tolleranza specificata (default 3 secondi).
     */
    fun isDurationMatching(actualDurationMs: Long, candidateDurationSec: Long, toleranceSec: Long = 3): Boolean {
        if (actualDurationMs <= 0 || candidateDurationSec <= 0) return true
        val actualSec = actualDurationMs / 1000
        return abs(actualSec - candidateDurationSec) <= toleranceSec
    }

    /**
     * Estrae un eventuale numero di traccia all'inizio del nome file (es. "01 - Track.mp3", "04. Track.mp3", "1-05 Song.flac").
     */
    fun extractTrackNumberFromFileName(displayName: String): Int? {
        val trimmed = displayName.trim()
        val match1 = Regex("""^(?:(\d{1,2})[-_.])?(\d{1,3})\s*[-_.]+\s*""").find(trimmed)
        if (match1 != null) {
            val numStr = match1.groupValues[2].ifBlank { match1.groupValues[1] }
            val num = numStr.toIntOrNull()
            if (num != null && num in 1..999) return num
        }
        val matchZeroPadded = Regex("""^(?:(\d{1,2})[-_.])?(0\d{1,2})\s+""").find(trimmed)
        if (matchZeroPadded != null) {
            val num = matchZeroPadded.groupValues[2].toIntOrNull()
            if (num != null && num in 1..999) return num
        }
        return null
    }

    /**
     * Semplifica un testo per il confronto rimuovendo accenti/diacritici, punteggiatura, estensioni e caratteri speciali.
     */
    fun simplifyForComparison(title: String): String {
        val cleaned = cleanRawString(title).lowercase(Locale.ROOT)
        val deaccented = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
        return deaccented
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    /**
     * Confronta due titoli di tracce o album in modo accurato:
     * tollera differenze di accenti, punteggiatura, suffissi "(feat. ...)" o "(Remastered)",
     * ma impedisce falsi positivi tra brani diversi che condividono una sola parola breve.
     */
    fun titlesMatch(titleA: String, titleB: String): Boolean {
        if (titleA.isBlank() || titleB.isBlank()) return false
        if (titleA.trim().equals(titleB.trim(), ignoreCase = true)) return true

        val simpA = simplifyForComparison(titleA)
        val simpB = simplifyForComparison(titleB)
        if (simpA.isNotBlank() && simpA == simpB) return true

        val coreA = simplifyForComparison(stripFeaturingAndEdition(titleA))
        val coreB = simplifyForComparison(stripFeaturingAndEdition(titleB))
        if (coreA.isNotBlank() && coreA == coreB) return true

        // Confronto per prefisso su confine di parola solo se il titolo più corto ha almeno 5 caratteri
        val minLen = min(coreA.length, coreB.length)
        if (minLen >= 5) {
            val shorter = if (coreA.length <= coreB.length) coreA else coreB
            val longer = if (coreA.length <= coreB.length) coreB else coreA
            if (longer.startsWith("$shorter ")) {
                return true
            }
        }
        return false
    }

    /**
     * Verifica se due stringhe artista sono compatibili (stesso artista principale o almeno un collaboratore in comune).
     */
    fun artistsMatch(artistA: String, artistB: String): Boolean {
        if (artistA.isBlank() || artistB.isBlank()) return false
        val simpA = simplifyForComparison(artistA)
        val simpB = simplifyForComparison(artistB)
        if (simpA.isNotBlank() && simpA == simpB) return true

        val tokensA = extractArtistTokens(artistA)
        val tokensB = extractArtistTokens(artistB)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false

        return tokensA.any { a ->
            tokensB.any { b ->
                a == b || (min(a.length, b.length) >= 5 && (a.startsWith("$b ") || b.startsWith("$a ")))
            }
        }
    }

    private fun extractArtistTokens(artist: String): List<String> {
        val withoutFeatParens = artist.replace(Regex("""[\(\[]\s*(feat\.?|ft\.?|featuring|with)\s+""", RegexOption.IGNORE_CASE), ", ")
            .replace(")", "")
            .replace("]", "")
        return withoutFeatParens.split(ARTIST_SPLIT_REGEX)
            .map { simplifyForComparison(it) }
            .filter { it.isNotBlank() && it !in UNKNOWN_ARTIST_SET }
    }

    /**
     * Determina se il titolo locale di una traccia è assente, generico ("Sconosciuto", "Track 01")
     * oppure "sporco" (contiene estensioni file, prefisso numerico da filename, tag "Official Video", "320kbps",
     * oppure il pattern grezzo "Artista - Titolo" non separato).
     * Se restituisce `false`, il titolo locale è già pulito e attendibile.
     */
    fun isTitleUnknownOrDirty(title: String, displayName: String = "", artist: String = ""): Boolean {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return true
        if (UNKNOWN_TITLE_REGEX.matches(trimmed)) return true
        if (AUDIO_EXTENSIONS_REGEX.containsMatchIn(trimmed)) return true
        if (LEADING_TRACK_NUMBER_REGEX.containsMatchIn(trimmed)) return true
        if (NOISE_TAGS_REGEX.containsMatchIn(trimmed)) return true
        if (QUALITY_AND_BITRATE_REGEX.containsMatchIn(trimmed)) return true

        // Se il titolo contiene " - " e la prima parte coincide con l'artista, è un filename non separato ("Artista - Titolo")
        val dashIdx = trimmed.indexOf(" - ")
        if (dashIdx > 0) {
            val leftPart = trimmed.substring(0, dashIdx).trim()
            if (artist.isNotBlank() && !isArtistUnknownOrDirty(artist) && artistsMatch(leftPart, artist)) {
                return true
            }
            val displayNoExt = displayName.replace(AUDIO_EXTENSIONS_REGEX, "").trim()
            if (displayNoExt.isNotBlank() && trimmed.equals(displayNoExt, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * Determina se l'artista locale è assente, generico ("Artista sconosciuto", "Unknown Artist") o sporco.
     */
    fun isArtistUnknownOrDirty(artist: String): Boolean {
        val trimmed = artist.trim()
        if (trimmed.isBlank()) return true
        if (trimmed.lowercase(Locale.ROOT) in UNKNOWN_ARTIST_SET) return true
        if (AUDIO_EXTENSIONS_REGEX.containsMatchIn(trimmed)) return true
        if (NOISE_TAGS_REGEX.containsMatchIn(trimmed)) return true
        return false
    }

    /**
     * Verifica se l'artista locale contiene già collaboratori/featuring validi (es. "Marracash feat. Guè" o "Salmo & Noyz Narcos")
     * compatibili con l'artista principale restituito da Deezer (che spesso espone solo il primo artista).
     */
    fun hasRicherArtistCredits(localArtist: String, candidateArtist: String): Boolean {
        if (isArtistUnknownOrDirty(localArtist) || candidateArtist.isBlank()) return false
        if (!artistsMatch(localArtist, candidateArtist)) return false
        val localTokens = extractArtistTokens(localArtist)
        val candidateTokens = extractArtistTokens(candidateArtist)
        return localTokens.size > candidateTokens.size
    }

    /**
     * Estrae l'artista principale (primo artista prima di feat., &, virgola, ecc.) per query mirate su Deezer.
     */
    fun extractPrimaryArtistForSearch(artist: String): String {
        if (isArtistUnknownOrDirty(artist)) return ""
        val withoutParens = artist.replace(FEAT_SUFFIX_REGEX, "").trim()
        val firstToken = withoutParens.split(ARTIST_SPLIT_REGEX).firstOrNull()?.trim().orEmpty()
        return cleanRawString(firstToken.ifBlank { withoutParens })
    }

    /**
     * Restituisce la coppia (Artista, Titolo) migliore da usare per la ricerca online.
     * Se i tag ID3 correnti sono assenti, generici o contengono "Artista - Titolo",
     * analizza ed estrae i termini puliti.
     */
    fun getEffectiveSearchTerms(track: AudioTrack): Pair<String, String> {
        val isTitleDirty = isTitleUnknownOrDirty(track.title, track.displayName, track.artist)
        val isArtistDirty = isArtistUnknownOrDirty(track.artist)

        if (isTitleDirty || isArtistDirty) {
            val fileNameClean = cleanFileName(track.displayName.ifBlank { track.title })
            val titleFromTagClean = cleanFileName(track.title)

            val effectiveArtist = when {
                !isArtistDirty -> cleanRawString(track.artist)
                titleFromTagClean.artist != null -> titleFromTagClean.artist
                fileNameClean.artist != null -> fileNameClean.artist
                !isArtistUnknownOrDirty(track.albumArtist) -> cleanRawString(track.albumArtist)
                else -> ""
            }
            val effectiveTitle = when {
                !isTitleDirty -> stripFeaturingAndEdition(track.title).ifBlank { cleanRawString(track.title) }
                titleFromTagClean.artist != null && titleFromTagClean.title.isNotBlank() ->
                    stripFeaturingAndEdition(titleFromTagClean.title).ifBlank { titleFromTagClean.title }
                !UNKNOWN_TITLE_REGEX.matches(track.title.trim()) && track.title.isNotBlank() ->
                    stripFeaturingAndEdition(track.title).ifBlank { cleanRawString(track.title) }
                fileNameClean.title.isNotBlank() ->
                    stripFeaturingAndEdition(fileNameClean.title).ifBlank { fileNameClean.title }
                else -> cleanRawString(track.displayName)
            }
            return Pair(effectiveArtist, effectiveTitle)
        }

        val cleanTitle = stripFeaturingAndEdition(track.title).ifBlank { track.title.trim() }
        return Pair(track.artist.trim(), cleanTitle)
    }
}
