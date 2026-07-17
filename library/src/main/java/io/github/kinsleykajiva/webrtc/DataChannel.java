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
final class DataChannel {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena ARENA = Arena.ofShared();

    /** Called when a message arrives; data is a pointer/length pair. */
    interface MessageCallback {
        void onMessage(int id, byte[] data);
    }

    /** Called on open/close; carries the channel id. */
    interface StateCallback {
        void onState(int id);
    }

    static MemorySegment upcallMessage(MessageCallback cb) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(
                    DataChannel.class, "msgCb",
                    MethodType.methodType(void.class, MemorySegment.class, short.class, MemorySegment.class, long.class));
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
                    MethodType.methodType(void.class, MemorySegment.class, short.class));
            MethodHandle bound = MethodHandles.insertArguments(target, 0, cb);
            FunctionDescriptor desc = FunctionDescriptor.ofVoid(webrtc_ffi_h.C_SHORT);
            return LINKER.upcallStub(bound, desc, ARENA);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void msgCb(MessageCallback cb, short id, MemorySegment data, long len) {
        byte[] bytes = new byte[(int) len];
        if (len > 0 && data != null && data.address() != 0) {
            MemorySegment.copy(data.reinterpret(len), java.lang.foreign.ValueLayout.JAVA_BYTE,
                    0, bytes, 0, (int) len);
        }
        cb.onMessage(id, bytes);
    }

    private static void stateCb(StateCallback cb, short id) {
        cb.onState(id);
    }

    private DataChannel() {}
}
