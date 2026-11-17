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

import java.io.IOException;
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
    private static final long[] RETRY_DELAYS_MS = {300, 700, 1200, 2000};

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
            if (currentTracker == null || playlistUri == null) {
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
            controller.onPlaylistRequested(dataSpec.uri);
            return upstream.open(dataSpec);
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
                    if (!isNotFound(error) || retryIndex >= RETRY_DELAYS_MS.length
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
