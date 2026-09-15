package com.mymusic.player.ui

import com.mymusic.player.data.ResolvedTrack
import com.mymusic.player.domain.Track
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1/B2 payoff: the play-queue pipeline is a plain class with two seams
 * ([TrackResolver] + [PlayerOps]), so the queue-context decision, the ordered
 * background fill and the shared resolution cache can be tested without
 * BiliDirectClient, Media3 or Android.
 */
class PlaybackQueueCoordinatorTest {

    // ---- Fakes ----

    /** Resolves every track to a playable copy; can fail selected bvids. */
    private class FakeResolver(private val failBvids: Set<String> = emptySet()) : TrackResolver {
        val resolveCount = mutableMapOf<String, Int>()

        override suspend fun resolveAudioFast(track: Track): Track {
            resolveCount[track.bvid] = (resolveCount[track.bvid] ?: 0) + 1
            if (track.bvid in failBvids) throw RuntimeException("resolve failed")
            return track.copy(audioUrl = "https://cdn/${track.uid}.m4a", cid = 100L)
        }

        override suspend fun resolveAudioWithSeason(track: Track): ResolvedTrack =
            ResolvedTrack(track = resolveAudioFast(track))
    }

    /** Records every timeline operation the coordinator requests. */
    private class RecordingPlayerOps : PlayerOps {
        data class PlayQueueCall(val context: List<Track>, val startIndex: Int, val startPositionMs: Long)

        val playQueueCalls = mutableListOf<PlayQueueCall>()
        val inserts = mutableListOf<Track>()
        val nextOnDemand = mutableListOf<Track>()
        var currentUid: String? = null

        override fun playQueue(context: List<Track>, startIndex: Int, startPositionMs: Long) {
            playQueueCalls += PlayQueueCall(context, startIndex, startPositionMs)
        }

        override fun insertQueueResolved(
            track: Track,
            pivotUid: String,
            before: Boolean,
            beforeOffset: Int,
        ): Boolean {
            inserts += track
            return true
        }

        override fun insertNextOnDemand(track: Track): Boolean {
            nextOnDemand += track
            return true
        }

        override fun clearLoadingNext() = Unit
        override fun loadingNextUid(): String? = null
        override fun currentUid(): String? = currentUid
    }

    private fun track(bvid: String) = Track(bvid = bvid, title = "t-$bvid", duration = 100)

    // ---- playFromList (list-as-queue path) ----

    @Test
    fun `list queues play the tapped song immediately and fill the rest in order`() = runTest {
        val resolver = FakeResolver()
        val player = RecordingPlayerOps()
        val messages = mutableListOf<String>()
        val coordinator = PlaybackQueueCoordinator(
            repo = resolver,
            scope = this,
            onMessage = { messages += it },
            player = player,
        )
        val list = listOf(track("A"), track("B"), track("C"), track("D"))

        val ok = coordinator.playFromList(list, index = 1, useListAsQueue = true)
        advanceUntilIdle()

        assertTrue(ok)
        // The visible queue is the whole list, starting at the tapped entry.
        assertEquals(1, player.playQueueCalls.size)
        val call = player.playQueueCalls.first()
        assertEquals(4, call.context.size)
        assertEquals(1, call.startIndex)
        assertEquals(0L, call.startPositionMs)
        // Background fill: the entries AFTER the tapped one first (in list
        // order), then the ones before it. The tapped entry itself is NOT an
        // insert — it is already in the player via playQueue.
        assertEquals(listOf("C", "D", "A"), player.inserts.map { it.bvid })
        // …with user-visible feedback along the way.
        assertTrue(messages.any { it.contains("解析音源中") })
    }

