package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.H26xReader;
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
 * Streams H.264 video and Opus audio from disk files to a remote peer via loopback.
 *
 * <p>If command-line arguments are provided the first argument is the H.264 Annex B
 * file and the second is the Ogg/Opus file. Without arguments the demo creates
 * tracks and performs the SDP exchange only (no file streaming).</p>
 */
public class PlayFromDiskH26xDemo {

    private static final int VIDEO_SSRC = 1000;
    private static final int AUDIO_SSRC = 2000;

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        final CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        final AtomicInteger videoPacketsReceived = new AtomicInteger();
        final AtomicInteger audioPacketsReceived = new AtomicInteger();

        // ---- Answerer: UDP, DTLS client ----
        Configuration answererCfg = Configuration.create()
                .setTransport("0.0.0.0:0", "", 0, 0);

        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (offererRef[0] != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onTrack(int trackId, String label) {
                System.out.println("[Answer] onTrack id=" + trackId + " label=" + label);
                TrackRemote track = TrackRemote.register(trackId);
                track.setRtpCallback((tId, payload, payloadType, seq, ts, ssrc) -> {
                    if (tId == trackId) {
                        if (track.getKind() == MediaKind.VIDEO.value) {
                            videoPacketsReceived.incrementAndGet();
                        } else {
                            audioPacketsReceived.incrementAndGet();
                        }
                    }
                });
            }
        });
        answererRef[0] = answerer;

        // ---- Offerer: UDP, default DTLS role ----
        Configuration offererCfg = Configuration.create()
                .setTransport("0.0.0.0:0", "", 0, 0);

        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (answererRef[0] != null) {
                    answererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }
        });
        offererRef[0] = offerer;

        // ---- Create tracks and add to offerer ----
        TrackLocal videoTrack = TrackLocal.create(
                MediaKind.VIDEO, "stream-video", "track-video", "video",
                VIDEO_SSRC, "video/H264", 90000, 0,
                "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f");
        int videoSenderId = offerer.addTrack(videoTrack);
        System.out.println("[Offer] video sender id=" + videoSenderId);

        TrackLocal audioTrack = TrackLocal.create(
                MediaKind.AUDIO, "stream-audio", "track-audio", "audio",
                AUDIO_SSRC, "audio/opus", 48000, 2, "");
        int audioSenderId = offerer.addTrack(audioTrack);
        System.out.println("[Offer] audio sender id=" + audioSenderId);

        // ---- SDP exchange ----
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);

        System.out.println("Signaling complete; connecting...");
        boolean ok = connected.await(20, TimeUnit.SECONDS);
        System.out.println("Connected: " + ok);

        if (!ok) {
            offerer.close();
            answerer.close();
            return;
        }

        Thread.sleep(1000);

        int videoPt = offerer.senderGetPayloadType(videoSenderId);
        int audioPt = offerer.senderGetPayloadType(audioSenderId);
        System.out.println("[Offer] video payload type=" + videoPt);
        System.out.println("[Offer] audio payload type=" + audioPt);

        String videoPath = args.length > 0 ? args[0] : null;
        String audioPath = args.length > 1 ? args[1] : null;

        if (videoPath == null) {
            System.out.println("No file arguments provided; skipping streaming.");
            System.out.println("Usage: PlayFromDiskH26xDemo <video.h264> <audio.ogg>");
        } else {
            System.out.println("[Offer] streaming video from " + videoPath);
            try (H26xReader reader = new H26xReader(new FileInputStream(videoPath), 1024 * 1024, false)) {
                H26xReader.H26xSample sample;
                while ((sample = reader.nextSample()) != null) {
                    videoTrack.writeSample(videoPt, sample.data(), 33);
                    if (sample.timed()) {
                        Thread.sleep(33);
                    }
                }
            }
            System.out.println("[Offer] video streaming complete");
        }

        if (audioPath == null) {
            System.out.println("No audio file provided; skipping audio streaming.");
        } else {
            System.out.println("[Offer] streaming audio from " + audioPath);
            try (OggReader reader = new OggReader(new FileInputStream(audioPath))) {
                OggReader.OggPage page;
                while ((page = reader.nextPage()) != null) {
                    audioTrack.writeSample(audioPt, page.data(), 20);
                    Thread.sleep(20);
                }
            }
            System.out.println("[Offer] audio streaming complete");
        }

        Thread.sleep(2000);

        System.out.println("\n=== Stats from OFFERER ===");
        StatsReport offererStats = offerer.getStats();
        for (var ir : offererStats.inboundRtpStreams()) {
            System.out.println("  InboundRTP: ssrc=" + ir.ssrc() + " kind=" + ir.kind()
                    + " bytes=" + ir.bytesReceived() + " packets=" + ir.packetsReceived()
                    + " lost=" + ir.packetsLost());
        }

        System.out.println("\n=== Stats from ANSWERER ===");
        StatsReport answererStats = answerer.getStats();
        for (var ir : answererStats.inboundRtpStreams()) {
            System.out.println("  InboundRTP: ssrc=" + ir.ssrc() + " kind=" + ir.kind()
                    + " bytes=" + ir.bytesReceived() + " packets=" + ir.packetsReceived()
                    + " lost=" + ir.packetsLost());
        }

        System.out.println("\n[Answer] video packets received: " + videoPacketsReceived.get());
        System.out.println("[Answer] audio packets received: " + audioPacketsReceived.get());

        offerer.close();
        answerer.close();
        System.out.println("\nDone.");
    }
}
