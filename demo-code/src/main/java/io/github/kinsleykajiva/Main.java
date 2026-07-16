package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.WebRtc;

public class Main {
	public static void main(String[] args) {
		System.out.println("Hello and welcome!");

		String status = WebRtc.probe();
		System.out.println("Native WebRTC bridge says: " + status);
	}
}
