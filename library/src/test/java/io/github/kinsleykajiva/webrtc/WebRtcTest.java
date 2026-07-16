package io.github.kinsleykajiva.webrtc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebRtcTest {

    @Test
    void probeReturnsNativeStatus() {
        String status = WebRtc.probe();
        assertThat(status).isNotBlank();
    }
}
