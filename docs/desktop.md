# Desktop Module

The `desktop` module provides JavaFX-based media device access and UI components for building WebRTC desktop applications. It complements the core `library` module without modifying it.

## Architecture

The desktop module is intentionally decoupled from the core WebRTC library. Server-side users never need to pull in JavaFX or device access dependencies.

```
demo-desktop  (application code)
      |
      v
desktop  (device access, capture, UI)
      |
      v
library  (pure WebRTC, FFI to Rust)
      |
      v
rust_webrtc_ffi.dll
```

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `library` | Core WebRTC API (TrackLocal, PeerConnection, etc.) |
| `javafx-controls` | UI controls, layout, scene graph |
| `javafx-media` | Media playback (receiving remote video) |
| `javafx-graphics` | Canvas for local video preview |
| `javafx-swing` | SwingFXUtils for BufferedImage <-> WritableImage conversion |
| `webcam-capture` (sarxos) | Camera device discovery and frame capture |

Audio capture uses `javax.sound.sampled.TargetDataLine` -- part of the JDK, no extra dependency needed.

## Device Enumeration

```java
import io.github.kinsleykajiva.webrtc.desktop.device.DeviceEnumerator;

// List all microphones
List<AudioDevice> mics = DeviceEnumerator.audioInputs();

// List all speakers
List<AudioDevice> speakers = DeviceEnumerator.audioOutputs();

// List all webcams
List<VideoDevice> cameras = DeviceEnumerator.videoDevices();

// Get defaults
AudioDevice mic = DeviceEnumerator.defaultAudioInput().orElseThrow();
VideoDevice cam = DeviceEnumerator.defaultVideoDevice().orElseThrow();
```

## Audio Capture

Captures PCM audio from the microphone and sends it to a WebRTC track.

```java
BitrateConfig config = BitrateConfig.defaults();
AudioDevice mic = DeviceEnumerator.defaultAudioInput().orElseThrow();

AudioCapture capture = new AudioCapture(mic, config);
capture.setOnAudioLevel(level -> {
    System.out.printf("Audio level: %.0f%%%n", level * 100);
});

Optional<TrackLocal> audioTrack = capture.start();
if (audioTrack.isPresent()) {
    int senderId = peerConnection.addTrack(audioTrack.get());
}

// Later...
capture.stop();
```

### How it works

1. Opens a `TargetDataLine` on the selected mixer
2. Reads PCM bytes in 20ms chunks (standard RTP frame duration)
3. Writes each chunk to a `TrackLocal` via `writeSample()`
4. Computes audio level for UI metering

### Audio format

Default: 48kHz, 16-bit, mono PCM. Configurable through `BitrateConfig`.

## Video Capture

Captures frames from the webcam and sends them to a WebRTC track.

```java
BitrateConfig config = BitrateConfig.defaults();
VideoDevice cam = DeviceEnumerator.defaultVideoDevice().orElseThrow();

VideoCapture capture = new VideoCapture(cam, config);
capture.setOnFrame(frame -> {
    // Update JavaFX Canvas on FX thread
    Platform.runLater(() -> {
        WritableImage img = SwingFXUtils.toFXImage(frame, null);
        canvas.getGraphicsContext2D().drawImage(img, 0, 0);
    });
});

Optional<TrackLocal> videoTrack = capture.start();
if (videoTrack.isPresent()) {
    int senderId = peerConnection.addTrack(videoTrack.get());
}

// Later...
capture.stop();
```

### How it works

1. Opens the webcam at the configured resolution
2. Reads `BufferedImage` frames at the target frame rate
3. Converts each frame to JPEG bytes
4. Writes to a `TrackLocal` via `writeSample()`
5. Passes the raw `BufferedImage` to the preview callback

### Video format

Default: 640x480 at 30fps, JPEG-compressed. Configurable through `BitrateConfig`.

## Bitrate Configuration

The `BitrateConfig` class controls audio and video encoding parameters.

