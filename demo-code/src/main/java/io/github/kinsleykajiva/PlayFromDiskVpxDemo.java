package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.IvfReader;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.OggReader;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.StatsReport;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.TrackRemote;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.io.FileInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates streaming VP8/VP9 video from IVF files and Opus audio from OGG files
 * to a remote peer via loopback.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code PlayFromDiskVpxDemo video.ivf audio.ogg} – stream from files</li>
 *   <li>{@code PlayFromDiskVpxDemo} – minimal test (no media files)</li>
 * </ul>
 */
public class PlayFromDiskVpxDemo {

    private static final AtomicInteger videoPacketsReceived = new AtomicInteger();
    private static final AtomicInteger audioPacketsReceived = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        String videoPath = args.length > 0 ? args[0] : null;
        String audioPath = args.length > 1 ? args[1] : null;

        final CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        Configuration answererCfg = Configuration.create()
                .setTransport("127.0.0.1:0", null, 0, 0);

        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (offererRef[0] != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Answerer] state=" + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onTrack(int trackId, String label) {
                System.out.println("[Answerer] onTrack id=" + trackId + " label=" + label);
                TrackRemote track = TrackRemote.get(trackId);
                track.setRtpCallback((tid, payload, payloadType, seq, ts, ssrc) -> {
                    if (label.contains("video")) {
                        videoPacketsReceived.incrementAndGet();
                    } else if (label.contains("audio")) {
                        audioPacketsReceived.incrementAndGet();
                    }
                });
            }
        });
        answererRef[0] = answerer;

        Configuration offererCfg = Configuration.create()
                .setTransport("127.0.0.1:0", null, 0, 0);

        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (answererRef[0] != null) {
                    answererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Offerer] state=" + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }
        });
        offererRef[0] = offerer;

        TrackLocal videoTrack = TrackLocal.create(
                MediaKind.VIDEO, "stream-video", "track-video", "video",
                0, "video/VP8", 90000, 0, "");

        TrackLocal audioTrack = TrackLocal.create(
                MediaKind.AUDIO, "stream-audio", "track-audio", "audio",
                0, "audio/opus", 48000, 2, "");

        offerer.addTrack(videoTrack);
        offerer.addTrack(audioTrack);

        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);

        System.out.println("Signaling complete; connecting...");
        boolean ok = connected.await(10, TimeUnit.SECONDS);
        System.out.println("Connected: " + ok);

        if (!ok) {
            offerer.close();
            answerer.close();
            return;
        }

        int[] senders = offerer.getSenders();
        int[] payloadTypes = new int[2]; // [0]=video, [1]=audio

        for (int senderId : senders) {
            String codec = offerer.senderGetCodec(senderId);
            int pt = offerer.senderGetPayloadType(senderId);
            if (codec.contains("VP8") || codec.contains("VP9")) {
                payloadTypes[0] = pt;
                System.out.println("[Offerer] video codec=" + codec + " pt=" + pt);
            } else if (codec.contains("opus")) {
                payloadTypes[1] = pt;
                System.out.println("[Offerer] audio codec=" + codec + " pt=" + pt);
            }
        }

        final int videoPayloadType = payloadTypes[0];
        final int audioPayloadType = payloadTypes[1];

        Thread videoThread = new Thread(() -> {
            if (videoPath == null) {
                System.out.println("[Offerer] No video file provided; skipping video stream.");
                return;
            }
            try (FileInputStream fis = new FileInputStream(videoPath);
                 IvfReader reader = new IvfReader(fis)) {
                IvfReader.IvfFileHeader header = reader.fileHeader();
                System.out.println("[Offerer] Video: " + header.width() + "x" + header.height()
                        + " frames=" + header.numFrames());

                long sleepTimeMs = (1000L * header.timebaseNumerator()) / header.timebaseDenominator();
                if (sleepTimeMs <= 0) {
                    sleepTimeMs = 33;
                }

                while (!Thread.currentThread().isInterrupted()) {
                    IvfReader.IvfFrame frame = reader.nextFrame();
                    if (frame == null) {
                        break;
                    }
                    videoTrack.writeSample(videoPayloadType, frame.data(), (int) sleepTimeMs);
                    Thread.sleep(sleepTimeMs);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.out.println("[Offerer] Video stream ended: " + e.getMessage());
                }
            }
        }, "video-stream");
        videoThread.setDaemon(true);

        Thread audioThread = new Thread(() -> {
            if (audioPath == null) {
                System.out.println("[Offerer] No audio file provided; skipping audio stream.");
                return;
            }
            try (FileInputStream fis = new FileInputStream(audioPath);
                 OggReader reader = new OggReader(fis)) {
                OggReader.OggHeader header = reader.header();
                System.out.println("[Offerer] Audio: opus " + header.sampleRate() + "Hz channels=" + header.channels());

                long frameDurationMs = 20;
                int samplesPerFrame = (int) (header.sampleRate() * frameDurationMs / 1000);

                OggReader.OggPage page;
                while (!Thread.currentThread().isInterrupted() && (page = reader.nextPage()) != null) {
                    audioTrack.writeSample(audioPayloadType, page.data(), (int) frameDurationMs);
                    Thread.sleep(frameDurationMs);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.out.println("[Offerer] Audio stream ended: " + e.getMessage());
                }
            }
        }, "audio-stream");
        audioThread.setDaemon(true);

        videoThread.start();
        audioThread.start();

        System.out.println("[Offerer] Streaming started. Waiting 5 seconds...");
        Thread.sleep(5000);

        videoThread.interrupt();
        audioThread.interrupt();

        System.out.println("\n=== Packet counts ===");
        System.out.println("Video packets received: " + videoPacketsReceived.get());
        System.out.println("Audio packets received: " + audioPacketsReceived.get());

        System.out.println("\n=== Stats from ANSWERER ===");
        StatsReport stats = answerer.getStats();
        for (var ir : stats.inboundRtpStreams()) {
            System.out.println("  InboundRTP: ssrc=" + ir.ssrc() + " kind=" + ir.kind()
                    + " bytes=" + ir.bytesReceived() + " packets=" + ir.packetsReceived()
                    + " lost=" + ir.packetsLost());
        }

        offerer.close();
        answerer.close();
        System.out.println("\nDone.");
    }
}
