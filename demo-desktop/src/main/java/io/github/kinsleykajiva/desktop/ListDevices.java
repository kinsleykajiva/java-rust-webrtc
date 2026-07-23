package io.github.kinsleykajiva.desktop;

import io.github.kinsleykajiva.webrtc.desktop.device.AudioDevice;
import io.github.kinsleykajiva.webrtc.desktop.device.DeviceEnumerator;
import io.github.kinsleykajiva.webrtc.desktop.device.VideoDevice;
import java.util.List;

/**
 * Lists all available audio and video devices on the system.
 *
 * <p>This is a simple diagnostic tool to verify that device enumeration works
 * before running the more complex demos.</p>
 *
 * <h2>Run</h2>
 * <pre>
 * java --enable-native-access=ALL-UNNAMED \
 *      -cp "..." io.github.kinsleykajiva.desktop.ListDevices
 * </pre>
 */
public class ListDevices {

    public static void main(String[] args) {
        System.out.println("=== Device Enumeration ===");
        System.out.println();

        // Audio inputs (microphones)
        List<AudioDevice> mics = DeviceEnumerator.audioInputs();
        System.out.println("Audio Input Devices (" + mics.size() + "):");
        if (mics.isEmpty()) {
            System.out.println("  (none found)");
        } else {
            for (int i = 0; i < mics.size(); i++) {
                System.out.println("  [" + i + "] " + mics.get(i));
            }
        }
        System.out.println();

        // Audio outputs (speakers)
        List<AudioDevice> speakers = DeviceEnumerator.audioOutputs();
        System.out.println("Audio Output Devices (" + speakers.size() + "):");
        if (speakers.isEmpty()) {
            System.out.println("  (none found)");
        } else {
            for (int i = 0; i < speakers.size(); i++) {
                System.out.println("  [" + i + "] " + speakers.get(i));
            }
        }
        System.out.println();

        // Video devices (webcams)
        List<VideoDevice> cameras = DeviceEnumerator.videoDevices();
        System.out.println("Video Devices (" + cameras.size() + "):");
        if (cameras.isEmpty()) {
            System.out.println("  (none found)");
        } else {
            for (int i = 0; i < cameras.size(); i++) {
                System.out.println("  [" + i + "] " + cameras.get(i));
            }
        }
        System.out.println();

        // Defaults
        System.out.println("Defaults:");
        DeviceEnumerator.defaultAudioInput()
            .ifPresentOrElse(d -> System.out.println("  Mic:    " + d),
                             () -> System.out.println("  Mic:    (none)"));
        DeviceEnumerator.defaultAudioOutput()
            .ifPresentOrElse(d -> System.out.println("  Speaker: " + d),
                             () -> System.out.println("  Speaker: (none)"));
        DeviceEnumerator.defaultVideoDevice()
            .ifPresentOrElse(d -> System.out.println("  Camera: " + d),
                             () -> System.out.println("  Camera: (none)"));
    }
}
