package io.github.kinsleykajiva.webrtc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PeerConnectionTest {

    @Test
    void createsOfferWithTransceiversAndDataChannel() {
        Configuration config = Configuration.create();
        try (PeerConnection pc = PeerConnection.create(config, new PeerConnection.Observer() {})) {
            pc.addTransceiver(MediaKind.AUDIO, TransceiverDirection.RECV_ONLY);
            pc.addTransceiver(MediaKind.VIDEO, TransceiverDirection.SEND_RECV);

            int dcId = pc.createDataChannel("test", true);
            assertThat(dcId).isGreaterThanOrEqualTo(0);

            try (SessionDescription offer = pc.createOffer()) {
                assertThat(offer.getSdpType()).isEqualTo(SdpType.OFFER);
                String sdp = offer.getSdp();
                assertThat(sdp).contains("m=audio").contains("m=video");
                pc.setLocalDescription(offer);
            }
        }
    }

    @Test
    void roundTripsSessionDescription() {
        Configuration config = Configuration.create();
        try (PeerConnection pc = PeerConnection.create(config, new PeerConnection.Observer() {})) {
            try (SessionDescription offer = pc.createOffer()) {
                String sdp = offer.getSdp();
                try (SessionDescription parsed = SessionDescription.create(SdpType.OFFER, sdp)) {
                    assertThat(parsed.getSdpType()).isEqualTo(SdpType.OFFER);
                    assertThat(parsed.getSdp()).isEqualTo(sdp);
                }
            }
        }
    }
}
