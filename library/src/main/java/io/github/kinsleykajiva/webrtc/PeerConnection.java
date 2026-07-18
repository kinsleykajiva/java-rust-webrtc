package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import io.github.kinsleykajiva.webrtc.internal.NativeLibraryLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A WebRTC peer connection.
 *
 * <p>Wraps the native {@code webrtc} PeerConnection. Events are delivered through the
 * {@link Observer} interface supplied at creation time. The native connection is closed
 * and freed when {@link #close()} is called.</p>
 */
public final class PeerConnection implements AutoCloseable {

    /** Receives asynchronous peer-connection events. */
    public interface Observer {
        /** A local ICE candidate is available and should be sent to the remote peer. */
        default void onIceCandidate(String candidate, String sdpMid) {}

        /** The connection state changed. */
        default void onConnectionStateChange(PeerConnectionState state) {}

        /** The remote peer created a data channel. */
        default void onDataChannel(int id, String label) {}

        /** ICE gathering state changed (0=new, 1=gathering, 2=complete). */
        default void onIceGatheringStateChange(int state) {}

        /** A remote track was received. */
        default void onTrack(int trackId, String label) {}
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena CALLBACK_ARENA = Arena.ofShared();

    private static final java.util.concurrent.ConcurrentHashMap<Long, Observer> OBSERVERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final MemorySegment handle;
    private final long observerId;
    private volatile boolean closed;

    private PeerConnection(MemorySegment handle, long observerId) {
        this.handle = handle;
        this.observerId = observerId;
    }

    MemorySegment handle() {
        return handle;
    }

    /**
     * Creates a peer connection from the given configuration and event observer.
     * The configuration is consumed and closed by this call.
     */
    public static PeerConnection create(Configuration config, Observer observer) {
        NativeLibraryLoader.load();
        Objects.requireNonNull(observer, "observer");
        MemorySegment cfgHandle = config.handle();

        long id = NEXT_ID.getAndIncrement();
        OBSERVERS.put(id, observer);
        MemorySegment userData = MemorySegment.ofAddress(id);

        MemorySegment onIce = UPCALL_ICE;
        MemorySegment onState = UPCALL_STATE;
        MemorySegment onDc = UPCALL_DC;
        MemorySegment onGathering = UPCALL_GATHERING;
        MemorySegment onTrack = UPCALL_TRACK;

        MemorySegment h = webrtc_ffi_h.webrtc_ffi_peer_create(cfgHandle, userData, onIce, onState, onDc, onGathering, onTrack);
        config.close();
        if (h == null || h.address() == 0) {
            OBSERVERS.remove(id);
            throw new IllegalStateException("Failed to create peer connection");
        }
        return new PeerConnection(h, id);
    }

    // ---- upcall stubs (shared, look up observer by user_data id) ------------

    private static final MemorySegment UPCALL_ICE = makeUpcall(
            "iceCb", MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.ofVoid(webrtc_ffi_h.C_POINTER, webrtc_ffi_h.C_POINTER, webrtc_ffi_h.C_POINTER));
    private static final MemorySegment UPCALL_STATE = makeUpcall(
            "stateCb", MethodType.methodType(void.class, MemorySegment.class, int.class),
            FunctionDescriptor.ofVoid(webrtc_ffi_h.C_POINTER, webrtc_ffi_h.C_INT));
    private static final MemorySegment UPCALL_DC = makeUpcall(
            "dcCb", MethodType.methodType(void.class, MemorySegment.class, short.class, MemorySegment.class),
            FunctionDescriptor.ofVoid(webrtc_ffi_h.C_POINTER, webrtc_ffi_h.C_SHORT, webrtc_ffi_h.C_POINTER));
    private static final MemorySegment UPCALL_GATHERING = makeUpcall(
            "gatheringCb", MethodType.methodType(void.class, MemorySegment.class, int.class),
            FunctionDescriptor.ofVoid(webrtc_ffi_h.C_POINTER, webrtc_ffi_h.C_INT));
    private static final MemorySegment UPCALL_TRACK = makeUpcall(
            "trackCb", MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class),
            FunctionDescriptor.ofVoid(webrtc_ffi_h.C_POINTER, webrtc_ffi_h.C_INT, webrtc_ffi_h.C_POINTER));

    private static MemorySegment makeUpcall(String name, MethodType type, FunctionDescriptor desc) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(PeerConnection.class, name, type);
            return LINKER.upcallStub(target, desc, CALLBACK_ARENA);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void iceCb(MemorySegment userData, MemorySegment candidatePtr, MemorySegment midPtr) {
        Observer obs = OBSERVERS.get(userData.address());
        if (obs != null) {
            obs.onIceCandidate(readStr(candidatePtr), readStr(midPtr));
        }
    }

    private static void stateCb(MemorySegment userData, int state) {
        Observer obs = OBSERVERS.get(userData.address());
        if (obs != null) {
            obs.onConnectionStateChange(PeerConnectionState.fromValue(state));
        }
    }

    private static void dcCb(MemorySegment userData, short id, MemorySegment labelPtr) {
        Observer obs = OBSERVERS.get(userData.address());
        if (obs != null) {
            obs.onDataChannel(id, readStr(labelPtr));
        }
    }

    private static void gatheringCb(MemorySegment userData, int state) {
        Observer obs = OBSERVERS.get(userData.address());
        if (obs != null) {
            obs.onIceGatheringStateChange(state);
        }
    }

    private static void trackCb(MemorySegment userData, int trackId, MemorySegment labelPtr) {
        Observer obs = OBSERVERS.get(userData.address());
        if (obs != null) {
            TrackRemote.register(trackId);
            obs.onTrack(trackId, readStr(labelPtr));
        }
    }

    private static String readStr(MemorySegment ptr) {
        if (ptr == null || ptr.address() == 0) {
            return "";
        }
        return ptr.reinterpret(Long.MAX_VALUE).getString(0L);
    }

    // ---- operations --------------------------------------------------------

    /** Creates an SDP offer. */
    public SessionDescription createOffer() {
        return createOffer(false);
    }

    /**
     * Creates an SDP offer.
     *
     * @param iceRestart when {@code true} generates fresh ICE credentials (ICE restart).
     */
    public SessionDescription createOffer(boolean iceRestart) {
        checkClosed();
        MemorySegment h = webrtc_ffi_h.webrtc_ffi_create_offer(handle, iceRestart ? 1 : 0);
        if (h == null || h.address() == 0) {
            throw new IllegalStateException("createOffer failed");
        }
        return new SessionDescription(h);
    }

    /** Creates an SDP answer. */
    public SessionDescription createAnswer() {
        checkClosed();
        MemorySegment h = webrtc_ffi_h.webrtc_ffi_create_answer(handle);
        if (h == null || h.address() == 0) {
            throw new IllegalStateException("createAnswer failed");
        }
        return new SessionDescription(h);
    }

    /** Sets the local description, consuming the handle. */
    public void setLocalDescription(SessionDescription desc) {
        checkClosed();
        int rc = webrtc_ffi_h.webrtc_ffi_set_local_description(handle, desc.handle());
        if (rc != 0) {
            throw new IllegalStateException("setLocalDescription failed: " + rc);
        }
    }

    /** Sets the remote description, consuming the handle. */
    public void setRemoteDescription(SessionDescription desc) {
        checkClosed();
        int rc = webrtc_ffi_h.webrtc_ffi_set_remote_description(handle, desc.handle());
        if (rc != 0) {
            throw new IllegalStateException("setRemoteDescription failed: " + rc);
        }
    }

    /** Adds an audio or video transceiver with the given direction. */
    public void addTransceiver(MediaKind kind, TransceiverDirection direction) {
        checkClosed();
        int rc = webrtc_ffi_h.webrtc_ffi_add_transceiver(handle, kind.value, direction.value);
        if (rc != 0) {
            throw new IllegalStateException("addTransceiver failed: " + rc);
        }
    }

    /** Adds a transceiver from media kind using the standard transceiver API. */
    public void addTransceiverFromKind(MediaKind kind, TransceiverDirection direction) {
        checkClosed();
        int rc = webrtc_ffi_h.webrtc_ffi_add_transceiver_from_kind(handle, kind.value, direction.value);
        if (rc != 0) {
            throw new IllegalStateException("addTransceiverFromKind failed: " + rc);
        }
    }

    /** Fetches stats for this peer connection. */
    public StatsReport getStats() {
        checkClosed();
        return StatsReport.fetch(this);
    }

    /** Adds a remote ICE candidate. */
    public void addIceCandidate(String candidate, String sdpMid, int sdpMlineIndex) {
        checkClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cand = arena.allocateFrom(candidate);
            MemorySegment mid = sdpMid == null ? MemorySegment.NULL : arena.allocateFrom(sdpMid);
            int rc = webrtc_ffi_h.webrtc_ffi_add_ice_candidate(handle, cand, mid, sdpMlineIndex);
            if (rc != 0) {
                throw new IllegalStateException("addIceCandidate failed: " + rc);
            }
        }
    }

    /** Creates a data channel and returns its id. */
    public int createDataChannel(String label, boolean ordered) {
        checkClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment lbl = arena.allocateFrom(label);
            int id = webrtc_ffi_h.webrtc_ffi_create_data_channel(handle, lbl, ordered);
            if (id < 0) {
                throw new IllegalStateException("createDataChannel failed: " + id);
            }
            return id;
        }
    }

    /** Registers message/open/close callbacks for a data channel. */
    public void setDataChannelCallbacks(
            int id,
            DataChannel.MessageCallback onMessage,
            DataChannel.StateCallback onOpen,
            DataChannel.StateCallback onClose) {
        checkClosed();
        MemorySegment onMsg = onMessage == null ? MemorySegment.NULL : DataChannel.upcallMessage(onMessage);
        MemorySegment onOp = onOpen == null ? MemorySegment.NULL : DataChannel.upcallState(onOpen);
        MemorySegment onCl = onClose == null ? MemorySegment.NULL : DataChannel.upcallState(onClose);
        webrtc_ffi_h.webrtc_ffi_data_channel_set_callbacks(handle, (short) id, onMsg, onOp, onCl);
    }

    /** Sends UTF-8 text on a data channel. */
    public void sendDataChannelText(int id, String text) {
        checkClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment t = arena.allocateFrom(text);
            int rc = webrtc_ffi_h.webrtc_ffi_data_channel_send_text(handle, (short) id, t);
            if (rc != 0) {
                throw new IllegalStateException("sendDataChannelText failed: " + rc);
            }
        }
    }

    /** Sends raw bytes on a data channel. */
    public void sendDataChannelBytes(int id, byte[] data) {
        checkClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, data);
            int rc = webrtc_ffi_h.webrtc_ffi_data_channel_send_bytes(handle, (short) id, seg, data.length);
            if (rc != 0) {
                throw new IllegalStateException("sendDataChannelBytes failed: " + rc);
            }
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("PeerConnection is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            webrtc_ffi_h.webrtc_ffi_peer_close(handle);
            OBSERVERS.remove(observerId);
            closed = true;
        }
    }
}
