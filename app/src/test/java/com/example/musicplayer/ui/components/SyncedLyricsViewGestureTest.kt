package com.example.musicplayer.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncedLyricsViewGestureTest {

    @Test
    fun onPreScrollAndPreFlingLeaveInnerScrollUnblocked() = runBlocking {
        assertEquals(
            Offset.Zero,
            LyricsVerticalScrollBlocker.onPreScroll(
                available = Offset(15f, 120f),
                source = NestedScrollSource.UserInput
            )
        )
        assertEquals(
            Velocity.Zero,
            LyricsVerticalScrollBlocker.onPreFling(
                available = Velocity(50f, 900f)
            )
        )
    }

    @Test
    fun onPostScrollConsumesAllResidualVerticalDeltaWhilePreservingHorizontalAxis() {
        val downwardOverscroll = LyricsVerticalScrollBlocker.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(18f, 240f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset(0f, 240f), downwardOverscroll)

        val upwardOverscroll = LyricsVerticalScrollBlocker.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(-12f, -180f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset(0f, -180f), upwardOverscroll)
    }

    @Test
    fun onPostFlingConsumesAllResidualVerticalVelocityWhilePreservingHorizontalAxis() = runBlocking {
        val downwardFling = LyricsVerticalScrollBlocker.onPostFling(
            consumed = Velocity.Zero,
            available = Velocity(320f, 1500f)
        )
        assertEquals(Velocity(0f, 1500f), downwardFling)

        val upwardFling = LyricsVerticalScrollBlocker.onPostFling(
            consumed = Velocity.Zero,
            available = Velocity(-210f, -1200f)
        )
        assertEquals(Velocity(0f, -1200f), upwardFling)
    }
}
