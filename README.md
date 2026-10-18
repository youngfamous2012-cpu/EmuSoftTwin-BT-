# EmuSoft Twin BT

Dual Bluetooth audio mirror for Android — plays any app's audio (VLC, Spotify, YouTube, etc.) simultaneously through two paired Bluetooth speakers.

---

## Requirements

| Item | Minimum |
|------|---------|
| Android version | 10 (API 29) |
| Bluetooth speakers | Any A2DP-compatible pair |
| Android Studio | Hedgehog (2023.1) or newer |
| Kotlin | 1.9+ |

---

## How it works

1. Uses **AudioPlaybackCapture** (Android 10+) to intercept the PCM audio output of other apps — VLC included.
2. Creates two **AudioTrack** instances, each pinned to one Bluetooth speaker via `setPreferredDevice(AudioDeviceInfo)`.
3. Writes the captured PCM stream to both tracks simultaneously from a coroutine loop.
4. Runs as a **foreground service** so it stays alive while you're in VLC.

---

## Quick Start

### 1. Open in Android Studio
```
File → Open → select the EmuSoftTwinBT folder
```

### 2. Let Gradle sync (requires internet for dependency download)

### 3. Pair both Bluetooth speakers to your phone via Android Settings → Bluetooth

### 4. Run on a physical device (emulators don't support BT audio)
```
Run → Run 'app'
```

### 5. In the app
- Tap **BT One → Select Speaker** → pick your first speaker
- Tap **BT Two → Select Speaker** → pick your second speaker
- Tap **Start Mirroring**
- Grant the "Screen Capture" permission (required for AudioPlaybackCapture)
- Open VLC and play any track — audio plays through both speakers

### 6. Sync adjustment
If one speaker sounds ahead of the other, drag the **Sync Offset** slider (0–200 ms) until they align. Start with small values (20–50 ms) and tune by ear.

---

## Project structure

```
app/src/main/java/com/emusoft/twinbt/
├── bluetooth/
│   └── BluetoothDeviceManager.kt   — paired device discovery & A2DP checks
├── service/
│   ├── AudioMirrorEngine.kt        — AudioPlaybackCapture + dual AudioTrack
│   └── TwinBTService.kt            — foreground service lifecycle
└── ui/
    └── MainActivity.kt             — speaker picker, mirror button, sync slider
```

---

## Known limitations

- **Some OEM devices** cap simultaneous A2DP connections at one. If your second speaker won't connect, this is a firmware limit — not a bug in the app.
- **Latency varies** by speaker brand and codec. The sync slider compensates but requires manual tuning.
- **VLC must not use exclusive audio mode** (it doesn't by default).
- The AudioPlaybackCapture API requires the capturing app to have `RECORD_AUDIO` permission — granted on first launch.

---

## Permissions requested

| Permission | Why |
|-----------|-----|
| `RECORD_AUDIO` | Required by AudioPlaybackCapture API |
| `BLUETOOTH_CONNECT` | Connect to paired speakers |
| `BLUETOOTH_SCAN` | Enumerate paired devices |
| `FOREGROUND_SERVICE` | Keep mirror alive in background |
| `POST_NOTIFICATIONS` | Show mirror status in notification bar |

---

*Built by EmuSoft — Twin BT v1.0.0*
