package com.example.musicplayer.data.tagger.writer

import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AudioTagWriterTest {

    @Test
    fun `inspectImageHeader correctly parses PNG dimensions and mimeType`() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x02, 0x58, // width = 600
            0x00, 0x00, 0x02, 0x58, // height = 600
            0x08, 0x06, 0x00, 0x00, 0x00
        )

        val (mimeType, width, height) = AudioTagWriter.inspectImageHeader(pngHeader)
        assertEquals("image/png", mimeType)
        assertEquals(600, width)
        assertEquals(600, height)
    }

    @Test
    fun `inspectImageHeader correctly parses JPEG SOF0 dimensions and mimeType`() {
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00, // APP0 segment (length 4)
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, // SOF0 segment
            0x08, // precision
            0x03, 0xE8.toByte(), // height = 1000
            0x03, 0xE8.toByte(), // width = 1000
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val (mimeType, width, height) = AudioTagWriter.inspectImageHeader(jpegHeader)
        assertEquals("image/jpeg", mimeType)
        assertEquals(1000, width)
        assertEquals(1000, height)
    }

    @Test
    fun `safe AndroidArtwork creates artwork fields on ID3v24, FlacTag and VorbisCommentTag without throwing`() {
        val dummyJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11,
            0x08, 0x03, 0xE8.toByte(), 0x03, 0xE8.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
            0xFF.toByte(), 0xD9.toByte()
        )
        val (mimeType, width, height) = AudioTagWriter.inspectImageHeader(dummyJpeg)
        val artwork = object : AndroidArtwork() {
            override fun setImageFromData(): Boolean = true
        }.apply {
            binaryData = dummyJpeg
            this.mimeType = mimeType
            description = "Cover (front)"
            pictureType = 3
            this.width = width
            this.height = height
        }

        val id3Tag = ID3v24Tag()
        val id3Field = id3Tag.createField(artwork)
        assertNotNull(id3Field)
        id3Tag.setField(id3Field)
        assertArrayEquals(dummyJpeg, id3Tag.firstArtwork.binaryData)

        val flacTag = FlacTag()
        val flacField = flacTag.createField(artwork)
        assertNotNull(flacField)
        flacTag.setField(flacField)
        assertArrayEquals(dummyJpeg, flacTag.firstArtwork.binaryData)

        val vorbisTag = VorbisCommentTag.createNewTag()
        val vorbisField = vorbisTag.createField(artwork)
        assertNotNull(vorbisField)
    }
}
