package com.brouken.player;

import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.util.UriUtil;
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
import java.util.LinkedHashMap;
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
    private static final String TAG = "CatchupHlsRecovery";
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
        private final Map<String, PlaylistSnapshot> playlistSnapshots = new HashMap<>();

        private static final class PlaylistSnapshot {
            final long version;
            final Map<Long, Uri> segmentsBySequence;
            final Map<String, Long> sequenceByUri;

            PlaylistSnapshot(long version, Map<Long, Uri> segmentsBySequence) {
                this.version = version;
                this.segmentsBySequence = segmentsBySequence;
                this.sequenceByUri = new HashMap<>();
                for (Map.Entry<Long, Uri> entry : segmentsBySequence.entrySet()) {
                    sequenceByUri.put(entry.getValue().toString(), entry.getKey());
                }
            }
        }

        private long playlistVersion;

        synchronized void onPlaylistLoaded(Uri uri, byte[] bytes) {
            if (uri == null || bytes == null || bytes.length == 0) return;
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (!body.startsWith("#EXTM3U") || !body.contains("#EXTINF:")) return;

            long mediaSequence = 0L;
            java.util.regex.Matcher sequenceMatcher = java.util.regex.Pattern
                    .compile("(?m)^#EXT-X-MEDIA-SEQUENCE:(\\d+)")
                    .matcher(body);
            if (sequenceMatcher.find()) {
                try {
                    mediaSequence = Long.parseLong(sequenceMatcher.group(1));
                } catch (NumberFormatException ignored) {
                    return;
                }
            }

            Map<Long, Uri> segments = new LinkedHashMap<>();
            long sequence = mediaSequence;
            boolean nextLineIsSegmentUri = false;
            String[] lines = body.split("\\r?\\n");
            for (String line : lines) {
                if (line.startsWith("#EXTINF:")) {
                    nextLineIsSegmentUri = true;
                } else if (nextLineIsSegmentUri && !line.isEmpty() && !line.startsWith("#")) {
                    Uri segmentUri = UriUtil.resolveToUri(uri.toString(), line.trim());
                    segments.put(sequence++, segmentUri);
                    nextLineIsSegmentUri = false;
                }
            }
            if (segments.isEmpty()) return;

            String key = uri.toString();
            playlistVersion++;
            playlistSnapshots.put(key, new PlaylistSnapshot(playlistVersion, segments));
            activePlaylistUri = uri;
            notifyAll();
            Log.d(TAG, "Playlist loaded: sequence=" + mediaSequence
                    + ", segments=" + segments.size() + ", version=" + playlistVersion);
        }

        synchronized Long findSequenceForSegment(Uri segmentUri) {
            if (segmentUri == null) return null;
            for (PlaylistSnapshot snapshot : playlistSnapshots.values()) {
                Long sequence = snapshot.sequenceByUri.get(segmentUri.toString());
                if (sequence != null) return sequence;
            }
            return null;
        }

        synchronized Uri awaitUpdatedSegment(Uri playlistUri, long sequence, Uri failedUri,
                                             long previousVersion, long timeoutMs) throws IOException {
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            String key = playlistUri.toString();
            while (true) {
                PlaylistSnapshot snapshot = playlistSnapshots.get(key);
                if (snapshot != null && snapshot.version > previousVersion) {
                    Uri updatedUri = snapshot.segmentsBySequence.get(sequence);
                    if (updatedUri == null) {
                        Log.d(TAG, "Refreshed playlist no longer contains failed segment sequence " + sequence);
                        return null;
                    }
                    if (!updatedUri.equals(failedUri)) {
                        Log.d(TAG, "Playlist changed segment sequence " + sequence + " to " + updatedUri);
                        return updatedUri;
                    }
                }
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) return null;
                try {
                    wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for refreshed HLS playlist", interrupted);
                }
            }
        }

        synchronized long getPlaylistVersion(Uri playlistUri) {
            if (playlistUri == null) return playlistVersion;
            PlaylistSnapshot snapshot = playlistSnapshots.get(playlistUri.toString());
            return snapshot == null ? playlistVersion : snapshot.version;
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
            DataSpec currentSpec = dataSpec;
            int retryIndex = 0;
            Long segmentSequence = controller.findSequenceForSegment(dataSpec.uri);
            Uri playlistUri = controller.activePlaylistUri;
            while (true) {
                try {
                    return upstream.open(currentSpec);
                } catch (IOException error) {
                    if (!isNotFound(error) || retryIndex >= RETRY_DELAYS_MS.length) {
                        throw error;
                    }

                    Uri failedUri = currentSpec.uri;
                    long previousVersion = controller.getPlaylistVersion(playlistUri);
                    boolean refreshRequested = controller.requestPlaylistRefresh();
                    Log.d(TAG, "Segment returned 404; retry " + (retryIndex + 1)
                            + "/" + RETRY_DELAYS_MS.length
                            + ", playlist refresh requested=" + refreshRequested
                            + ", sequence=" + segmentSequence);

                    try {
                        upstream.close();
                    } catch (IOException ignored) {
                        // The failed open may not have acquired a connection.
                    }

                    long waitMs = RETRY_DELAYS_MS[retryIndex++];
                    if (segmentSequence != null && playlistUri != null) {
                        Uri replacement = controller.awaitUpdatedSegment(
                                playlistUri, segmentSequence, failedUri, previousVersion,
                                Math.max(1200L, waitMs));
                        if (replacement != null) {
                            currentSpec = currentSpec.buildUpon().setUri(replacement).build();
                            Log.d(TAG, "Retrying sequence " + segmentSequence
                                    + " with refreshed segment URI: " + replacement);
                        } else {
                            Log.d(TAG, "No replacement URI for sequence " + segmentSequence
                                    + "; retrying current URI after playlist refresh wait");
                        }
                    } else {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while retrying a missing HLS segment", interrupted);
                        }
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