    @Test
    fun `playFromList passes a resume position through to the player`() = runTest {
        val resolver = FakeResolver()
        val player = RecordingPlayerOps()
        val coordinator = PlaybackQueueCoordinator(
            repo = resolver,
            scope = this,
            onMessage = {},
            player = player,
        )
        val list = listOf(track("A"), track("B"))

        val ok = coordinator.playFromList(
            list,
            index = 0,
            useListAsQueue = true,
            startPositionMs = 45_000L,
        )
        advanceUntilIdle()

        assertTrue(ok)
        assertEquals(1, player.playQueueCalls.size)
        // 播单记忆上次进度: the remembered position must reach the player's
        // initial seek, not be swallowed by the coordinator.
        assertEquals(45_000L, player.playQueueCalls.first().startPositionMs)
    }

    @Test
    fun `a failed entry is skipped instead of aborting the fill`() = runTest {
        val resolver = FakeResolver(failBvids = setOf("C"))
        val player = RecordingPlayerOps()
        val coordinator = PlaybackQueueCoordinator(
            repo = resolver,
            scope = this,
            onMessage = {},
            player = player,
        )
        val list = listOf(track("A"), track("B"), track("C"), track("D"))

        val ok = coordinator.playFromList(list, index = 0, useListAsQueue = true)
        advanceUntilIdle()

        assertTrue(ok)
        // C failed to resolve: it is skipped, D still lands after B.
        assertEquals(listOf("B", "D"), player.inserts.map { it.bvid })
    }

    @Test
    fun `playFromList reports failure when the tapped song cannot be resolved`() = runTest {
        val resolver = FakeResolver(failBvids = setOf("A"))
        val player = RecordingPlayerOps()
        val messages = mutableListOf<String>()
        val coordinator = PlaybackQueueCoordinator(
            repo = resolver,
            scope = this,
            onMessage = { messages += it },
            player = player,
        )

        val ok = coordinator.playFromList(listOf(track("A"), track("B")), index = 0, useListAsQueue = true)
        advanceUntilIdle()

        assertFalse(ok)
        assertTrue(player.playQueueCalls.isEmpty())
        assertTrue(messages.any { it.contains("播放失败") })
    }

    // ---- Shared resolution (cache / in-flight dedup) ----

    @Test
    fun `a second play of the same song is served from the resolve cache`() = runTest {
        val resolver = FakeResolver()
        val player = RecordingPlayerOps()
        val coordinator = PlaybackQueueCoordinator(
            repo = resolver,
            scope = this,
            onMessage = {},
            player = player,
        )
        val list = listOf(track("A"))

        coordinator.playFromList(list, useListAsQueue = true)
        advanceUntilIdle()
        coordinator.playFromList(list, useListAsQueue = true)
        advanceUntilIdle()

        // The first pass resolved A once; the replay must NOT hit B站 again.
        assertEquals(1, resolver.resolveCount["A"])
    }

    // ---- 下一首 on-demand (the B2 flow) ----

    @Test
    fun `needResolveNext resolves the entry and inserts it after the current one`() = runTest {
        val resolver = FakeResolver()
        val player = RecordingPlayerOps()
        val messages = mutableListOf<String>()
        val coordinator = PlaybackQueueCoordinator(
            repo = resolver,
            scope = this,
            onMessage = { messages += it },
            player = player,
        )
        player.currentUid = "playing-now"

        coordinator.onNeedResolveNext(track("X"))
        advanceUntilIdle()

        assertEquals(listOf("X"), player.nextOnDemand.map { it.bvid })
    }

    @Test
    fun `needResolveNext failure clears the hint and reports a message`() = runTest {
        val resolver = FakeResolver(failBvids = setOf("X"))
        val player = RecordingPlayerOps()
        val messages = mutableListOf<String>()
        val coordinator = PlaybackQueueCoordinator(
            repo = resolver,
            scope = this,
            onMessage = { messages += it },
            player = player,
        )
        player.currentUid = "playing-now"

        coordinator.onNeedResolveNext(track("X"))
        advanceUntilIdle()

        assertTrue(player.nextOnDemand.isEmpty())
        assertTrue(messages.any { it.contains("下一首解析失败") })
    }
}
