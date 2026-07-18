# Play from Disk

This document covers reading media files from disk and streaming them over WebRTC.

## Overview

The library includes Java file readers for common media formats. These readers parse the container format and extract individual frames or packets, which you then write to a `TrackLocal` for transmission.

The supported formats:

| Format | Reader | Codec | Container |
|--------|--------|-------|-----------|
| H.264 Annex B | `H26xReader` | H.264 | Raw NAL units |
| H.265 Annex B | `H26xReader` | H.265 | Raw NAL units |
| IVF (VP8) | `IvfReader` | VP8 | IVF container |
| IVF (VP9) | `IvfReader` | VP9 | IVF container |
| OGG (Opus) | `OggReader` | Opus | OGG container |
| MPEG Audio | `Mp3Reader` | MP3 | MPEG frame stream |
| WAV (PCM) | `WavReader` | PCM | RIFF/WAVE |

## Generating Test Files

Test files in `demo-content/` are generated with ffmpeg:

```bash
# VP8 video from camera
ffmpeg -f v4l2 -i /dev/video0 -c:v libvpx -b:v 1M -g 30 \
       -f ivf demo-content/output_vp8.ivf

# VP9 video
ffmpeg -f v4l2 -i /dev/video0 -c:v libvpx-vp9 -b:v 1M -g 30 \
       -f ivf demo-content/output_vp9.ivf

# H.264 video
ffmpeg -f v4l2 -i /dev/video0 -c:v libx264 -profile:v baseline \
       -f h264 demo-content/output.264

# H.265 video
ffmpeg -f v4l2 -i /dev/video0 -c:v libx265 -tag:v hvc1 \
       -f hevc demo-content/output.265

# Opus audio
ffmpeg -f lavfi -i "sine=frequency=440:duration=10" \
       -c:a libopus -b:a 128k demo-content/output.ogg

# Playlist (multiple audio clips)
ffmpeg -i clip1.ogg -i clip2.ogg -filter_complex "[0:0][1:0]concat=n=2:v=0:a=1[out]" \
       -map "[out]" demo-content/playlist.ogg
```

## H26xReader

Reads H.264 or H.265 Annex B byte streams. Parses NAL unit boundaries.

```java
H26xReader reader = new H26xReader(new File("demo-content/output.264"));

H26xReader.NalUnit nal;
while ((nal = reader.nextNalUnit()) != null) {
    byte[] data = nal.data();
    // Write to TrackLocal
    track.writeSample(payloadType, data, 33);
}
reader.close();
```

NAL unit types relevant for streaming:
- **SPS/PPS** (H.264) or **VPS/SPS/PPS** (H.265): Decoder configuration, send before any frames.
- **IDR**: Keyframe, can be decoded independently.
- **Non-IDR**: Predicted frames, depend on previous frames.

## IvfReader

Reads IVF containers used for VP8 and VP9. Extracts individual frames.

```java
IvfReader reader = new IvfReader(new File("demo-content/output_vp8.ivf"));

IvfReader.Frame frame;
while ((frame = reader.nextFrame()) != null) {
    byte[] data = frame.data();
    track.writeSample(payloadType, data, 33);  // 33ms per frame (~30fps)
}
reader.close();
```

The reader handles IVF header parsing (codec signature, dimensions, frame count) and gives you raw frame payloads.

## OggReader

Reads OGG containers, typically containing Opus audio.

```java
OggReader reader = new OggReader(new File("demo-content/output.ogg"));

OggReader.Packet packet;
while ((packet = reader.nextPacket()) != null) {
    byte[] data = packet.data();
    track.writeSample(payloadType, data, 20);  // 20ms Opus frame
}
reader.close();
```

Opus frames are typically 20ms. The clock rate is 48000 Hz.

## Mp3Reader

Reads MPEG audio frames. Works with MP3 and MPEG Layer II audio.

```java
Mp3Reader reader = new Mp3Reader(new File("audio.mp3"));

Mp3Reader.Frame frame;
while ((frame = reader.nextFrame()) != null) {
    byte[] data = frame.data();
    track.writeSample(payloadType, data, 26);  // ~26ms per frame
}
reader.close();
```

## WavReader

Reads RIFF/WAVE files with PCM audio.

```java
WavReader reader = new WavReader(new File("audio.wav"));
WavReader.Header header = reader.header();

// header.sampleRate(), header.channels(), header.bitsPerSample()

WavReader.Frame frame;
while ((frame = reader.nextFrame()) != null) {
    byte[] data = frame.data();
    track.writeSample(payloadType, data, 20);
}
reader.close();
```

The reader parses the WAV header and gives you raw PCM frames.

## Play-from-Disk Demos

The `demo-code` module includes four play-from-disk examples:

### PlayFromDiskH26xDemo

Streams H.264 or H.265 from disk to a peer connection. Uses `H26xReader` to parse Annex B NAL units and sends them via a sample-based `TrackLocal`.

### PlayFromDiskVpxDemo

Streams VP8 or VP9 from disk. Uses `IvfReader` for IVF container parsing.

### PlayFromDiskRenegotiationDemo

Starts with one track, then renegotiates to add or remove tracks mid-session. Demonstrates SDP renegotiation without dropping the connection.

### PlayFromDiskPlaylistControlDemo

Switches between multiple media files (playlist style). Demonstrates track replacement and playlist control patterns.

### Running the demos

```bash
# Make sure demo-content/ files exist and DLL is copied
java --enable-native-access=ALL-UNNAMED \
     -cp "demo-code/target/classes;demo-code/target/dependency/*;." \
     io.github.kinsleykajiva.PlayFromDiskH26xDemo
```

## File Path Conventions

The `MimeTypes` class provides standard paths for demo content:

```java
MimeTypes.CONTENT_VP8    // "demo-content/output_vp8.ivf"
MimeTypes.CONTENT_VP9    // "demo-content/output_vp9.ivf"
MimeTypes.CONTENT_H264   // "demo-content/output.264"
MimeTypes.CONTENT_H265   // "demo-content/output.265"
MimeTypes.CONTENT_OPUS   // "demo-content/output.ogg"
MimeTypes.CONTENT_PLAYLIST // "demo-content/playlist.ogg"
```

These paths are relative to the working directory. When running from `demo-code/`, you may need to adjust or copy files.
