package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.IceGatheringState;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Helper that wires two in-process peer connections over loopback so the data-channel
 * demos can run without a browser or a separate signaling server. It forwards ICE
 * candidates between the two peers and waits for both to reach {@code CONNECTED}.
 *
 * <p>Both peers use ICE-over-TCP on the loopback interface (the answerer listens on a
 * fixed TCP address and acts as DTLS client, the offerer uses an ephemeral TCP port).
 * This mirrors the configuration proven by {@code IceTcpActivePassiveDemo} so the demos
 * connect reliably without STUN/TURN.</p>
 */
public final class PeerPair implements AutoCloseable {

    public PeerConnection offerer;
    public PeerConnection answerer;

    private final CountDownLatch connected;

    private PeerPair() {
        this.connected = new CountDownLatch(2);
    }

    public static PeerPair prepare(PeerConnection.Observer offererObs,
                                   PeerConnection.Observer answererObs) {
        PeerPair pair = new PeerPair();
        BiConsumer<String, String> fwdToAnswerer = (c, m) -> {
            if (pair.answerer != null) pair.answerer.addIceCandidate(c, m, 0);
        };
        BiConsumer<String, String> fwdToOfferer = (c, m) -> {
            if (pair.offerer != null) pair.offerer.addIceCandidate(c, m, 0);
        };
        PeerConnection.Observer oWrap = new Wrap(offererObs, pair.connected, fwdToAnswerer);
        PeerConnection.Observer aWrap = new Wrap(answererObs, pair.connected, fwdToOfferer);

        Configuration answererCfg = Configuration.create()
                .useTcpOnly("127.0.0.1:8443", DtlsRole.CLIENT);
        Configuration offererCfg = Configuration.create()
                .setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value);

        pair.answerer = PeerConnection.create(answererCfg, aWrap);
        pair.offerer = PeerConnection.create(offererCfg, oWrap);
        return pair;
    }

    /** Performs the offer/answer exchange and blocks until both peers are CONNECTED. */
    public void negotiate() throws Exception {
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);
        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);
        if (!connected.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Peers did not connect within timeout");
        }
    }

    @Override
    public void close() {
        if (offerer != null) offerer.close();
        if (answerer != null) answerer.close();
    }

    private static final class Wrap implements PeerConnection.Observer {
        private final PeerConnection.Observer user;
        private final CountDownLatch latch;
        private final BiConsumer<String, String> iceForward;

        Wrap(PeerConnection.Observer user, CountDownLatch latch, BiConsumer<String, String> iceForward) {
            this.user = user;
            this.latch = latch;
            this.iceForward = iceForward;
        }

        @Override
        public void onIceCandidate(String candidate, String sdpMid) {
            iceForward.accept(candidate, sdpMid);
            user.onIceCandidate(candidate, sdpMid);
        }

        @Override
        public void onConnectionStateChange(PeerConnectionState state) {
            if (state == PeerConnectionState.CONNECTED) {
                latch.countDown();
            }
            user.onConnectionStateChange(state);
        }

        @Override
        public void onDataChannel(int id, String label) {
            user.onDataChannel(id, label);
        }

        @Override
        public void onIceGatheringStateChange(IceGatheringState state) {
            user.onIceGatheringStateChange(state);
        }

        @Override
        public void onTrack(int trackId, String label) {
            user.onTrack(trackId, label);
        }
    }
}
