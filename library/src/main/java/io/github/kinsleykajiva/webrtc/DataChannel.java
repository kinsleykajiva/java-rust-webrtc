package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Static helpers for registering data-channel callbacks via FFM upcalls. */
public final class DataChannel {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena ARENA = Arena.ofShared();

    /** Called when a message arrives. */
    public interface MessageCallback {
        void onMessage(int id, byte[] data);
    }

    /** Called on open/close; carries the channel id. */
    public interface StateCallback {
        void onState(int id);
    }

    static MemorySegment upcallMessage(MessageCallback cb) {
        try {
            // The callback's length parameter is a C uintptr_t, which jextract maps to
            // C_LONG. Its carrier is `int` on Windows (where C long is 32-bit) but `long`
            // on macOS/Linux (LP64). The upcall MethodHandle type must match exactly, so
            // pick the handler whose length parameter matches the platform's C_LONG.
            Class<?> lenType = webrtc_ffi_h.C_LONG.carrier();
            String handler = (lenType == long.class) ? "msgCbLong" : "msgCbInt";
            MethodHandle target = MethodHandles.lookup().findStatic(
                    DataChannel.class, handler,
                    MethodType.methodType(void.class, MessageCallback.class, short.class, MemorySegment.class, lenType));
            MethodHandle bound = MethodHandles.insertArguments(target, 0, cb);
            FunctionDescriptor desc = FunctionDescriptor.ofVoid(
                    webrtc_ffi_h.C_SHORT, webrtc_ffi_h.C_POINTER, webrtc_ffi_h.C_LONG);
            return LINKER.upcallStub(bound, desc, ARENA);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static MemorySegment upcallState(StateCallback cb) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(
                    DataChannel.class, "stateCb",
                    MethodType.methodType(void.class, StateCallback.class, short.class));
            MethodHandle bound = MethodHandles.insertArguments(target, 0, cb);
            FunctionDescriptor desc = FunctionDescriptor.ofVoid(webrtc_ffi_h.C_SHORT);
            return LINKER.upcallStub(bound, desc, ARENA);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void msgCbInt(MessageCallback cb, short id, MemorySegment data, int len) {
        byte[] bytes = new byte[len < 0 ? 0 : len];
        if (len > 0 && data != null && data.address() != 0) {
            MemorySegment.copy(data.reinterpret(len), java.lang.foreign.ValueLayout.JAVA_BYTE,
                    0, bytes, 0, len);
        }
        cb.onMessage(id, bytes);
    }

    private static void msgCbLong(MessageCallback cb, short id, MemorySegment data, long len) {
        int n = (int) len;
        byte[] bytes = new byte[n < 0 ? 0 : n];
        if (len > 0 && data != null && data.address() != 0) {
            MemorySegment.copy(data.reinterpret(len), java.lang.foreign.ValueLayout.JAVA_BYTE,
                    0, bytes, 0, n);
        }
        cb.onMessage(id, bytes);
    }

    private static void stateCb(StateCallback cb, short id) {
        cb.onState(id);
    }

    private DataChannel() {}
}
