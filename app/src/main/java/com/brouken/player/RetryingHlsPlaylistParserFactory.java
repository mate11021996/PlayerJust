package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gives catch-up HLS a second playlist URL so Media3's built-in track fallback can
 * recover from a segment 404 by loading a fresh playlist snapshot.
 *
 * The alternate playlist URL differs only by a query parameter. Segment URLs remain
 * those supplied by the server. This is deliberately limited to catch-up sources.
 */
final class RetryingHlsPlaylistParserFactory implements HlsPlaylistParserFactory {
    private static final String FALLBACK_PARAMETER = "_jp_hls_fallback";

    private final HlsPlaylistParserFactory delegate = new DefaultHlsPlaylistParserFactory();

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        final ParsingLoadable.Parser<HlsPlaylist> parser = delegate.createPlaylistParser();
        return (uri, inputStream) -> {
            HlsPlaylist playlist = parser.parse(uri, inputStream);
            if (playlist instanceof HlsMediaPlaylist) {
                return createTwoVariantPlaylist((HlsMediaPlaylist) playlist);
            }
            if (playlist instanceof HlsMultivariantPlaylist) {
                return ensureFallbackVariant((HlsMultivariantPlaylist) playlist);
            }
            return playlist;
        };
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
            HlsMultivariantPlaylist multivariantPlaylist,
            @Nullable HlsMediaPlaylist previousMediaPlaylist) {
        // Child media playlists must remain media playlists; only the initial master
        // playlist is duplicated to provide an alternate track for Media3's fallback.
        return delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist);
    }

    private static HlsMultivariantPlaylist createTwoVariantPlaylist(HlsMediaPlaylist mediaPlaylist) {
        Uri originalUri = Uri.parse(mediaPlaylist.baseUri);
        Uri fallbackUri = withFallbackParameter(originalUri);
        List<HlsMultivariantPlaylist.Variant> variants = new ArrayList<>();
        variants.add(HlsMultivariantPlaylist.Variant.createMediaPlaylistVariantUrl(originalUri));
        variants.add(HlsMultivariantPlaylist.Variant.createMediaPlaylistVariantUrl(fallbackUri));
        return new HlsMultivariantPlaylist(
                mediaPlaylist.baseUri,
                Collections.emptyList(),
                variants,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null,
                mediaPlaylist.hasIndependentSegments,
                Collections.emptyMap(),
                Collections.emptyList());
    }

    private static HlsMultivariantPlaylist ensureFallbackVariant(
            HlsMultivariantPlaylist playlist) {
        if (playlist.variants.isEmpty() || playlist.variants.size() > 1) {
            return playlist;
        }
        HlsMultivariantPlaylist.Variant original = playlist.variants.get(0);
        Uri fallbackUri = withFallbackParameter(original.url);
        Format originalFormat = original.format;
        String originalId = originalFormat.id == null ? "jp" : originalFormat.id;
        Format fallbackFormat = originalFormat.buildUpon()
                .setId(originalId + "-jp-fallback")
                .build();
        List<HlsMultivariantPlaylist.Variant> variants = new ArrayList<>(playlist.variants);
        variants.add(new HlsMultivariantPlaylist.Variant(
                fallbackUri,
                fallbackFormat,
                original.videoGroupId,
                original.audioGroupId,
                original.subtitleGroupId,
                original.captionGroupId));
        return new HlsMultivariantPlaylist(
                playlist.baseUri,
                playlist.tags,
                variants,
                playlist.videos,
                playlist.audios,
                playlist.subtitles,
                playlist.closedCaptions,
                playlist.muxedAudioFormat,
                playlist.muxedCaptionFormats,
                playlist.hasIndependentSegments,
                playlist.variableDefinitions,
                playlist.sessionKeyDrmInitData);
    }

    private static Uri withFallbackParameter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(FALLBACK_PARAMETER, "1")
                .build();
    }
}
