package io.github.kinsleykajiva.server;

import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.TrackLocal;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Loops media received on a {@link io.github.kinsleykajiva.webrtc.TrackRemote}
 * back to the same peer connection via a {@link TrackLocal} (the "echo").
 *
 * <p>Important threading constraint: the RTP callback is invoked on the native
 * tokio runtime thread, and the FFI write path ({@code writeRtp}) internally
 * {@code block_on}s its own runtime. Calling {@code writeRtp} from the callback
 * thread would panic ("cannot start a runtime from within a runtime"). Therefore
 * the callback only builds the packet and enqueues it; a dedicated consumer
 * thread drains the queue and performs the actual {@code writeRtp}.</p>
 *
 * <p>The payload bytes are forwarded verbatim (no transcoding) so the echo track
 * must be created with the same codec the client is sending; the payload type is
 * stamped from the negotiated answer SDP.</p>
 */
public final class EchoForwarder {

    private static final Logger LOG = Logger.getLogger(EchoForwarder.class.getName());

    private record RtpJob(int kind, byte[] packet) {
    }

    private TrackLocal audioTrack;
    private TrackLocal videoTrack;
    private int audioPayloadType = 96;
    private int videoPayloadType = 96;
    private int audioSsrc;
    private int videoSsrc;

    private final AtomicLong audioPackets = new AtomicLong();
    private final AtomicLong videoPackets = new AtomicLong();

    private final BlockingQueue<RtpJob> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread consumer;

    public void start() {
        consumer = Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    RtpJob job = queue.take();
                    TrackLocal track = job.kind == MediaKind.AUDIO.value ? audioTrack : videoTrack;
                    if (track == null) continue;
                    try {
                        track.writeRtp(job.packet);
                        if (job.kind == MediaKind.AUDIO.value) {
                            long n = audioPackets.incrementAndGet();
                            if (n <= 5 || n % 200 == 0) {
                                LOG.info("echo audio pkt#" + n + " len=" + job.packet.length);
                            }
                        } else {
                            long n = videoPackets.incrementAndGet();
                            if (n <= 5 || n % 200 == 0) {
                                LOG.info("echo video pkt#" + n + " len=" + job.packet.length);
                            }
                        }
                    } catch (RuntimeException e) {
                        LOG.warning("echo writeRtp failed: " + e.getMessage());
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    public void stop() {
        running = false;
        if (consumer != null) consumer.interrupt();
    }

    public void setAudioTrack(TrackLocal track, int ssrc) {
        this.audioTrack = track;
        this.audioSsrc = ssrc;
    }

    public void setVideoTrack(TrackLocal track, int ssrc) {
        this.videoTrack = track;
        this.videoSsrc = ssrc;
    }

    public void setAudioPayloadType(int pt) {
        this.audioPayloadType = pt;
    }

    public void setVideoPayloadType(int pt) {
        this.videoPayloadType = pt;
    }

    public int audioPt() {
        return audioPayloadType;
    }

    public int videoPt() {
        return videoPayloadType;
    }

    public long audioPackets() {
        return audioPackets.get();
    }

    public long videoPackets() {
        return videoPackets.get();
    }

    /**
     * Called from a {@link io.github.kinsleykajiva.webrtc.TrackRemote} RTP callback.
     * Must NOT perform any FFI write here; it only builds and enqueues the packet.
     *
     * @param kind {@link MediaKind#AUDIO} or {@link MediaKind#VIDEO}
     */
    public void onRtp(int kind, byte[] payload, int sequenceNumber, int timestamp) {
        TrackLocal track = kind == MediaKind.AUDIO.value ? audioTrack : videoTrack;
        if (track == null || payload == null || payload.length == 0) {
            return;
        }
        int ssrc = kind == MediaKind.AUDIO.value ? audioSsrc : videoSsrc;
        int pt = kind == MediaKind.AUDIO.value ? audioPayloadType : videoPayloadType;
        byte[] packet = RtpPacket.build(ssrc, pt, sequenceNumber, timestamp, payload);
        queue.offer(new RtpJob(kind, packet));
    }
}
