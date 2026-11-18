package com.brouken.player;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
        controller.setPlaylistDataSourceFactory(growingDataSourceFactory);

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

    private static String safeUri(Uri uri) {
        if (uri == null) return "null";
        String path = uri.getPath();
        String suffix = "";
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot > path.lastIndexOf('/')) suffix = path.substring(dot);
            if (suffix.length() > 12) suffix = "";
        }
        java.util.Set<String> keys = new java.util.TreeSet<>(uri.getQueryParameterNames());
        return uri.getScheme() + "://" + uri.getHost()
                + " [pathSegments=" + uri.getPathSegments().size()
                + ", suffix=" + suffix + ", queryKeys=" + keys + "]";
    }

    private static String contentType(Map<String, List<String>> headers) {
        if (headers == null) return "unknown";
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return "missing";
    }

    private static final class RecoveryController {
        private volatile HlsPlaylistTracker tracker;
        private volatile Handler trackerHandler;
        private volatile DataSource.Factory playlistDataSourceFactory;
        private volatile DataSpec activePlaylistDataSpec;
        private volatile Uri activePlaylistRequestUri;
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
            if (!body.startsWith("#EXTM3U") || !body.contains("#EXTINF:")) {
                Log.w(TAG, "Playlist parse skipped: uri=" + safeUri(uri) + ", bytes=" + bytes.length
                        + ", startsWithM3u=" + body.startsWith("#EXTM3U")
                        + ", hasExtinf=" + body.contains("#EXTINF:"));
                return;
            }

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
            if (segments.isEmpty()) {
                Log.w(TAG, "Playlist parse found no segments: uri=" + uri + ", bytes=" + bytes.length);
                return;
            }

            String key = uri.toString();
            playlistVersion++;
            playlistSnapshots.put(key, new PlaylistSnapshot(playlistVersion, segments));
            activePlaylistUri = uri;
            notifyAll();
            Log.d(TAG, "Playlist loaded: uri=" + safeUri(uri) + ", sequence=" + mediaSequence
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

        private static final class SegmentReplacement {
            final long sequence;
            final Uri uri;

            SegmentReplacement(long sequence, Uri uri) {
                this.sequence = sequence;
                this.uri = uri;
            }
        }

        void setPlaylistDataSourceFactory(DataSource.Factory factory) {
            this.playlistDataSourceFactory = factory;
        }

        private void fetchPlaylistDirectly(Uri playlistUri) {
            DataSource.Factory factory = playlistDataSourceFactory;
            if (factory == null || playlistUri == null) return;

            Log.d(TAG, "Direct playlist refresh starting: " + safeUri(playlistUri));
            // The playlist request may have been redirected, and the tracker may not
            // yet have completed its first manifest load when a segment returns 404.
            // Reuse the full original request when available; otherwise retry the
            // original .m3u8 URI with a plain GET through the same configured factory.
            DataSpec requestSpec = activePlaylistDataSpec;
            Uri requestUri = activePlaylistRequestUri;
            if (requestSpec == null && requestUri != null) {
                requestSpec = new DataSpec(requestUri);
            }
            if (requestSpec == null) {
                Log.w(TAG, "Direct playlist refresh skipped: original playlist URI not captured yet");
                return;
            }
            DataSource source = factory.createDataSource();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                // Reuse Media3's original playlist request verbatim. Stalker portals may
                // require request headers, method, or body that are absent from a bare GET.
                long openedLength = source.open(requestSpec);
                Log.d(TAG, "Direct playlist refresh opened: length=" + openedLength
                        + ", responseUri=" + safeUri(source.getUri())
                        + ", contentType=" + contentType(source.getResponseHeaders()));
                byte[] buffer = new byte[8192];
                int count;
                while ((count = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
                    if (count > 0) {
                        output.write(buffer, 0, count);
                        if (output.size() > 2 * 1024 * 1024) {
                            Log.w(TAG, "Direct playlist refresh exceeded size limit");
                            return;
                        }
                    }
                }
                byte[] playlistBytes = output.toByteArray();
                Log.d(TAG, "Direct playlist refresh read: bytes=" + playlistBytes.length
                        + ", uri=" + safeUri(playlistUri));
                // The portal redirects the original .m3u8 request to its timeshift host.
                // Parse relative segment URLs against the final response URI, but always
                // repeat the original request DataSpec above (not the redirected URL).
                Uri responseUri = source.getUri() != null ? source.getUri() : playlistUri;
                onPlaylistLoaded(responseUri, playlistBytes);
            } catch (IOException error) {
                Log.w(TAG, "Direct playlist refresh failed for " + safeUri(playlistUri), error);
            } finally {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // Ignore close failures after the refresh attempt.
                }
            }
        }

        synchronized SegmentReplacement awaitUpdatedSegment(Uri playlistUri, long sequence, Uri failedUri,
                                                              long previousVersion, long timeoutMs)
                throws IOException {
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            long nextRefreshMs = 0L;
            String key = playlistUri.toString();
            while (true) {
                PlaylistSnapshot snapshot = playlistSnapshots.get(key);
                if (snapshot != null && snapshot.version > previousVersion) {
                    Uri updatedUri = snapshot.segmentsBySequence.get(sequence);
                    if (updatedUri != null && !updatedUri.equals(failedUri)) {
                        Log.d(TAG, "Playlist changed segment sequence " + sequence + " to " + updatedUri);
                        return new SegmentReplacement(sequence, updatedUri);
                    }

                    // The server still advertises the failed URI, or has already removed
                    // that sequence. Continue from the next segment present in the refreshed
                    // playlist instead of requesting the same known-bad URL again.
                    for (Map.Entry<Long, Uri> entry : snapshot.segmentsBySequence.entrySet()) {
                        if (entry.getKey() > sequence) {
                            Log.w(TAG, "Skipping unavailable segment sequence " + sequence
                                    + " and continuing with sequence " + entry.getKey()
                                    + ": " + entry.getValue());
                            return new SegmentReplacement(entry.getKey(), entry.getValue());
                        }
                    }
                    Log.w(TAG, "Refreshed playlist has no segment after failed sequence " + sequence);
                    return null;
                }

                long now = android.os.SystemClock.elapsedRealtime();
                long remaining = deadline - now;
                if (remaining <= 0) return null;

                // refreshPlaylist() is a no-op while Media3 is already loading this playlist.
                // Re-post it periodically so a refresh is issued as soon as that load finishes.
                if (now >= nextRefreshMs) {
                    // Do not rely only on refreshPlaylist(): Media3 may already have a
                    // playlist load in flight and ignore that request. Fetch the playlist
                    // through the same configured source factory on this loader thread.
                    fetchPlaylistDirectly(playlistUri);
                    // Do not call DefaultHlsPlaylistTracker.refreshPlaylist() here.
                    // Its bundle key is not guaranteed to match the redirected URI
                    // exposed by DataSource.getUri(), and Media3 1.6.0-rc02 can NPE
                    // when asked to refresh a URI that has no registered bundle.
                    nextRefreshMs = now + 750L;
                }

                try {
                    wait(Math.min(remaining, 250L));
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
            Looper looper = Looper.myLooper();
            if (looper == null) looper = Looper.getMainLooper();
            this.trackerHandler = new Handler(looper);
        }

        void onPlaylistRequested(DataSpec dataSpec) {
            if (dataSpec == null || dataSpec.uri == null) return;
            String path = dataSpec.uri.getPath();
            // Keep the original media-playlist request, including its method, headers,
            // body and flags. Do not replace it with the redirected timeshift URL.
            if (path != null && path.toLowerCase(java.util.Locale.US).endsWith(".m3u8")) {
                activePlaylistDataSpec = dataSpec;
                activePlaylistRequestUri = dataSpec.uri;
                Log.d(TAG, "Remembered original playlist request: " + safeUri(dataSpec.uri));
            }
        }

        boolean requestPlaylistRefresh() {
            return requestPlaylistRefresh(activePlaylistRequestUri);
        }

        boolean requestPlaylistRefresh(Uri playlistUri) {
            HlsPlaylistTracker currentTracker = tracker;
            Handler handler = trackerHandler;
            // Media3 indexes playlist bundles by the request URI, not the final URI
            // returned after an HTTP redirect. Passing the redirected timeshift URI
            // makes DefaultHlsPlaylistTracker.refreshPlaylist() dereference a null bundle.
            Uri trackerPlaylistUri = activePlaylistRequestUri;
            if (trackerPlaylistUri == null) trackerPlaylistUri = playlistUri;
            if (currentTracker == null || handler == null || trackerPlaylistUri == null) {
                return false;
            }
            final Uri refreshUri = trackerPlaylistUri;
            Log.d(TAG, "Requesting Media3 playlist refresh using original URI: " + safeUri(refreshUri));
            // HlsPlaylistTracker is owned by the player/application looper. Never call it
            // directly from the segment loader thread.
            return handler.post(() -> {
                try {
                    currentTracker.refreshPlaylist(refreshUri);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Playlist refresh request failed for " + playlistUri, error);
                }
            });
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
            controller.onPlaylistRequested(dataSpec);
            playlistBytes = new ByteArrayOutputStream();
            long openedLength = upstream.open(dataSpec);
            Log.d(TAG, "Playlist request opened: requestUri=" + safeUri(dataSpec.uri)
                    + ", responseUri=" + safeUri(upstream.getUri())
                    + ", contentType=" + contentType(upstream.getResponseHeaders())
                    + ", method=" + dataSpec.httpMethod
                    + ", flags=" + dataSpec.flags
                    + ", bodyBytes=" + (dataSpec.httpBody == null ? 0 : dataSpec.httpBody.length)
                    + ", requestHeaderNames=" + (dataSpec.httpRequestHeaders == null
                            ? "[]" : new java.util.TreeSet<>(dataSpec.httpRequestHeaders.keySet())));
            return openedLength;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = upstream.read(buffer, offset, length);
            if (count > 0 && playlistBytes != null && playlistBytes.size() < 2 * 1024 * 1024) {
                playlistBytes.write(buffer, offset, count);
            } else if (count == C.RESULT_END_OF_INPUT && playlistBytes != null) {
                Log.d(TAG, "Playlist response completed: requestUri=" + safeUri(playlistUri)
                        + ", responseUri=" + safeUri(upstream.getUri())
                        + ", bytes=" + playlistBytes.size());
                Uri responseUri = upstream.getUri() != null ? upstream.getUri() : playlistUri;
                controller.onPlaylistLoaded(responseUri, playlistBytes.toByteArray());
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
                    Log.d(TAG, "Segment returned 404; retry " + (retryIndex + 1)
                            + "/" + RETRY_DELAYS_MS.length
                            + ", direct playlist refresh will be attempted"
                            + ", sequence=" + segmentSequence);

                    try {
                        upstream.close();
                    } catch (IOException ignored) {
                        // The failed open may not have acquired a connection.
                    }

                    long waitMs = RETRY_DELAYS_MS[retryIndex++];
                    if (segmentSequence != null && playlistUri != null) {
                        RecoveryController.SegmentReplacement replacement = controller.awaitUpdatedSegment(
                                playlistUri, segmentSequence, failedUri, previousVersion,
                                Math.max(2000L, waitMs));
                        if (replacement != null) {
                            currentSpec = currentSpec.buildUpon().setUri(replacement.uri).build();
                            segmentSequence = replacement.sequence;
                            Log.d(TAG, "Retrying sequence " + segmentSequence
                                    + " with refreshed segment URI: " + replacement.uri);
                        } else {
                            Log.d(TAG, "No usable replacement segment after sequence " + segmentSequence
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
