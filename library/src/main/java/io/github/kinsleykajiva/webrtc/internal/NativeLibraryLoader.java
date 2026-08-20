package io.github.kinsleykajiva.webrtc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads the embedded native WebRTC FFI shared library for the current platform
 * and links it into the process via {@link System#load(String)}.
 *
 * <p>The Rust {@code cdylib} is packaged under {@code /native/<libname>} inside
 * the library jar. The library name is chosen per platform:</p>
 * <ul>
 *   <li>Windows: {@code rust_webrtc_ffi.dll}</li>
 *   <li>macOS:   {@code librust_webrtc_ffi.dylib}</li>
 *   <li>Linux:   {@code librust_webrtc_ffi.so}</li>
 * </ul>
 *
 * <p>Loading is idempotent and safe to call from many virtual threads
 * concurrently. The heavy extract-and-load work runs on a single virtual-thread
 * task; concurrent callers await the same {@link Future} instead of pinning
 * carrier threads behind a {@code synchronized} block (which would pin the
 * underlying OS thread under Project Loom).</p>
 *
 * <p>An explicit library path can be supplied via the {@code webrtc.native.lib}
 * system property (useful when developing the Rust side or when the jar is not
 * packaged with native libraries for the current platform).</p>
 */
public final class NativeLibraryLoader {

    private enum Platform { WINDOWS, MACOS, LINUX, UNKNOWN }

    private static final Map<Platform, String> LIB_NAMES = Map.of(
            Platform.WINDOWS, "rust_webrtc_ffi.dll",
            Platform.MACOS, "librust_webrtc_ffi.dylib",
            Platform.LINUX, "librust_webrtc_ffi.so");

    private static final Map<Platform, String> TEMP_SUFFIXES = Map.of(
            Platform.WINDOWS, ".dll",
            Platform.MACOS, ".dylib",
            Platform.LINUX, ".so");

    private static final Platform PLATFORM = detect();
    private static final String LIB_NAME = LIB_NAMES.getOrDefault(PLATFORM, "rust_webrtc_ffi.dll");
    private static final String TEMP_SUFFIX = TEMP_SUFFIXES.getOrDefault(PLATFORM, ".dll");

    private static final AtomicReference<Future<Void>> LOAD_FUTURE = new AtomicReference<>();
    private static final ExecutorService LOADER = Executors.newVirtualThreadPerTaskExecutor();

    private NativeLibraryLoader() {}

    private static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return Platform.WINDOWS;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Platform.MACOS;
        }
        if (os.contains("nux") || os.contains("nix")) {
            return Platform.LINUX;
        }
        return Platform.UNKNOWN;
    }

    /** Extracts and loads the native library. Safe to call many times (concurrently). */
    public static void load() {
        Future<Void> f = LOAD_FUTURE.get();
        if (f == null) {
            Future<Void> candidate = LOADER.submit(() -> {
                doLoad();
                return null;
            });
            Future<Void> existing = LOAD_FUTURE.compareAndExchange(null, candidate);
            f = (existing == null) ? candidate : existing;
        }
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading native WebRTC library", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to load native WebRTC library", e.getCause());
        }
    }

    private static void doLoad() {
        String override = System.getProperty("webrtc.native.lib");
        if (override != null && !override.isBlank()) {
            System.load(Path.of(override).toAbsolutePath().toString());
            return;
        }

        // 1) Jar/classpath-embedded copy under /native/<name>.
        String resource = "/native/" + LIB_NAME;
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (in != null) {
                Path tmp = Files.createTempFile("rust_webrtc_ffi", TEMP_SUFFIX);
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                System.load(tmp.toAbsolutePath().toString());
                return;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load native WebRTC library", e);
        }

        // 2) Filesystem search. Covers runs launched from an IDE whose "Make" step
        //    compiles the Java classes but does not run the Maven resource-copy that
        //    embeds the cdylib: the artifact already exists in the cargo target dir
        //    (or in library/target/classes/native from a prior full build).
        Path onDisk = findOnDisk();
        if (onDisk != null) {
            System.load(onDisk.toAbsolutePath().toString());
            return;
        }

        throw new IllegalStateException(
                "Native library not found: " + LIB_NAME + " (platform=" + PLATFORM + "). Build the"
                        + " rust-webrtc-ffi cdylib with `cargo build --release`, ensure it is embedded"
                        + " under /native, or set -Dwebrtc.native.lib=<absolute-path> to the file.");
    }

    /**
     * Searches a few well-known locations for the native artifact, walking up from
     * the working directory so the lookup works regardless of which module directory
     * the JVM was launched from.
     */
    private static Path findOnDisk() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i <= 5; i++) {
            Path base = cwd;
            for (int j = 0; j < i; j++) {
                base = base.getParent();
            }
            if (base == null) {
                break;
            }
            Path cargo = base.resolve("rust-webrtc-ffi").resolve("target").resolve("release").resolve(LIB_NAME);
            if (Files.isRegularFile(cargo)) {
                return cargo;
            }
            Path embedded = base.resolve("library").resolve("target").resolve("classes").resolve("native").resolve(LIB_NAME);
            if (Files.isRegularFile(embedded)) {
                return embedded;
            }
        }
        return null;
    }
}
