<img width="256" height="256" alt="icon_128x128@2x" src="https://github.com/user-attachments/assets/90133f83-b4f6-41c6-aab9-25d0859d2a47" />

## bitchat for Android

A decentralized peer-to-peer messaging app with dual transport architecture: local Bluetooth mesh networks for offline communication and internet-based Nostr protocol for global reach. No accounts, no phone numbers, no central servers.

This is the Android implementation of bitchat, fully protocol-compatible with the [iOS version](https://github.com/permissionlesstech/bitchat) for cross-platform mesh communication.

[bitchat.free](http://bitchat.free)

[GitHub Releases](https://github.com/permissionlesstech/bitchat-android/releases)

[<img alt="Get it on Google Play" height="60" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"/>](https://play.google.com/store/apps/details?id=com.bitchat.droid)

## See it in action

<table>
  <tr>
    <th>Offline mesh conversation</th>
    <th>Geohash globe picker</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/readme-mesh-chat.png" alt="Active four-peer Bitchat mesh conversation with an image, voice messages, and text messages" width="360"/></td>
    <td><img src="docs/screenshots/readme-geohash-globe.png" alt="Bitchat geohash location picker showing the whole Earth and geohash grid" width="360"/></td>
  </tr>
</table>

## License

This project is released into the public domain. See the [LICENSE](LICENSE.md) file for details.

## Features

- **Dual Transport Architecture**: Bluetooth LE mesh for offline messaging, Nostr relays for internet-based messaging
- **Internet P2P Direct Links**: Serverless direct connections over the internet between strangers — via QR code, share link, or geohash-channel probing (no accounts required)
- **Location-Based Channels**: Geographic chat rooms using geohash coordinates over Nostr relays
- **Intelligent Message Routing**: Automatically chooses the best transport, with queuing and retry when a peer is unreachable
- **End-to-End Encryption**: [Noise Protocol](https://noiseprotocol.org) (XX pattern, X25519 + ChaCha20-Poly1305) for private messages over the mesh and direct links
- **Decentralized Mesh Network**: Automatic peer discovery and multi-hop relay over Bluetooth LE (max 7 hops)
- **Wi-Fi Aware Transport**: Higher-bandwidth local mesh on supported devices
- **Channel Chats**: Topic-based group messaging with optional password protection (Argon2id + AES-256-GCM)
- **IRC-Style Commands**: Familiar `/join`, `/msg`, `/who` style interface
- **Tor Support**: Built-in Tor (Arti) for private internet connectivity
- **Emergency Wipe**: Triple-tap to instantly clear all data
- **Cross-Platform**: Binary protocol compatible with bitchat on iOS and macOS

## Technical Architecture

### Bluetooth Mesh Network (Offline)

- Direct peer-to-peer within Bluetooth range, multi-hop relay through nearby devices
- Noise Protocol sessions with forward secrecy; peer identities derived from static keys
- Compact binary packet format with fragmentation, TTL routing, and deduplication
- Adaptive duty cycling and connection limits for battery efficiency
- Foreground service keeps the mesh alive within Android background execution limits

### Nostr Protocol (Internet)

- Global reach via public relays, geohash-based location channels
- Private messages fall back to Nostr for mutual favorites when the mesh is unavailable
- Ephemeral keys per geohash area

### Internet P2P Direct Links (China-friendly)

A serverless direct-link channel (`internetp2p`) that connects two devices over the
internet with **no TURN, no relay, no signaling server**. Signaling reuses the
existing end-to-end encrypted Nostr DM stream; STUN is only an optional reflector.

- **Three no-favorite entry points**: QR code, share/copy link (`bitchat-p2p://`),
  and geohash-channel probing — any of them opens a direct chat with a stranger.
- **NAT detection first**: RFC 5780 probing classifies the local NAT (cone /
  symmetric / open) plus a port-allocation probe (`PortBehaviorProbe`) that tells
  predictable (sequential) from random symmetric NATs, then picks the right
  traversal strategy.
- **Multi-tier fallback (direct first)**:
  1. Tier 0 — LAN direct (same Wi-Fi, no traversal needed)
  2. Tier 1 — global IPv6 direct; if inbound TCP is blocked (common on cellular),
     Tier 1b retries with an IPv6 UDP punch
  3. Tier 2 — classic UDP hole punch; for predictable (sequential) symmetric NATs
     a port-prediction sweep (RFC 5128 N+1) widens the window
  4. Tier 3 — TCP Simultaneous Open; for random symmetric NATs (typical China
     Mobile CGNAT) a multi-port Birthday Attack (bind+connect the same shared
     port range, 4–8 ports with jitter)
  5. Nostr relays as the final fallback — no direct path is ever required
- **UDP → TCP upgrade**: carriers (China Mobile/Unicom) QoS-throttle UDP hard
  while leaving TCP mostly untouched, so after a successful UDP punch the link
  briefly re-establishes over TCP (fresh `[BP2P][nonce]` handshake re-authenticates);
  on failure the UDP link stays in use.
- **Security, fail-closed**: every link — UDP, TCP, or IPv6 — must complete a
  `[BP2P][nonce]` handshake (nonce delivered out-of-band over encrypted Nostr DM);
  handshake failures are closed immediately (DoS-safe). Identity stays
  unverified until the Noise session binds it, which the UI marks explicitly.

### Android Stack

- Kotlin, Jetpack Compose (Material 3), MVVM
- Coroutines and Flow for all networking and state
- Core components: `MeshForegroundService` (persistent connectivity), `BluetoothMeshService` / `WifiAwareMeshService` (transports), `UnifiedMeshService` (transport selection), `NoiseSessionManager` (encryption sessions), `MessageRouter` (mesh/P2P/Nostr routing with outbox retry), `InternetMeshTransport` + `NatTraversalEngine` (internet P2P direct links)

## Building

Requires Android Studio and the Android SDK (API 26+).

### One-shot build script (Windows)

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Builds `:app:assembleDebug`, copies the APKs into `release/` (lowercase), and
removes any stale uppercase `Release/` directory.

### Manual build

```bash
git clone https://github.com/permissionlesstech/bitchat-android.git
cd bitchat-android
./gradlew assembleDebug
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app requests Bluetooth, location (required for BLE scanning), and notification permissions at runtime.

Release APKs and the Android App Bundle can be rebuilt byte-for-byte in the
pinned Linux container. Maintainers should follow the
[Android release guide](docs/maintainer-release-guide.md). See
[Reproducible builds](docs/reproducible-builds.md) for the build trust model
and public GitHub/Google Play verification procedures.

## Testing

```bash
# Unit tests
./gradlew test

# Lint
./gradlew lint

# Instrumented tests (requires a device or emulator)
./gradlew connectedAndroidTest
```

Note that BLE mesh behavior is difficult to emulate; protocol and session logic is covered by unit tests, while radio-level behavior needs real devices.
