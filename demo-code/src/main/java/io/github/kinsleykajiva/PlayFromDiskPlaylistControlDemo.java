package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DataChannel;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.OggReader;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Demonstrates multi-track OGG audio streaming with data-channel playlist control.
 *
 * <p>The offerer loads one or more OGG/Opus files from disk (or synthesises
 * silent Opus frames when no files are available) and exposes them as audio
 * tracks. A "playlist" data channel accepts commands from the answerer:</p>
 * <ul>
 *     <li>{@code list}         – prints the current playlist</li>
 *     <li>{@code next}         – advances to the next track</li>
 *     <li>{@code prev}         – moves to the previous track</li>
 *     <li>{@code select <N>}   – switches to track at index N</li>
 *     <li>{@code pause}        – pauses audio output</li>
 *     <li>{@code resume}       – resumes audio output</li>
 * </ul>
 *
 * <p>Usage:
 * {@code java PlayFromDiskPlaylistControlDemo [file1.ogg] [file2.ogg] ...}</p>
 */
public class PlayFromDiskPlaylistControlDemo {

    private static final int OPUS_PAYLOAD_TYPE = 111;
    private static final int FRAME_DURATION_MS = 20;
    private static final int CLOCK_RATE = 48000;

    /**
     * Holds the state for a single audio track backed by an OGG file.
     */
    static final class TrackEntry {
        final String name;
        final Path filePath;
        final TrackLocal trackLocal;
        final OggReader.OggHeader header;

        TrackEntry(String name, Path filePath, TrackLocal trackLocal, OggReader.OggHeader header) {
            this.name = name;
            this.filePath = filePath;
            this.trackLocal = trackLocal;
            this.header = header;
        }

