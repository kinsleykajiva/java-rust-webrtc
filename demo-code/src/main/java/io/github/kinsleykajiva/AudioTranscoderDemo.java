package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.MimeTypes;
import io.github.kinsleykajiva.webrtc.OggReader;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.TrackRemote;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.io.FileInputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates audio transcoding between WebRTC peers.
 *
 * <p>This demo shows how to build an <b>audio transcoder bridge</b> that receives audio
 * in one codec and re-transmits it in another. The library supports 4 audio codecs:</p>
 *
 * <h2>Supported audio codecs</h2>
 * <table>
 *   <tr><th>Constant</th><th>MIME Type</th><th>Clock Rate</th><th>Bandwidth</th></tr>
 *   <tr><td>{@link MimeTypes#AUDIO_OPUS}</td><td>{@code audio/opus}</td><td>48 000 Hz</td><td>Wideband (speech + music)</td></tr>
 *   <tr><td>{@link MimeTypes#AUDIO_G722}</td><td>{@code audio/G722}</td><td>8 000 Hz</td><td>Wideband (telephony)</td></tr>
 *   <tr><td>{@link MimeTypes#AUDIO_PCMU}</td><td>{@code audio/PCMU}</td><td>8 000 Hz</td><td>Narrowband (G.711 mu-law)</td></tr>
 *   <tr><td>{@link MimeTypes#AUDIO_PCMA}</td><td>{@code audio/PCMA}</td><td>8 000 Hz</td><td>Narrowband (G.711 A-law)</td></tr>
 * </table>
 *
 * <h2>Transcoding matrix</h2>
 * <p>Any of the 4 codecs can be transcoded to any of the other 3, giving <b>12 possible
 * transcoding paths</b>:</p>
 * <pre>
 *   From \ To  │  Opus   │  G.722  │  PCMU   │  PCMA
 *   ───────────┼─────────┼─────────┼─────────┼────────
 *   Opus       │   —     │  Op→G7  │  Op→PM  │  Op→PA
 *   G.722      │  G7→Op  │   —     │  G7→PM  │  G7→PA
 *   PCMU       │  PM→Op  │  PM→G7  │   —     │  PM→PA
 *   PCMA       │  PA→Op  │  PA→G7  │  PA→PM  │   —
 * </pre>
 *
 * <h2>Architecture</h2>
 * <p>The transcoder sits between two WebRTC connections:</p>
 * <pre>
 *   [Source Peer] ──Opus RTP──▸ [Transcoder] ──G.722 RTP──▸ [Sink Peer]
 *                                  │
 *                                  ├─ receive RTP on TrackRemote
 *                                  ├─ decode to PCM (plug in your codec library)
 *                                  ├─ encode to target codec
 *                                  └─ send RTP on TrackLocal
 * </pre>
 *
 * <p>This demo runs a <b>codec-remapped relay</b> — it receives Opus RTP, rewrites the
 * payload type header to match the target codec, and forwards it. For true transcoding
 * (Opus→G.722), plug in a codec library at the marked integration point.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java AudioTranscoderDemo [target_mime_type]
 *     target_mime_type: one of "audio/opus", "audio/G722", "audio/PCMU", "audio/PCMA"
 *                       defaults to "audio/G722"
 * </pre>
 *
 * <h2>Demo content</h2>
 * <p>Uses {@code demo-content/output.ogg} (OGG/Opus). See {@link MimeTypes} for the full
 * list of supported content files and how to generate content for other codecs.</p>
 */
public class AudioTranscoderDemo {

    /**
     * Maps each audio MIME type to its default RTP payload type and clock rate.
     */
    private static final Map<String, CodecInfo> CODEC_REGISTRY = Map.of(
            MimeTypes.AUDIO_OPUS, new CodecInfo(111, 48_000, "Opus"),
            MimeTypes.AUDIO_G722, new CodecInfo(9, 8_000, "G.722"),
            MimeTypes.AUDIO_PCMU,  new CodecInfo(0, 8_000, "PCMU (G.711 mu)"),
            MimeTypes.AUDIO_PCMA,  new CodecInfo(8, 8_000, "PCMA (G.711 A)")
    );

    private record CodecInfo(int payloadType, int clockRate, String displayName) {}

    public static void main(String[] args) throws Exception {
        String targetMime = args.length > 0 ? args[0] : MimeTypes.AUDIO_G722;
        CodecInfo targetCodec = CODEC_REGISTRY.get(targetMime);
        if (targetCodec == null) {
            System.err.println("Unknown target codec: " + targetMime);
            System.err.println("Supported: " + CODEC_REGISTRY.keySet());
            return;
        }

        WebRtc.initialize();

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║          Audio Transcoder Bridge Demo               ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Print supported audio codecs ────────────────────────────────────────
        System.out.println("Supported audio codecs (" + CODEC_REGISTRY.size() + " total):");
        for (var entry : CODEC_REGISTRY.entrySet()) {
            CodecInfo info = entry.getValue();
            String marker = entry.getKey().equals(targetMime) ? " ◀ TARGET" : "";
            System.out.printf("  %-20s  PT=%-3d  %6d Hz  %s%s%n",
                    info.displayName(), info.payloadType(), info.clockRate(), entry.getKey(), marker);
        }
        System.out.println();

        // ── Transcoding matrix ──────────────────────────────────────────────────
        int paths = CODEC_REGISTRY.size() * (CODEC_REGISTRY.size() - 1);
        System.out.println("Transcoding paths (" + paths + " combinations):");
        for (var from : CODEC_REGISTRY.entrySet()) {
            for (var to : CODEC_REGISTRY.entrySet()) {
                if (!from.getKey().equals(to.getKey())) {
                    System.out.printf("  %s (%s) → %s (%s)%n",
                            from.getValue().displayName(), from.getKey(),
                            to.getValue().displayName(), to.getKey());
                }
            }
        }
        System.out.println();

        // ── State ───────────────────────────────────────────────────────────────
        final CountDownLatch sourceConnected = new CountDownLatch(1);
        final CountDownLatch sinkConnected = new CountDownLatch(1);
        final AtomicInteger sourcePacketsReceived = new AtomicInteger(0);
        final AtomicInteger sinkPacketsForwarded = new AtomicInteger(0);

        // Holder arrays for mutual references between peers.
        final PeerConnection[] transcoderARef = new PeerConnection[1];
        final PeerConnection[] transcoderBRef = new PeerConnection[1];
        final PeerConnection[] sinkPcRef = new PeerConnection[1];

        // ── Source peer (sends Opus audio to the transcoder) ────────────────────
        Configuration sourceCfg = Configuration.create();
        PeerConnection sourcePc = PeerConnection.create(sourceCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (transcoderARef[0] != null) {
                    transcoderARef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Source]    state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    sourceConnected.countDown();
                }
            }
        });

        // Create the source Opus track BEFORE the SDP exchange.
        int sourceSsrc = TrackLocal.randomSsrc();
        TrackLocal sourceTrack = TrackLocal.create(
                MediaKind.AUDIO,
                "source-stream",
                "opus-audio",
                "Opus Source",
                sourceSsrc,
                MimeTypes.AUDIO_OPUS,
                48_000,
                1,
                "");
        if (sourceTrack == null) {
            System.err.println("Failed to create source track");
            return;
        }
        sourcePc.addTrack(sourceTrack);
        System.out.println("[Source]    Created Opus track ssrc=" + sourceSsrc);

        // ── Transcoder peer A (receives Opus from source) ───────────────────────
        Configuration transcoderACfg = Configuration.create();
        PeerConnection transcoderA = PeerConnection.create(transcoderACfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                sourcePc.addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Xcoder-A] state: " + state);
            }

            @Override
            public void onTrack(int trackId, String label) {
                System.out.println("[Xcoder-A] received track: " + label + " (id=" + trackId + ")");
                TrackRemote track = TrackRemote.get(trackId);
                if (track != null) {
                    track.setRtpCallback((tid, payload, pt, seq, ts, ssrc) -> {
                        sourcePacketsReceived.incrementAndGet();
                        // ─── TRANSCODING INTEGRATION POINT ───────────────────
                        // In a real transcoder you would:
                        //   1. Decode the incoming Opus payload to raw PCM samples
                        //   2. Resample if needed (48 kHz → 8 kHz for G.722/PCMU/PCMA)
                        //   3. Encode PCM to the target codec
                        //   4. Send the encoded frame via sinkTrack.writeSample()
                        //
                        // For this demo we forward the raw RTP payload as a
                        // "codec-remapped" packet by writing it to the sink track
                        // with the target codec's payload type.
                        forwardToSink(sinkTrackSink, targetCodec.payloadType(), payload, seq, ts);
                    });
                }
            }
        });
        transcoderARef[0] = transcoderA;

        // ── Transcoder peer B (sends target codec to sink) ──────────────────────
        Configuration transcoderBCfg = Configuration.create();
        PeerConnection transcoderB = PeerConnection.create(transcoderBCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (sinkPcRef[0] != null) {
                    sinkPcRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Xcoder-B] state: " + state);
            }
        });

        // Create the outgoing RTP track with the TARGET codec.
        int sinkSsrc = TrackLocal.randomSsrc();
        TrackLocal sinkTrack = TrackLocal.createRtpTrack(
                MediaKind.AUDIO,
                "transcoder-stream",
                "transcoded-audio",
                "Transcoded " + targetCodec.displayName(),
                sinkSsrc,
                targetMime,
                targetCodec.clockRate());
        if (sinkTrack == null) {
            System.err.println("Failed to create sink RTP track");
            return;
        }
        transcoderB.addTrack(sinkTrack);
        transcoderBRef[0] = transcoderB;
        System.out.println("[Xcoder-B] Added " + targetCodec.displayName() + " RTP track ssrc=" + sinkSsrc);

        // Store the sink track reference for the forwarding callback.
        sinkTrackSink = sinkTrack;

        // ── Sink peer (receives transcoded audio) ──────────────────────────────
        Configuration sinkCfg = Configuration.create();
        PeerConnection sinkPc = PeerConnection.create(sinkCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                transcoderB.addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Sink]      state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    sinkConnected.countDown();
                }
            }

            @Override
            public void onTrack(int trackId, String label) {
                System.out.println("[Sink]      received track: " + label + " (id=" + trackId + ")");
                TrackRemote track = TrackRemote.get(trackId);
                if (track != null) {
                    track.setRtpCallback((tid, payload, pt, seq, ts, ssrc) -> {
                        int count = sinkPacketsForwarded.incrementAndGet();
                        if (count <= 5 || count % 50 == 0) {
                            System.out.printf("[Sink]      packet #%d  PT=%d  seq=%d  %d bytes%n",
                                    count, pt, seq, payload.length);
                        }
                    });
                }
            }
        });
        sinkPcRef[0] = sinkPc;

        // ── SDP exchange: Source ↔ Transcoder-A ────────────────────────────────
        SessionDescription sourceOffer = sourcePc.createOffer();
        sourcePc.setLocalDescription(sourceOffer);
        transcoderA.setRemoteDescription(sourceOffer);

        SessionDescription transcoderAAnswer = transcoderA.createAnswer();
        transcoderA.setLocalDescription(transcoderAAnswer);
        sourcePc.setRemoteDescription(transcoderAAnswer);
        System.out.println("[Signal]    Source ↔ Transcoder-A  SDP exchange complete");

        // ── SDP exchange: Transcoder-B ↔ Sink ──────────────────────────────────
        SessionDescription transcoderBOffer = transcoderB.createOffer();
        transcoderB.setLocalDescription(transcoderBOffer);
        sinkPc.setRemoteDescription(transcoderBOffer);

        SessionDescription sinkAnswer = sinkPc.createAnswer();
        sinkPc.setLocalDescription(sinkAnswer);
        transcoderB.setRemoteDescription(sinkAnswer);
        System.out.println("[Signal]    Transcoder-B ↔ Sink    SDP exchange complete");
        System.out.println();

        // ── Wait for both legs to connect ───────────────────────────────────────
        System.out.println("Waiting for connections...");
        sourceConnected.await(20, TimeUnit.SECONDS);
        sinkConnected.await(20, TimeUnit.SECONDS);
        System.out.println();

        // ── Stream audio from the demo-content OGG file ─────────────────────────
        String oggPath = "demo-content/output.ogg";
        System.out.println("[Source]    Streaming Opus from " + oggPath);

        Thread sender = new Thread(() -> {
            try (OggReader reader = new OggReader(new FileInputStream(oggPath))) {
                OggReader.OggHeader hdr = reader.header();
                System.out.printf("[Source]    OGG: %d ch, %d Hz, pre-skip=%d%n",
                        hdr.channels(), hdr.sampleRate(), hdr.preSkip());

                OggReader.OggPage page;
                int count = 0;
                while ((page = reader.nextPage()) != null) {
                    // Each OGG page is one Opus frame (~20ms at 48kHz).
                    // writeSample lets the native stack handle RTP packetization.
                    sourceTrack.writeSample(111, page.data(), 20);
                    count++;
                    if (count <= 3 || count % 100 == 0) {
                        System.out.printf("[Source]    sent frame #%d  %d bytes%n", count, page.data().length);
                    }
                    Thread.sleep(20);
                }
                System.out.println("[Source]    Finished streaming " + count + " frames");
            } catch (Exception e) {
                System.err.println("[Source]    Error: " + e.getMessage());
                e.printStackTrace();
            }
        }, "opus-source");
        sender.start();

        // ── Wait and report ─────────────────────────────────────────────────────
        sender.join(30_000);
        Thread.sleep(2000);

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║                   Results                          ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf("║  Source codec:     %-32s ║%n", MimeTypes.AUDIO_OPUS + " (Opus)");
        System.out.printf("║  Target codec:     %-32s ║%n", targetMime + " (" + targetCodec.displayName() + ")");
        System.out.printf("║  Packets received: %-32d ║%n", sourcePacketsReceived.get());
        System.out.printf("║  Packets forwarded:%-32d ║%n", sinkPacketsForwarded.get());
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  All 4 audio codecs supported:                      ║");
        System.out.println("║    audio/opus   (48 kHz, wideband)                  ║");
        System.out.println("║    audio/G722   ( 8 kHz, wideband telephony)        ║");
        System.out.println("║    audio/PCMU   ( 8 kHz, G.711 mu-law)             ║");
        System.out.println("║    audio/PCMA   ( 8 kHz, G.711 A-law)              ║");
        System.out.println("║                                                      ║");
        System.out.println("║  12 transcoding paths available (4×3).               ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        sourcePc.close();
        transcoderA.close();
        transcoderB.close();
        sinkPc.close();
        System.out.println("Done.");
    }

    /** Holder for the sink RTP track, accessible from the transcoder callback. */
    private static TrackLocal sinkTrackSink;

    /**
     * Bridges incoming RTP to the sink track. This is the core of the transcoder.
     *
     * <p>For this demo, the raw RTP payload is forwarded with the target codec's payload
     * type. For true transcoding, replace this with actual decode→resample→encode logic.</p>
     *
     * <p>Java codec libraries for true transcoding:</p>
     * <ul>
     *   <li><b>Opus decoding</b>: opus-java, jopus, or JNI wrapper to libopus</li>
     *   <li><b>G.722 encoding</b>: Apache Commons Codec, or JNI to libg722</li>
     *   <li><b>G.711 (PCMU/PCMA)</b>: Simple mu-law/A-law lookup tables (no library needed)</li>
     *   <li><b>Resampling</b>: javax.sound (Java Sound API) or a simple linear interpolator</li>
     * </ul>
     */
    private static void forwardToSink(TrackLocal track, int targetPt, byte[] payload, int seq, int ts) {
        if (track == null) {
            return;
        }
        byte[] rtpPacket = buildRtpPacket(targetPt, seq, ts, 0, payload);
        track.writeRtp(rtpPacket);
    }

    /**
     * Builds a minimal RTP packet from its components.
     * Layout: [V=2, P=0, X=0, CC=0, M=0, PT] [seq] [timestamp] [SSRC] [payload]
     */
    private static byte[] buildRtpPacket(int payloadType, int sequenceNumber, int timestamp, int ssrc, byte[] payload) {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (payloadType & 0x7F);
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
        System.arraycopy(payload, 0, packet, 12, payload.length);
        return packet;
    }
}
