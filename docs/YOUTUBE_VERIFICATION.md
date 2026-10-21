# YouTube verification ? 2026-10-22

Run the backend with yt-dlp, ffmpeg, ffprobe and Node available, then `npm run test:youtube` from tools/web-storage-tests. This opt-in test calls the real service and consumes disk/network; it is outside the deterministic CI suite.

Confirmed Easy German 1 (_NXDM9XXhQ4): 48 cues, 156 seconds of MP3, 200 waveform bins with nonzero signal. Job polling reached READY. A Range bytes=0-255 request returned HTTP 206, audio MIME and exactly 256 bytes.

Real Chromium UI playback/offline persistence uses deterministic fixture audio in test:app. Physical Desktop/TUI playback is unverified because local Application Control blocks ffplay and VLC is absent.
