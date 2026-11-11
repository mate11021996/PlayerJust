package com.brouken.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Turns an appendable HLS EVENT playlist into a refreshable playlist for Media3.
 *
 * Some catch-up servers return an EVENT playlist with ENDLIST even though the
 * same URL is extended with more segments later. Media3 correctly treats
 * ENDLIST as final, so it never polls that playlist again. For catch-up
 * playback only, remove that final marker from media-playlist responses.
 * Media3's normal HLS playlist tracker then polls the URL and updates its
 * timeline as the server appends segments. Segment requests remain untouched.
 */
final class GrowingHlsDataSource implements DataSource {
    private final DataSource upstream;
    private final boolean rewritePlaylists;
    private byte[] playlist;
    private int playlistPosition;

    GrowingHlsDataSource(DataSource upstream, boolean rewritePlaylists) {
        this.upstream = upstream;
        this.rewritePlaylists = rewritePlaylists;
    }

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;
        private final boolean rewritePlaylists;

        Factory(DataSource.Factory upstreamFactory, boolean rewritePlaylists) {
            this.upstreamFactory = upstreamFactory;
            this.rewritePlaylists = rewritePlaylists;
        }

        @Override
        public DataSource createDataSource() {
            return new GrowingHlsDataSource(upstreamFactory.createDataSource(), rewritePlaylists);
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        playlist = null;
        playlistPosition = 0;
        long upstreamLength = upstream.open(dataSpec);
        Uri uri = dataSpec.uri;
        String path = uri == null ? null : uri.getPath();
        boolean isPlaylist = path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");
        if (!rewritePlaylists || !isPlaylist) {
            return upstreamLength;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                upstreamLength > 0 && upstreamLength < 1024 * 1024 ? (int) upstreamLength : 8192);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = upstream.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            if (count > 0) output.write(buffer, 0, count);
        }

        String body = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (!body.startsWith("#EXTM3U") || !body.contains("#EXT-X-ENDLIST")) {
            playlist = output.toByteArray();
        } else {
            // Remove only the ENDLIST tag. Keep EVENT, media sequence, segment
            // URIs, durations and all authentication query parameters intact.
            String refreshed = body.replaceAll("(?m)^#EXT-X-ENDLIST[ \\t]*\\r?\\n?", "");
            playlist = refreshed.getBytes(StandardCharsets.UTF_8);
        }
        return playlist.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (playlist == null) {
            return upstream.read(buffer, offset, length);
        }
        if (playlistPosition >= playlist.length) {
            return C.RESULT_END_OF_INPUT;
        }
        int count = Math.min(length, playlist.length - playlistPosition);
        System.arraycopy(playlist, playlistPosition, buffer, offset, count);
        playlistPosition += count;
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
        playlist = null;
        playlistPosition = 0;
        upstream.close();
    }
}
