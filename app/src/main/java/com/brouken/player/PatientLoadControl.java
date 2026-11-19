package com.brouken.player;

import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;

/**
 * Builds a network-focused Media3 load-control policy.
 * Local playback retains Media3's standard defaults.
 */
final class PatientLoadControl {
    private PatientLoadControl() {}

    static LoadControl create(boolean networkPlayback) {
        if (!networkPlayback) {
            return new DefaultLoadControl.Builder().build();
        }

        BufferProfile profile = BufferProfile.network();
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        profile.minBufferMs,
                        profile.maxBufferMs,
                        profile.bufferForPlaybackMs,
                        profile.bufferForPlaybackAfterRebufferMs)
                .setPrioritizeTimeOverSizeThresholds(profile.prioritizeTimeOverSizeThresholds)
                .build();
    }
}
