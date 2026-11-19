package com.example.musicplayer.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceLifecycleTest {

    @Test
    fun `shouldStopServiceOnTaskRemoved keeps service alive while actively playing`() {
        assertFalse(
            PlaybackService.shouldStopServiceOnTaskRemoved(
                isPlaying = true,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                mediaItemCount = 3
            )
        )
    }

    @Test
    fun `shouldStopServiceOnTaskRemoved keeps service alive during transient focus suppression or buffering`() {
        assertFalse(
            PlaybackService.shouldStopServiceOnTaskRemoved(
                isPlaying = false,
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
                mediaItemCount = 2
            )
        )
        assertFalse(
            PlaybackService.shouldStopServiceOnTaskRemoved(
                isPlaying = false,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                mediaItemCount = 1
            )
        )
    }

    @Test
    fun `shouldStopServiceOnTaskRemoved stops service when paused or ended or empty`() {
        assertTrue(
            PlaybackService.shouldStopServiceOnTaskRemoved(
                isPlaying = false,
                playWhenReady = false,
                playbackState = Player.STATE_READY,
                mediaItemCount = 5
            )
        )
        assertTrue(
            PlaybackService.shouldStopServiceOnTaskRemoved(
                isPlaying = false,
                playWhenReady = true,
                playbackState = Player.STATE_ENDED,
                mediaItemCount = 5
            )
        )
        assertTrue(
            PlaybackService.shouldStopServiceOnTaskRemoved(
                isPlaying = true,
                playWhenReady = true,
                playbackState = Player.STATE_IDLE,
                mediaItemCount = 5
            )
        )
        assertTrue(
            PlaybackService.shouldStopServiceOnTaskRemoved(
                isPlaying = true,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                mediaItemCount = 0
            )
        )
    }

    @Test
    fun `calculateInSampleSize keeps small images unscaled and downsamples oversized covers`() {
        assertEquals(1, Media3BitmapLoader.calculateInSampleSize(512, 512, 512))
        assertEquals(1, Media3BitmapLoader.calculateInSampleSize(300, 300, 512))
        assertEquals(2, Media3BitmapLoader.calculateInSampleSize(1024, 1024, 512))
        assertEquals(2, Media3BitmapLoader.calculateInSampleSize(2000, 2000, 512))
        assertEquals(4, Media3BitmapLoader.calculateInSampleSize(3000, 3000, 512))
        assertEquals(1, Media3BitmapLoader.calculateInSampleSize(0, 0, 512))
    }
}