```java
// Default: 64kbps audio, 1.5Mbps video at 640x480@30fps
BitrateConfig config = BitrateConfig.defaults();

// Low bandwidth: 32kbps audio, 300kbps video at 320x240@15fps
BitrateConfig config = BitrateConfig.lowBandwidth();

// High quality: 128kbps audio, 4Mbps video at 1280x720@30fps
BitrateConfig config = BitrateConfig.highQuality();

// Screen share: 64kbps audio, 2Mbps video at 1920x1080@15fps
BitrateConfig config = BitrateConfig.screenShare();
```

### Custom configuration

```java
BitrateConfig config = new BitrateConfig();
config.setAudioBitrate(96_000);
config.setAudioSampleRate(44100);
config.setAudioChannels(2);           // stereo
config.setVideoBitrate(2_500_000);
config.setVideoWidth(1280);
config.setVideoHeight(720);
config.setVideoFps(24);
```

### Parameter reference

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `audioBitrate` | 64000 | 8000-320000 | Target audio bitrate (bps) |
| `audioSampleRate` | 48000 | 8000-96000 | PCM sample rate (Hz) |
| `audioChannels` | 1 | 1-2 | Mono or stereo |
| `audioBitsPerSample` | 16 | 8-24 | Bits per sample |
| `videoBitrate` | 1500000 | 100000-10000000 | Target video bitrate (bps) |
| `videoWidth` | 640 | 160-3840 | Capture width (px) |
| `videoHeight` | 480 | 120-2160 | Capture height (px) |
| `videoFps` | 30 | 1-60 | Target frame rate |

## VideoCallWindow

A complete video call UI that combines all desktop features:

- Camera and microphone device selection
- Bitrate preset dropdown (Low Bandwidth / Default / High Quality / Screen Share)
- Local video preview (webcam feed on Canvas)
- Remote video display area
- Call / Hang Up buttons
- SDP signaling input
- Connection status display

```java
// Launch as JavaFX Application
public class MyApp extends Application {
    @Override
    public void start(Stage stage) {
        VideoCallWindow window = new VideoCallWindow();
        window.show();
    }
}
```

## Desktop Demos

### ListDevices

Diagnostic tool that lists all available devices:

```bash
java --enable-native-access=ALL-UNNAMED \
     -cp "..." io.github.kinsleykajiva.desktop.ListDevices
```

### MicrophoneCaptureDemo

Captures microphone audio and sends it over a local peer connection:

```bash
java --enable-native-access=ALL-UNNAMED \
     -cp "..." io.github.kinsleykajiva.desktop.MicrophoneCaptureDemo
```

### CameraCaptureDemo

Captures webcam video and sends it over a local peer connection:

```bash
java --enable-native-access=ALL-UNNAMED \
     -cp "..." io.github.kinsleykajiva.desktop.CameraCaptureDemo
```

### VideoCallApp

Full video call UI (requires JavaFX modules):

```bash
java --enable-native-access=ALL-UNNAMED \
     --module-path /path/to/javafx/lib \
     --add-modules javafx.controls,javafx.media,javafx.graphics,javafx.swing \
     -cp "..." io.github.kinsleykajiva.desktop.VideoCallApp
```

## Design Decisions

### Why javax.sound.sampled for audio?

JavaFX's `Media` API is playback-only -- it has no capture support. The Java Sound API (`javax.sound.sampled`) is built into the JDK, well-tested, and provides direct access to the microphone via `TargetDataLine`. No external dependency needed.

### Why webcam-capture for video?

JavaFX has no camera capture API at all. The `webcam-capture` library by sarxos is the most established Java webcam library, widely used, and actively maintained. It provides a clean `Webcam.getImage()` API that returns `BufferedImage`.

### Why decoupled from the core?

Server-side deployments (media servers, backend services) should not pull in JavaFX or desktop device libraries. The `library` module is self-contained. The `desktop` module is an optional add-on for desktop applications.

### Why JPEG for video frames?

JPEG is a practical choice for raw frame transport over RTP. It provides reasonable compression without requiring a full video codec encoder on the capture side. The receiver gets the JPEG bytes and can decode or display them directly. A future improvement would be to add VP8/H.264 encoding for proper WebRTC video codec support.
