# Local playback fixture

`video-cache-smoke.mp4` is copied from this repository's own
`assets/novvin-promo/conectate-presenta-novvin.mp4` (994,355 bytes).
Its source generator is `assets/novvin-promo/render-novvin-promo.ps1`:
the project promotional artwork is drawn locally and exported to H.264 MP4.
The asset contains no account screenshots, chat history or external downloaded material.

SHA-256: `5f6de22545b66d00c4000c44c220cb94e907750cef0dca5f40dda7fda58c4057`.

No FFmpeg executable was present in the current workstation runtime. The existing
small project asset was reused instead of fetching a clip or a new binary. Its
container contains `avc1` / `avcC` sample configuration. This asset is bundled only
in the instrumentation APK, never the application APK.

`VideoCachePlaybackTest` copies these bytes to a unique `.media` file (the shape of
the chat offline cache), then uses the production `VideoCache` and `UbuntuMedia`
path with a real ExoPlayer and output surface. It requires an active CacheDataSource,
STATE_READY and the first decoded frame within 20 seconds; no network is needed.
