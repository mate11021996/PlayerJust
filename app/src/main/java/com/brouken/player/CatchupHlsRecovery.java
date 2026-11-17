package com.brouken.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.hls.HlsDataSourceFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retries transient catch-up HLS segment 404 responses while asking Media3's
 * own playlist tracker to refresh the active media playlist in parallel.
 *
 * The segment load thread waits between attempts; playback can continue from
 * already buffered media while the playlist tracker performs its own reload.
 */
final class CatchupHlsRecovery {
    // Keep the retry window shorter than the old 4.2-second sequence.
    // The live playlist is refreshed independently by Media3's tracker.
    private static final long[] RETRY_DELAYS_MS = {150, 300, 600, 1000};

    private CatchupHlsRecovery() {}

    static HlsMediaSource.Factory createFactory(
            GrowingHlsDataSource.Factory growingDataSourceFactory,
            boolean enable404Recovery) {
        RecoveryController controller = new RecoveryController();

        HlsDataSourceFactory hlsDataSourceFactory = dataType -> {
            DataSource source = growingDataSourceFactory.createDataSource();
            if (dataType == C.DATA_TYPE_MANIFEST) {
                return new PlaylistTrackingDataSource(source, controller);
            }
            if (enable404Recovery && dataType == C.DATA_TYPE_MEDIA) {
                return new SegmentRetryDataSource(source, controller);
            }
            return source;
        };

        HlsMediaSource.Factory factory = new HlsMediaSource.Factory(hlsDataSourceFactory);
        if (enable404Recovery) {
            factory.setPlaylistTrackerFactory(
                    (dataSourceFactory, loadErrorHandlingPolicy, playlistParserFactory, cmcdConfiguration) -> {
                        HlsPlaylistTracker tracker = DefaultHlsPlaylistTracker.FACTORY.createTracker(
                                dataSourceFactory,
                                loadErrorHandlingPolicy,
                                playlistParserFactory,
                                cmcdConfiguration);
                        controller.setTracker(tracker);
                        return tracker;
                    });
        }
        return factory;
    }

    private static final class RecoveryController {
        private volatile HlsPlaylistTracker tracker;
        private volatile Uri activePlaylistUri;
        private final Map<String, PlaylistState> playlistStates = new HashMap<>();
        private volatile boolean playlistHasAdvanced;

        private static final class PlaylistState {
            final long mediaSequence;
            final int segmentCount;

            PlaylistState(long mediaSequence, int segmentCount) {
                this.mediaSequence = mediaSequence;
                this.segmentCount = segmentCount;
            }
        }

        synchronized void onPlaylistLoaded(Uri uri, byte[] bytes) {
            if (uri == null || bytes == null || bytes.length == 0) return;
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (!body.startsWith("#EXTM3U") || !body.contains("#EXTINF:")) return;

            long sequence = 0L;
            java.util.regex.Matcher sequenceMatcher = java.util.regex.Pattern
                    .compile("(?m)^#EXT-X-MEDIA-SEQUENCE:(\\d+)")
                    .matcher(body);
            if (sequenceMatcher.find()) {
                try { sequence = Long.parseLong(sequenceMatcher.group(1)); }
                catch (NumberFormatException ignored) { return; }
            }

            java.util.regex.Matcher segmentMatcher = java.util.regex.Pattern
                    .compile("(?m)^#EXTINF:")
                    .matcher(body);
            int segmentCount = 0;
            while (segmentMatcher.find()) segmentCount++;

            String key = uri.toString();
            PlaylistState previous = playlistStates.put(key, new PlaylistState(sequence, segmentCount));
            if (previous != null && (sequence > previous.mediaSequence
                    || segmentCount > previous.segmentCount)) {
                playlistHasAdvanced = true;
            }
            // Only media playlists (not master playlists) become refresh targets.
            activePlaylistUri = uri;
        }

        boolean isAdvancingPlaylist() {
            return playlistHasAdvanced;
        }

        void setTracker(HlsPlaylistTracker tracker) {
            this.tracker = tracker;
        }

        void onPlaylistRequested(Uri uri) {
            if (uri != null) {
                activePlaylistUri = uri;
            }
        }

        boolean requestPlaylistRefresh() {
            HlsPlaylistTracker currentTracker = tracker;
            Uri playlistUri = activePlaylistUri;
            if (currentTracker == null || playlistUri == null || !playlistHasAdvanced) {
                return false;
            }
            try {
                // DefaultHlsPlaylistTracker schedules the actual network load on
                // its playlist handler, independently of this segment load thread.
                currentTracker.refreshPlaylist(playlistUri);
                return true;
            } catch (RuntimeException ignored) {
                // The tracker may not have registered this URI yet or may be releasing.
                return false;
            }
        }
    }

    private static final class PlaylistTrackingDataSource implements DataSource {
        private final DataSource upstream;
        private final RecoveryController controller;
        private Uri playlistUri;
        private ByteArrayOutputStream playlistBytes;

        PlaylistTrackingDataSource(DataSource upstream, RecoveryController controller) {
            this.upstream = upstream;
            this.controller = controller;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            playlistUri = dataSpec.uri;
            playlistBytes = new ByteArrayOutputStream();
            return upstream.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = upstream.read(buffer, offset, length);
            if (count > 0 && playlistBytes != null && playlistBytes.size() < 2 * 1024 * 1024) {
                playlistBytes.write(buffer, offset, count);
            } else if (count == C.RESULT_END_OF_INPUT && playlistBytes != null) {
                controller.onPlaylistLoaded(playlistUri, playlistBytes.toByteArray());
                playlistBytes = null;
            }
            return count;
        }

        @Override
        public Uri getUri() {
            return upstream.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return upstream.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            playlistBytes = null;
            playlistUri = null;
            upstream.close();
        }
    }

    private static final class SegmentRetryDataSource implements DataSource {
        private final DataSource upstream;
        private final RecoveryController controller;

        SegmentRetryDataSource(DataSource upstream, RecoveryController controller) {
            this.upstream = upstream;
            this.controller = controller;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            int retryIndex = 0;
            while (true) {
                try {
                    return upstream.open(dataSpec);
                } catch (IOException error) {
                    // Only use in-place retries after the media playlist has
                    // demonstrably advanced. Static/final VOD playlists retain
                    // their existing source-level recovery behavior.
                    if (!isNotFound(error) || retryIndex >= RETRY_DELAYS_MS.length
                            || !controller.isAdvancingPlaylist()
                            || !controller.requestPlaylistRefresh()) {
                        throw error;
                    }

                    try {
                        upstream.close();
                    } catch (IOException ignored) {
                        // The failed open may not have acquired a connection.
                    }

                    try {
                        Thread.sleep(RETRY_DELAYS_MS[retryIndex++]);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while retrying a missing HLS segment", interrupted);
                    }
                }
            }
        }

        private static boolean isNotFound(IOException error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                    return ((HttpDataSource.InvalidResponseCodeException) current).responseCode == 404;
                }
                current = current.getCause();
            }
            return false;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return upstream.read(buffer, offset, length);
        }

        @Override
        public Uri getUri() {
            return upstream.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return upstream.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }
    }
}
