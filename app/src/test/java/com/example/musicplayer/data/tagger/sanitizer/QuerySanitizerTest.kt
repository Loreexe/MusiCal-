package com.example.musicplayer.data.tagger.sanitizer

import android.net.Uri
import com.example.musicplayer.data.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySanitizerTest {

    @Test
    fun `cleanRawString preserves song titles starting with numbers`() {
        assertEquals("21 Guns", QuerySanitizer.cleanRawString("21 Guns"))
        assertEquals("7 Rings", QuerySanitizer.cleanRawString("7 Rings"))
        assertEquals("99 Problems", QuerySanitizer.cleanRawString("99 Problems"))
        assertEquals("505", QuerySanitizer.cleanRawString("505"))
        assertEquals("1979", QuerySanitizer.cleanRawString("1979"))
    }

    @Test
    fun `cleanRawString strips explicit track number prefixes and file extensions`() {
        assertEquals("21 Guns", QuerySanitizer.cleanRawString("01 - 21 Guns.mp3"))
        assertEquals("7 Rings", QuerySanitizer.cleanRawString("04. 7 Rings.flac"))
        assertEquals("Brano", QuerySanitizer.cleanRawString("01 Brano (Official Video).mp3"))
        assertEquals("Brano", QuerySanitizer.cleanRawString("1-05 Brano 320kbps.mp3"))
    }

    @Test
    fun `titlesMatch handles accents and feat suffixes without false positives on short words`() {
        assertTrue(QuerySanitizer.titlesMatch("Perché", "Perche"))
        assertTrue(QuerySanitizer.titlesMatch("Brivido (feat. Guè)", "Brivido"))
        assertTrue(QuerySanitizer.titlesMatch("Bohemian Rhapsody (Remastered 2011)", "Bohemian Rhapsody"))

        // Prevent false positives between distinct songs sharing a short word
        assertFalse(QuerySanitizer.titlesMatch("Love", "Hard To Love"))
        assertFalse(QuerySanitizer.titlesMatch("Blu", "Blu Celeste"))
        assertFalse(QuerySanitizer.titlesMatch("Solo", "Non Sono Solo"))
    }

    @Test
    fun `artistsMatch and hasRicherArtistCredits preserve featuring and collab credits`() {
        assertTrue(QuerySanitizer.artistsMatch("Marracash feat. Mahmood", "Marracash"))
        assertTrue(QuerySanitizer.artistsMatch("Salmo & Noyz Narcos", "Salmo"))
        assertFalse(QuerySanitizer.artistsMatch("Marracash", "Lazza"))

        assertTrue(QuerySanitizer.hasRicherArtistCredits("Marracash feat. Mahmood", "Marracash"))
        assertTrue(QuerySanitizer.hasRicherArtistCredits("Salmo & Noyz Narcos", "Salmo"))
        assertFalse(QuerySanitizer.hasRicherArtistCredits("Marracash", "Marracash"))
    }

    @Test
    fun `isTitleUnknownOrDirty distinguishes clean titles from raw filenames`() {
        assertFalse(QuerySanitizer.isTitleUnknownOrDirty("21 Guns", "01 - 21 Guns.mp3", "Green Day"))
        assertFalse(QuerySanitizer.isTitleUnknownOrDirty("Brivido (feat. Guè)", "Brivido.mp3", "Marracash"))

        assertTrue(QuerySanitizer.isTitleUnknownOrDirty("Sconosciuto", "01 - Song.mp3", "Artist"))
        assertTrue(QuerySanitizer.isTitleUnknownOrDirty("Track 01", "Track 01.mp3", "Artist"))
        assertTrue(QuerySanitizer.isTitleUnknownOrDirty("01 - Song.mp3", "01 - Song.mp3", "Artist"))
        assertTrue(QuerySanitizer.isTitleUnknownOrDirty("Marracash - Brivido", "Marracash - Brivido.mp3", "Marracash"))
    }
}
