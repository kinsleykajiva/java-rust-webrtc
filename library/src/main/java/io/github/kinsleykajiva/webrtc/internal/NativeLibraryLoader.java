package io.github.kinsleykajiva.webrtc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the embedded native WebRTC FFI shared library from the classpath into a
 * temporary file and links it into the current class loader.
 *
 * <p>The Rust {@code cdylib} is packaged under {@code /native/rust_webrtc_ffi.dll}
 * inside the library jar. Because {@link java.lang.System#loadLibrary(String)} relies
 * on {@code java.library.path} while the FFM bindings rely on the loader symbol
 * lookup, we extract the library to a temp file and load it explicitly with
 * {@link java.lang.System#load(String)}.</p>
 */
public final class NativeLibraryLoader {

    private static final String LIB_RESOURCE = "/native/rust_webrtc_ffi.dll";
    private static volatile boolean loaded;

    private NativeLibraryLoader() {}

    /** Extracts and loads the native library. Safe to call multiple times. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        String resource = LIB_RESOURCE;
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Native library resource not found on classpath: " + resource
                                + ". Ensure the rust-webrtc-ffi cdylib was built and embedded.");
            }
            Path tmp = Files.createTempFile("rust_webrtc_ffi", ".dll");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load native WebRTC library", e);
        }
    }
}
