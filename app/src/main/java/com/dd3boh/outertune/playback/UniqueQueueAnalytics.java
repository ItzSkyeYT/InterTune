/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * media3's own analytics, told the player's queue of periods with each period once (#102, #103).
 * Its tracker makes a map of that queue, and when a period is listed twice the map throws
 * "Multiple entries with same key" on the main thread and the app closes; seen on 0.12.9 with the
 * next song preloaded (media3 1.8.0, no fix upstream as of 1.11.1). Every other call goes to
 * media3's collector as it is.
 *
 * <p>Written out method by method in Java: Kotlin's delegation leaves out the methods with a
 * default body in Java (most of the player's listener), and the play counts and the health line
 * would stop hearing the player. Generated from media3 1.8.0's AnalyticsCollector;
 * UniqueQueueAnalyticsTest fails when a newer media3 adds a method this does not pass on.
 */
@UnstableApi
public final class UniqueQueueAnalytics implements AnalyticsCollector {

    private final DefaultAnalyticsCollector inner;

    public UniqueQueueAnalytics() {
        this(new DefaultAnalyticsCollector(Clock.DEFAULT));
    }

    public UniqueQueueAnalytics(DefaultAnalyticsCollector inner) {
        this.inner = inner;
    }

    /** The queue with each period once, in its order: the list itself where none repeats. */
    public static <T> List<T> unique(List<T> queue) {
        if (queue.size() < 2 || new HashSet<>(queue).size() == queue.size()) return queue;
        List<T> out = new ArrayList<>(queue.size());
        HashSet<T> seen = new HashSet<>();
        for (T t : queue) if (seen.add(t)) out.add(t);
        return out;
    }
    // Listener
    @Override public void onAudioAttributesChanged(androidx.media3.common.AudioAttributes p0) { inner.onAudioAttributesChanged(p0); }
    @Override public void onAudioSessionIdChanged(int p0) { inner.onAudioSessionIdChanged(p0); }
    @Override public void onAvailableCommandsChanged(androidx.media3.common.Player.Commands p0) { inner.onAvailableCommandsChanged(p0); }
    @Override public void onCues(androidx.media3.common.text.CueGroup p0) { inner.onCues(p0); }
    @SuppressWarnings("deprecation") @Override public void onCues(java.util.List<androidx.media3.common.text.Cue> p0) { inner.onCues(p0); }
    @Override public void onDeviceInfoChanged(androidx.media3.common.DeviceInfo p0) { inner.onDeviceInfoChanged(p0); }
    @Override public void onDeviceVolumeChanged(int p0, boolean p1) { inner.onDeviceVolumeChanged(p0, p1); }
    @Override public void onEvents(androidx.media3.common.Player p0, androidx.media3.common.Player.Events p1) { inner.onEvents(p0, p1); }
    @Override public void onIsLoadingChanged(boolean p0) { inner.onIsLoadingChanged(p0); }
    @Override public void onIsPlayingChanged(boolean p0) { inner.onIsPlayingChanged(p0); }
    @SuppressWarnings("deprecation") @Override public void onLoadingChanged(boolean p0) { inner.onLoadingChanged(p0); }
    @Override public void onMaxSeekToPreviousPositionChanged(long p0) { inner.onMaxSeekToPreviousPositionChanged(p0); }
    @Override public void onMediaItemTransition(androidx.media3.common.MediaItem p0, int p1) { inner.onMediaItemTransition(p0, p1); }
    @Override public void onMediaMetadataChanged(androidx.media3.common.MediaMetadata p0) { inner.onMediaMetadataChanged(p0); }
    @Override public void onMetadata(androidx.media3.common.Metadata p0) { inner.onMetadata(p0); }
    @Override public void onPlayWhenReadyChanged(boolean p0, int p1) { inner.onPlayWhenReadyChanged(p0, p1); }
    @Override public void onPlaybackParametersChanged(androidx.media3.common.PlaybackParameters p0) { inner.onPlaybackParametersChanged(p0); }
    @Override public void onPlaybackStateChanged(int p0) { inner.onPlaybackStateChanged(p0); }
    @Override public void onPlaybackSuppressionReasonChanged(int p0) { inner.onPlaybackSuppressionReasonChanged(p0); }
    @Override public void onPlayerError(androidx.media3.common.PlaybackException p0) { inner.onPlayerError(p0); }
    @Override public void onPlayerErrorChanged(androidx.media3.common.PlaybackException p0) { inner.onPlayerErrorChanged(p0); }
    @SuppressWarnings("deprecation") @Override public void onPlayerStateChanged(boolean p0, int p1) { inner.onPlayerStateChanged(p0, p1); }
    @Override public void onPlaylistMetadataChanged(androidx.media3.common.MediaMetadata p0) { inner.onPlaylistMetadataChanged(p0); }
    @Override public void onPositionDiscontinuity(androidx.media3.common.Player.PositionInfo p0, androidx.media3.common.Player.PositionInfo p1, int p2) { inner.onPositionDiscontinuity(p0, p1, p2); }
    @SuppressWarnings("deprecation") @Override public void onPositionDiscontinuity(int p0) { inner.onPositionDiscontinuity(p0); }
    @Override public void onRenderedFirstFrame() { inner.onRenderedFirstFrame(); }
    @Override public void onRepeatModeChanged(int p0) { inner.onRepeatModeChanged(p0); }
    @Override public void onSeekBackIncrementChanged(long p0) { inner.onSeekBackIncrementChanged(p0); }
    @Override public void onSeekForwardIncrementChanged(long p0) { inner.onSeekForwardIncrementChanged(p0); }
    @Override public void onShuffleModeEnabledChanged(boolean p0) { inner.onShuffleModeEnabledChanged(p0); }
    @Override public void onSkipSilenceEnabledChanged(boolean p0) { inner.onSkipSilenceEnabledChanged(p0); }
    @Override public void onSurfaceSizeChanged(int p0, int p1) { inner.onSurfaceSizeChanged(p0, p1); }
    @Override public void onTimelineChanged(androidx.media3.common.Timeline p0, int p1) { inner.onTimelineChanged(p0, p1); }
    @Override public void onTrackSelectionParametersChanged(androidx.media3.common.TrackSelectionParameters p0) { inner.onTrackSelectionParametersChanged(p0); }
    @Override public void onTracksChanged(androidx.media3.common.Tracks p0) { inner.onTracksChanged(p0); }
    @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize p0) { inner.onVideoSizeChanged(p0); }
    @Override public void onVolumeChanged(float p0) { inner.onVolumeChanged(p0); }

    // AnalyticsCollector
    @Override public void addListener(androidx.media3.exoplayer.analytics.AnalyticsListener p0) { inner.addListener(p0); }
    @Override public void notifySeekStarted() { inner.notifySeekStarted(); }
    @Override public void onAudioCodecError(java.lang.Exception p0) { inner.onAudioCodecError(p0); }
    @Override public void onAudioDecoderInitialized(java.lang.String p0, long p1, long p2) { inner.onAudioDecoderInitialized(p0, p1, p2); }
    @Override public void onAudioDecoderReleased(java.lang.String p0) { inner.onAudioDecoderReleased(p0); }
    @Override public void onAudioDisabled(androidx.media3.exoplayer.DecoderCounters p0) { inner.onAudioDisabled(p0); }
    @Override public void onAudioEnabled(androidx.media3.exoplayer.DecoderCounters p0) { inner.onAudioEnabled(p0); }
    @Override public void onAudioInputFormatChanged(androidx.media3.common.Format p0, androidx.media3.exoplayer.DecoderReuseEvaluation p1) { inner.onAudioInputFormatChanged(p0, p1); }
    @Override public void onAudioPositionAdvancing(long p0) { inner.onAudioPositionAdvancing(p0); }
    @Override public void onAudioSinkError(java.lang.Exception p0) { inner.onAudioSinkError(p0); }
    @Override public void onAudioTrackInitialized(androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig p0) { inner.onAudioTrackInitialized(p0); }
    @Override public void onAudioTrackReleased(androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig p0) { inner.onAudioTrackReleased(p0); }
    @Override public void onAudioUnderrun(int p0, long p1, long p2) { inner.onAudioUnderrun(p0, p1, p2); }
    @Override public void onDroppedFrames(int p0, long p1) { inner.onDroppedFrames(p0, p1); }
    @Override public void onRenderedFirstFrame(java.lang.Object p0, long p1) { inner.onRenderedFirstFrame(p0, p1); }
    @Override public void onRendererReadyChanged(int p0, int p1, boolean p2) { inner.onRendererReadyChanged(p0, p1, p2); }
    @Override public void onVideoCodecError(java.lang.Exception p0) { inner.onVideoCodecError(p0); }
    @Override public void onVideoDecoderInitialized(java.lang.String p0, long p1, long p2) { inner.onVideoDecoderInitialized(p0, p1, p2); }
    @Override public void onVideoDecoderReleased(java.lang.String p0) { inner.onVideoDecoderReleased(p0); }
    @Override public void onVideoDisabled(androidx.media3.exoplayer.DecoderCounters p0) { inner.onVideoDisabled(p0); }
    @Override public void onVideoEnabled(androidx.media3.exoplayer.DecoderCounters p0) { inner.onVideoEnabled(p0); }
    @Override public void onVideoFrameProcessingOffset(long p0, int p1) { inner.onVideoFrameProcessingOffset(p0, p1); }
    @Override public void onVideoInputFormatChanged(androidx.media3.common.Format p0, androidx.media3.exoplayer.DecoderReuseEvaluation p1) { inner.onVideoInputFormatChanged(p0, p1); }
    @Override public void release() { inner.release(); }
    @Override public void removeListener(androidx.media3.exoplayer.analytics.AnalyticsListener p0) { inner.removeListener(p0); }
    @Override public void setPlayer(androidx.media3.common.Player p0, android.os.Looper p1) { inner.setPlayer(p0, p1); }
    @Override public void updateMediaPeriodQueueInfo(java.util.List<androidx.media3.exoplayer.source.MediaSource.MediaPeriodId> p0, @Nullable androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1) { inner.updateMediaPeriodQueueInfo(unique(p0), p1); }

    // DrmSessionEventListener
    @Override public void onDrmKeysLoaded(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1) { inner.onDrmKeysLoaded(p0, p1); }
    @Override public void onDrmKeysRemoved(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1) { inner.onDrmKeysRemoved(p0, p1); }
    @Override public void onDrmKeysRestored(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1) { inner.onDrmKeysRestored(p0, p1); }
    @Override public void onDrmSessionAcquired(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, int p2) { inner.onDrmSessionAcquired(p0, p1, p2); }
    @Override public void onDrmSessionManagerError(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, java.lang.Exception p2) { inner.onDrmSessionManagerError(p0, p1, p2); }
    @Override public void onDrmSessionReleased(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1) { inner.onDrmSessionReleased(p0, p1); }

    // MediaSourceEventListener
    @Override public void onDownstreamFormatChanged(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, androidx.media3.exoplayer.source.MediaLoadData p2) { inner.onDownstreamFormatChanged(p0, p1, p2); }
    @Override public void onLoadCanceled(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, androidx.media3.exoplayer.source.LoadEventInfo p2, androidx.media3.exoplayer.source.MediaLoadData p3) { inner.onLoadCanceled(p0, p1, p2, p3); }
    @Override public void onLoadCompleted(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, androidx.media3.exoplayer.source.LoadEventInfo p2, androidx.media3.exoplayer.source.MediaLoadData p3) { inner.onLoadCompleted(p0, p1, p2, p3); }
    @Override public void onLoadError(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, androidx.media3.exoplayer.source.LoadEventInfo p2, androidx.media3.exoplayer.source.MediaLoadData p3, java.io.IOException p4, boolean p5) { inner.onLoadError(p0, p1, p2, p3, p4, p5); }
    @Override public void onLoadStarted(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, androidx.media3.exoplayer.source.LoadEventInfo p2, androidx.media3.exoplayer.source.MediaLoadData p3, int p4) { inner.onLoadStarted(p0, p1, p2, p3, p4); }
    @Override public void onUpstreamDiscarded(int p0, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId p1, androidx.media3.exoplayer.source.MediaLoadData p2) { inner.onUpstreamDiscarded(p0, p1, p2); }

    // EventListener
    @Override public void onBandwidthSample(int p0, long p1, long p2) { inner.onBandwidthSample(p0, p1, p2); }
}
