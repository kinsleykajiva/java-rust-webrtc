package io.github.kinsleykajiva.server;

import io.github.kinsleykajiva.server.protocol.ErrorDetail;
import io.github.kinsleykajiva.server.protocol.Signal;
import io.github.kinsleykajiva.server.protocol.SignalAction;
import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.SdpType;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.TrackRemote;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * One logical echo peer: a {@link PeerConnection} that receives the client's
 * audio/video and loops it straight back (RTP forwarded verbatim) so the client
 * sees/hears itself — a minimal SFU-style echo.
 */
public final class EchoSession {

    public interface SignalSender {
        void send(Signal signal);
    }

    private static final Logger LOG = Logger.getLogger(EchoSession.class.getName());

    private final String sessionId = UUID.randomUUID().toString();
    private final SignalSender sender;
    private final EchoForwarder forwarder = new EchoForwarder();

    private volatile PeerConnection pc;
    private volatile boolean remoteDescriptionSet = false;
    private final Object candidateLock = new Object();
    private final java.util.List<Candidate> pendingCandidates = new java.util.ArrayList<>();

    public EchoSession(SignalSender sender) {
        this.sender = sender;
        forwarder.start();
    }

    public String sessionId() {
        return sessionId;
    }

    public void handle(Signal req) {
        if (req.action == null) {
            replyError(req, ErrorDetail.CODE_BAD_REQUEST, "missing action");
            return;
        }
        switch (req.action) {
            case SignalAction.JOIN -> reply(SignalAction.ACK, req);
            case SignalAction.OFFER -> handleOffer(req);
            case SignalAction.CANDIDATE -> handleCandidate(req);
            case SignalAction.MEDIA_STATE -> {
                LOG.info("session " + sessionId + " media-state " + req.payload);
                reply(SignalAction.ACK, req);
            }
            case SignalAction.LEAVE -> {
                close();
                reply(SignalAction.BYE, req);
            }
            default -> replyError(req, ErrorDetail.CODE_BAD_REQUEST, "unknown action: " + req.action);
        }
    }

    private void handleOffer(Signal req) {
        String sdp = req.str("sdp");
        if (sdp == null || sdp.isBlank()) {
            replyError(req, ErrorDetail.CODE_BAD_REQUEST, "missing sdp in offer");
            return;
        }

        LOG.info("session " + sessionId + " OFFER SDP:\n" + sdp);

        int audioSsrc = TrackLocal.randomSsrc();
        int videoSsrc = TrackLocal.randomSsrc();

        // TCP loopback is the proven-working transport for this engine on
        // localhost (UDP does not form candidate pairs on loopback). The server
        // is the answerer, so it listens on 127.0.0.1 and acts as DTLS client.
        Configuration cfg = Configuration.create()
                .useTcpOnly("127.0.0.1:0", DtlsRole.CLIENT)
                .setCodecs("opus,vp8");
        pc = PeerConnection.create(cfg, observer());

        try (SessionDescription offer = SessionDescription.create(SdpType.OFFER, sdp)) {
            pc.setRemoteDescription(offer);
        }

        // The engine does not narrow its codec list, so the answer lists every
        // supported codec and the browser picks the FIRST one per m-line. This
        // server's media engine prefers PCMA over opus and H264 over VP8, so the
        // client actually sends PCMA/H264 -- not the offer's first codec. Build
        // the echo tracks from a probe answer so they use exactly the codec the
        // browser will send, then classify inbound RTP by that payload type.
        SessionDescription probe = pc.createAnswer();
        String probeSdp = probe.getSdp();
        probe.close();

        SdpCodecs.Codec sendAudio = SdpCodecs.firstMediaCodec(probeSdp, "audio");
        SdpCodecs.Codec sendVideo = SdpCodecs.firstMediaCodec(probeSdp, "video");
        if (sendAudio == null || sendVideo == null) {
            replyError(req, ErrorDetail.CODE_INTERNAL, "could not determine negotiated codec from answer");
            return;
        }

        TrackLocal audioTrack = TrackLocal.createRtpTrack(
                MediaKind.AUDIO, "echo", "audio", "audio", audioSsrc, sendAudio.mime(), sendAudio.clock());
        TrackLocal videoTrack = TrackLocal.createRtpTrack(
                MediaKind.VIDEO, "echo", "video", "video", videoSsrc, sendVideo.mime(), sendVideo.clock());
        if (audioTrack == null || videoTrack == null) {
            replyError(req, ErrorDetail.CODE_INTERNAL, "failed to create echo tracks");
            return;
        }
        forwarder.setAudioTrack(audioTrack, audioSsrc);
        forwarder.setVideoTrack(videoTrack, videoSsrc);
        forwarder.setAudioPayloadType(sendAudio.pt());
        forwarder.setVideoPayloadType(sendVideo.pt());

        // Remote description is now set: apply any candidates that arrived early.
        synchronized (candidateLock) {
            remoteDescriptionSet = true;
            for (Candidate c : pendingCandidates) {
                pc.addIceCandidate(c.candidate, c.sdpMid, c.mline);
            }
            pendingCandidates.clear();
        }

        pc.addTrack(audioTrack);
        pc.addTrack(videoTrack);

        SessionDescription answer = pc.createAnswer();
        String answerSdp = answer.getSdp();
        pc.setLocalDescription(answer);
        answer.close();

        LOG.info("session " + sessionId + " ANSWER SDP:\n" + answerSdp);
        LOG.info("session " + sessionId + " negotiated audio=" + sendAudio.mime()
                + " pt=" + sendAudio.pt() + " video=" + sendVideo.mime() + " pt=" + sendVideo.pt());
        replyWithSdp(SignalAction.ANSWER, req, answerSdp);
    }

