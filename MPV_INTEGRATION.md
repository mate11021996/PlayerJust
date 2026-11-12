# Embedded MPV integration (test branch)

This branch adds a secondary in-process libmpv playback activity. The existing Media3/ExoPlayer implementation remains the primary player and is not replaced.

- MPV dependency: dev.jdtech.mpv:libmpv:1.0.0 (Maven Central)
- MPV activity: MpvPlaybackActivity
- Network source errors from Media3 try MPV once for HTTP, HTTPS, and RTSP streams.
- Basic Authorization derived from URL user-info is forwarded as an MPV HTTP header.
- Android 8.0+ is required by the libmpv AAR. The existing app minimum SDK remains unchanged; older devices retain the Media3 behavior.

This is an experimental integration branch. Validate native library packaging, build variants, and playback on physical devices before merging into the stable branch. The bundled libmpv/FFmpeg binaries carry GPL-3.0-or-later obligations; review and ship the corresponding license/source notices before distribution.