        @Override
        public String toString() {
            return name + " (" + header.channels() + "ch, " + header.sampleRate() + "Hz)";
        }
    }

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        final CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        // ---- offerer (streams audio, owns the data channel) ----
        Configuration offererCfg = Configuration.create()
                .setTransport("", "127.0.0.1:0", 0, NetworkType.UDP.value);

        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (answererRef[0] != null) {
                    answererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[offerer] state=" + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }
        });
        offererRef[0] = offerer;

        // ---- answerer (receives audio, sends playlist commands) ----
        Configuration answererCfg = Configuration.create()
                .setTransport("", "127.0.0.1:0", 0, NetworkType.UDP.value);

        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (offererRef[0] != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[answerer] state=" + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                System.out.println("[answerer] remote data channel id=" + id + " label=" + label);
                answererRef[0].setDataChannelCallbacks(id,
                        (dcId, data) -> System.out.println("[answerer] received: " + new String(data)),
                        dcId -> System.out.println("[answerer] data channel open"),
                        dcId -> System.out.println("[answerer] data channel closed"));
            }
        });
        answererRef[0] = answerer;

        // ---- create data channel on offerer ----
        int dcId = offerer.createDataChannel("playlist", true);
        offerer.setDataChannelCallbacks(dcId,
                (id, data) -> System.out.println("[offerer] received: " + new String(data)),
                id -> System.out.println("[offerer] data channel open"),
                id -> System.out.println("[offerer] data channel closed"));

        // ---- build track list from CLI args or synthetic tracks ----
        List<TrackEntry> tracks = new ArrayList<>();

        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Path oggPath = Path.of(args[i]);
                if (!Files.exists(oggPath)) {
                    System.out.println("[setup] skipping missing file: " + oggPath);
                    continue;
                }
                try {
                    OggReader reader = new OggReader(new FileInputStream(oggPath.toFile()));
                    OggReader.OggHeader hdr = reader.header();
                    TrackLocal tl = TrackLocal.create(
                            MediaKind.AUDIO,
                            "stream-" + i,
                            "track-" + i,
                            oggPath.getFileName().toString(),
                            0,
                            "audio/opus",
                            CLOCK_RATE,
                            hdr.channels(),
                            "");
                    if (tl == null) {
                        System.out.println("[setup] failed to create track for: " + oggPath);
                        reader.close();
                        continue;
                    }
                    tracks.add(new TrackEntry(oggPath.getFileName().toString(), oggPath, tl, hdr));
                    offerer.addTrack(tl);
                    reader.close();
                    System.out.println("[setup] loaded track " + i + ": " + oggPath.getFileName()
                            + " (" + hdr.channels() + "ch, " + hdr.sampleRate() + "Hz)");
                } catch (IOException e) {
                    System.out.println("[setup] error reading " + oggPath + ": " + e.getMessage());
                }
            }
        }

        if (tracks.isEmpty()) {
            System.out.println("[setup] no OGG files provided, creating 3 synthetic audio tracks");
            for (int i = 0; i < 3; i++) {
                OggReader.OggHeader synthHdr = new OggReader.OggHeader(1, 48000, 0);
                TrackLocal tl = TrackLocal.create(
                        MediaKind.AUDIO,
                        "synth-stream",
                        "synth-track-" + i,
                        "synthetic-" + i,
                        0,
                        "audio/opus",
                        CLOCK_RATE,
                        1,
                        "");
                tracks.add(new TrackEntry("synthetic-" + i, null, tl, synthHdr));
                offerer.addTrack(tl);
                System.out.println("[setup] created synthetic track " + i);
            }
        }

        System.out.println("[setup] " + tracks.size() + " track(s) ready");

        // ---- SDP exchange ----
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);

        System.out.println("Signaling complete; gathering UDP candidates and connecting...");
        boolean ok = connected.await(20, TimeUnit.SECONDS);
        System.out.println("Connected: " + ok);

        if (!ok) {
            offerer.close();
            answerer.close();
            System.out.println("Timed out, aborting.");
            return;
        }

        // ---- streaming state ----
        final AtomicInteger currentTrackIndex = new AtomicInteger(0);
        final AtomicBoolean paused = new AtomicBoolean(false);
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger packetsSent = new AtomicInteger(0);
        final AtomicInteger packetsReceived = new AtomicInteger(0);

        // answerer receives track events and counts packets
        // (we register on the answerer's remote track callbacks via the observer already set)

        // ---- command receiver on offerer side ----
        // Commands come from the answerer via the answerer creating a data channel,
        // but in this setup the offerer created the channel. The answerer's
        // onDataChannel callback already registered listeners. We instead use
        // a separate data channel created by the answerer for commands.
        // However, to keep it simple: the answerer will send commands on the
        // offerer-created channel, and the offerer receives them via its onMessage callback.
        // We re-register with command handling:
        offerer.setDataChannelCallbacks(dcId,
                (id, data) -> handleCommand(new String(data), tracks, currentTrackIndex, paused),
                id -> System.out.println("[offerer] data channel open"),
                id -> System.out.println("[offerer] data channel closed"));

        // ---- streaming thread: write OGG pages from the active track ----
        Thread streamingThread = new Thread(() -> {
            System.out.println("[stream] starting audio stream for track: " + tracks.get(0));
            try {
                while (running.get()) {
                    int idx = currentTrackIndex.get();
                    if (idx < 0 || idx >= tracks.size()) {
                        Thread.sleep(100);
                        continue;
                    }
                    TrackEntry entry = tracks.get(idx);
                    if (paused.get()) {
                        Thread.sleep(FRAME_DURATION_MS);
                        continue;
                    }
                    if (entry.filePath == null) {
                        // synthetic: send a minimal Opus silence frame
                        byte[] silence = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};
                        entry.trackLocal.writeSample(OPUS_PAYLOAD_TYPE, silence, FRAME_DURATION_MS);
                        packetsSent.incrementAndGet();
                    } else {
                        try (OggReader reader = new OggReader(new FileInputStream(entry.filePath.toFile()))) {
                            // skip header and tags pages
                            reader.header();
                            reader.nextPage(); // skip OpusTags
                            OggReader.OggPage page;
                            while (running.get() && !paused.get() && (page = reader.nextPage()) != null) {
                                if (currentTrackIndex.get() != idx) {
                                    break; // track switched, restart from new track
                                }
                                entry.trackLocal.writeSample(OPUS_PAYLOAD_TYPE, page.data(), FRAME_DURATION_MS);
                                packetsSent.incrementAndGet();
                                Thread.sleep(FRAME_DURATION_MS);
                            }
                        }
                        // loop the track
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.out.println("[stream] I/O error: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[stream] stopped. packets sent=" + packetsSent.get());
        }, "audio-stream");
        streamingThread.setDaemon(true);
        streamingThread.start();

        // ---- answerer sends commands after a short delay ----
        Thread.sleep(3000);
        System.out.println("\n--- sending: list ---");
        offerer.sendDataChannelText(dcId, "list");
        Thread.sleep(1000);

        System.out.println("--- sending: next ---");
        offerer.sendDataChannelText(dcId, "next");
        Thread.sleep(2000);

        System.out.println("--- sending: pause ---");
        offerer.sendDataChannelText(dcId, "pause");
        Thread.sleep(2000);

        System.out.println("--- sending: resume ---");
        offerer.sendDataChannelText(dcId, "resume");
        Thread.sleep(2000);

        System.out.println("--- sending: select 0 ---");
        offerer.sendDataChannelText(dcId, "select 0");
        Thread.sleep(2000);

        System.out.println("--- sending: prev ---");
        offerer.sendDataChannelText(dcId, "prev");
        Thread.sleep(1000);

        System.out.println("\n[summary] packets sent=" + packetsSent.get()
                + ", current track=" + currentTrackIndex.get()
                + " (" + tracks.get(currentTrackIndex.get()) + ")");

        // ---- shutdown ----
        running.set(false);
        streamingThread.interrupt();
        streamingThread.join(3000);

        offerer.close();
        answerer.close();
        System.out.println("Done.");
    }

    private static void handleCommand(
            String command,
            List<TrackEntry> tracks,
            AtomicInteger currentIndex,
            AtomicBoolean paused) {
        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "list": {
                System.out.println("[playlist] (" + tracks.size() + " tracks):");
                for (int i = 0; i < tracks.size(); i++) {
                    String marker = (i == currentIndex.get()) ? " <-- active" : "";
                    System.out.println("  [" + i + "] " + tracks.get(i) + marker);
                }
                break;
            }
            case "next": {
                int next = (currentIndex.get() + 1) % tracks.size();
                currentIndex.set(next);
                System.out.println("[playlist] next -> [" + next + "] " + tracks.get(next));
                break;
            }
            case "prev": {
                int prev = (currentIndex.get() - 1 + tracks.size()) % tracks.size();
                currentIndex.set(prev);
                System.out.println("[playlist] prev -> [" + prev + "] " + tracks.get(prev));
                break;
            }
            case "select": {
                if (parts.length < 2) {
                    System.out.println("[playlist] usage: select <index>");
                    break;
                }
                try {
                    int idx = Integer.parseInt(parts[1].trim());
                    if (idx < 0 || idx >= tracks.size()) {
                        System.out.println("[playlist] index out of range: " + idx
                                + " (valid: 0-" + (tracks.size() - 1) + ")");
                        break;
                    }
                    currentIndex.set(idx);
                    System.out.println("[playlist] select -> [" + idx + "] " + tracks.get(idx));
                } catch (NumberFormatException e) {
                    System.out.println("[playlist] invalid index: " + parts[1]);
                }
                break;
            }
            case "pause": {
                paused.set(true);
                System.out.println("[playlist] paused");
                break;
            }
            case "resume": {
                paused.set(false);
                System.out.println("[playlist] resumed");
                break;
            }
            default:
                System.out.println("[playlist] unknown command: " + command);
        }
    }
}