    private void handleCandidate(Signal req) {
        String candidate = req.str("candidate");
        String sdpMid = req.str("sdpMid");
        Integer mline = req.integer("sdpMLineIndex");
        if (candidate == null) {
            reply(SignalAction.ACK, req);
            return;
        }
        Candidate c = new Candidate(candidate, sdpMid, mline == null ? -1 : mline);
        synchronized (candidateLock) {
            // The client may trickle candidates before the offer has been
            // processed (ICE gathering starts as soon as setLocalDescription
            // runs). Buffer them until the PeerConnection exists and the
            // remote description is applied, then they are flushed in
            // handleOffer.
            if (pc == null || !remoteDescriptionSet) {
                pendingCandidates.add(c);
                LOG.info("session " + sessionId + " buffered REMOTE ice-candidate (pre-offer): " + candidate);
                reply(SignalAction.ACK, req);
                return;
            }
        }
        LOG.info("session " + sessionId + " add REMOTE ice-candidate: " + candidate + " mid=" + sdpMid);
        pc.addIceCandidate(c.candidate, c.sdpMid, c.mline);
        reply(SignalAction.ACK, req);
    }

    private record Candidate(String candidate, String sdpMid, int mline) {
    }

    private PeerConnection.Observer observer() {
        return new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                LOG.info("session " + sessionId + " LOCAL ice-candidate: " + candidate + " mid=" + sdpMid);
                sender.send(Signal.event(SignalAction.CANDIDATE, sessionId)
                        .withPayload("candidate", candidate)
                        .withPayload("sdpMid", sdpMid));
            }

            @Override
            public void onTrack(int trackId, String label) {
                TrackRemote remote = TrackRemote.get(trackId);
                if (remote == null) return;
                LOG.info("session " + sessionId + " onTrack label=" + label);
                remote.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> {
                    // getKind()/getCodec() re-enter the FFI runtime (block_on) and
                    // would panic from this callback thread, so classify by the
                    // payload type negotiated in the answer instead.
                    int kind;
                    if (pt == forwarder.audioPt()) kind = MediaKind.AUDIO.value;
                    else if (pt == forwarder.videoPt()) kind = MediaKind.VIDEO.value;
                    else kind = 0;
                    if (forwarder.audioPackets() == 0 && forwarder.videoPackets() == 0) {
                        LOG.info("session " + sessionId + " first RTP cb: pt=" + pt
                                + " len=" + (payload == null ? 0 : payload.length)
                                + " matched=" + (kind != 0));
                    }
                    if (kind != 0) {
                        forwarder.onRtp(kind, payload, seq, ts);
                    }
                });
                remote.setOpenCallback((id, ssrc, rid) ->
                        LOG.info("session " + sessionId + " remote track open: " + label));
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                LOG.info("session " + sessionId + " connection state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    sender.send(Signal.event(SignalAction.PEER_CONNECTED, sessionId));
                } else if (state == PeerConnectionState.FAILED || state == PeerConnectionState.CLOSED) {
                    sender.send(Signal.event(SignalAction.PEER_DISCONNECTED, sessionId));
                }
            }
        };
    }

    public void close() {
        forwarder.stop();
        PeerConnection p = pc;
        pc = null;
        if (p != null) {
            try {
                p.close();
            } catch (RuntimeException e) {
                LOG.warning("close failed: " + e.getMessage());
            }
        }
    }

    private void reply(String action, Signal req) {
        sender.send(Signal.response(action, sessionId, req.transaction));
    }

    private void replyWithSdp(String action, Signal req, String sdp) {
        sender.send(Signal.response(action, sessionId, req.transaction).withPayload("sdp", sdp));
    }

    private void replyError(Signal req, int code, String message) {
        sender.send(Signal.response(SignalAction.ERROR, sessionId, req.transaction)
                .withError(code, message));
    }
}
