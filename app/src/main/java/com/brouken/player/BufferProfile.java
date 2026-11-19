package com.brouken.player;

/**
 * Conservative buffer settings for network playback.
 *
 * The profile keeps startup and rebuffer thresholds modest while allowing
 * Media3 to retain a larger forward buffer for unstable IPTV connections.
 */
final class BufferProfile {
    final int minBufferMs;
    final int maxBufferMs;
    final int bufferForPlaybackMs;
    final int bufferForPlaybackAfterRebufferMs;
    final boolean prioritizeTimeOverSizeThresholds;

    private BufferProfile(int minBufferMs, int maxBufferMs,
                          int bufferForPlaybackMs, int bufferForPlaybackAfterRebufferMs,
                          boolean prioritizeTimeOverSizeThresholds) {
        this.minBufferMs = minBufferMs;
        this.maxBufferMs = maxBufferMs;
        this.bufferForPlaybackMs = bufferForPlaybackMs;
        this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs;
        this.prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds;
    }

    static BufferProfile network() {
        return new BufferProfile(15_000, 60_000, 1_500, 3_000, true);
    }
}
