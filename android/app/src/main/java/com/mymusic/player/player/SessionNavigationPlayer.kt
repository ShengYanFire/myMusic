package com.mymusic.player.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps the ExoPlayer behind the [PlaybackService]'s MediaSession so that every
 * skip-to-next / skip-to-previous arriving from OUTSIDE the app — the media
 * notification, the lock screen, Bluetooth headsets, car/Android-Auto controls —
 * goes through [PlayerController.playNext] / [PlayerController.playPrevious]
 * instead of Media3's native linear timeline navigation.
 *
 * That is the one seam where the app's own queue logic becomes reachable from
 * the system UI: the shuffle round (随机播放) triggers from the notification's
 * 下一首/上一首, and an entry that has not reached the player timeline yet is
 * resolved on demand exactly like an in-app skip. The linear path is unchanged
 * in behavior — [PlayerController.playNext] falls back to a plain timeline seek
 * when the next entry is already there, which is what Media3 did before.
 *
 * All four variants are overridden because different control surfaces route to
 * different `Player` methods (`seekToNext`/`seekToPrevious` for media buttons,
 * `seekToNextMediaItem`/`seekToPreviousMediaItem` for the notification's skip
 * actions); covering the whole surface keeps every entry point consistent.
 * `hasNextMediaItem` / `hasPreviousMediaItem` are likewise widened to the LOGICAL
 * queue so the notification / lock-screen skip buttons stay enabled when the
 * player timeline has not been filled that far yet (the skip still resolves the
 * next entry on demand).
 */
class SessionNavigationPlayer(player: Player) : ForwardingPlayer(player) {

    override fun seekToNext() = PlayerController.playNext()

    override fun seekToNextMediaItem() = PlayerController.playNext()

    override fun seekToPrevious() = PlayerController.playPrevious()

    override fun seekToPreviousMediaItem() = PlayerController.playPrevious()

    override fun hasNextMediaItem(): Boolean =
        super.hasNextMediaItem() || PlayerController.hasNextInQueue()

    override fun hasPreviousMediaItem(): Boolean =
        super.hasPreviousMediaItem() || PlayerController.hasPreviousInQueue()
}