# VLC Remote Modern

A small Android remote control for VLC's built-in HTTP interface.

This is a modernized/reimplemented derivative inspired by **VlcFreemote** by Nicolas Brailovsky:
https://github.com/nicolasbrailo/VlcFreemote

## Android baseline

- `compileSdk 36`
- `targetSdk 36`
- `minSdk 26`
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Java 17
- AndroidX AppCompat 1.7.1

The app intentionally asks only for `INTERNET`. It communicates directly with VLC over the local network. VLC's web interface commonly uses HTTP, so cleartext traffic is explicitly enabled for this LAN-oriented use case.

## VLC setup

1. In VLC, open **Tools > Preferences**.
2. Show **All** settings.
3. Open **Interface > Main interfaces** and enable **Web**.
4. In **Main interfaces > Lua**, set the VLC HTTP password.
5. Restart VLC.
6. In this app, enter the VLC host/IP, port (usually 8080), and password.

Do not expose VLC's HTTP interface directly to the public Internet. Use a trusted LAN or a VPN such as WireGuard.

## First alpha features

- HTTP Basic authentication compatible with VLC
- Status / now playing
- Play/pause, stop, previous, next
- Seek ±10 seconds and absolute seek slider
- Volume control
- Fullscreen toggle
- Playlist display and play-by-item
- Clear playlist
- Saved connection settings
- No analytics, advertising, trackers, camera, microphone, contacts, location, or storage permission

## Build

```bash
gradle :app:assembleDebug
```

The GitHub Actions workflow in `.github/workflows/android-vlc-remote.yml` builds an installable debug APK on the `vlc-remote-modern` branch.

## Signing

The first alpha is a debug-signed test APK. A persistent private release key should be generated and stored outside the repository before producing the stable release. That release key must be kept permanently if future APKs are to upgrade the installed app in place.

## License

GPL-3.0-or-later. See `LICENSE` and `NOTICE`.
