# Packaging & Distribution

This page explains how the library and its native binaries are packaged, and how to consume them
from Maven and Gradle. The first publishable version is **0.1.0**. It is **not yet deployed** to
Maven Central — the coordinates and build below are what you will use once it is released.

## Artifacts

The project produces two related artifacts under the group `io.github.kinsleykajiva`:

| Artifact | Contents | Platform? |
|----------|----------|-----------|
| `JavaRust-Webrtc` | Pure-Java FFM bindings + API (`io.github.kinsleykajiva.webrtc.*`) | Platform-neutral |
| `JavaRust-Webrtc-native` | The prebuilt Rust `cdylib`, bundled at `/native/<libname>` | One jar **per platform** (classifier) |

The native library cannot be platform-neutral, so it is published as a set of **classified** jars.
Pick the classifier that matches your target OS/arch:

| Classifier | Native file | OS / arch |
|------------|-------------|-----------|
| `osx-aarch_64` | `librust_webrtc_ffi.dylib` | macOS (Apple Silicon) |
| `windows-x86_64` | `rust_webrtc_ffi.dll` | Windows (x86-64) |
| `linux-x86_64` | `librust_webrtc_ffi.so` | Linux (x86-64) |

The Java loader (`NativeLibraryLoader`) finds the library on the classpath at
`/native/<libname>` and extracts it to a temp file before `System.load()`. You normally do **not**
need to set `-Dwebrtc.native.lib`; do so only to override with a specific file (e.g. while
developing the Rust side).

## Maven

Add the platform-neutral API jar plus the native jar for your platform. Use a property so the
classifier matches the build OS:

```xml
<properties>
    <!-- Pick the classifier for your platform:
         osx-aarch_64 | windows-x86_64 | linux-x86_64 -->
    <webrtc.native.classifier>osx-aarch_64</webrtc.native.classifier>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.kinsleykajiva</groupId>
        <artifactId>JavaRust-Webrtc</artifactId>
        <version>0.1.0</version>
    </dependency>
    <dependency>
        <groupId>io.github.kinsleykajiva</groupId>
        <artifactId>JavaRust-Webrtc-native</artifactId>
        <version>0.1.0</version>
        <classifier>${webrtc.native.classifier}</classifier>
    </dependency>
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.13</version>
    </dependency>
</dependencies>
```

When running, enable FFM native access (required by the JDK's Foreign Function & Memory API):

```bash
java --enable-native-access=ALL-UNNAMED -cp "app.jar" com.example.App
```

## Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'io.github.kinsleykajiva:JavaRust-Webrtc:0.1.0'
    implementation 'io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:osx-aarch_64'
    implementation 'org.slf4j:slf4j-api:2.0.13'
}

application {
    applicationDefaultJvmArgs = ['--enable-native-access=ALL-UNNAMED']
}
```

## Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.kinsleykajiva:JavaRust-Webrtc:0.1.0")
    implementation("io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:osx-aarch_64")
    implementation("org.slf4j:slf4j-api:2.0.13")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

## Building it yourself (local install)

The reactor builds the Rust crate, generates the FFM bindings with jextract, compiles the Java API,
and packages the native binaries. From the repo root:

```bash
# Build the Rust cdylib, generate bindings, compile, and install everything locally.
mvn clean install -Djextract.home=/path/to/jextract-25

# Run a demo (the native jar is on the classpath transitively).
java --enable-native-access=ALL-UNNAMED \
     -cp "demo-code/target/classes:library/target/classes:\
$JAVA_HOME/lib/slf4j-api-2.0.13.jar:...logback..." \
     io.github.kinsleykajiva.RtpForwarderDemo
```

The native binaries live in `native/<platform>/` (committed). On macOS the dylib must be ad-hoc
signed before the JVM will load it — the `library` module signs the cargo-built dylib automatically,
and you should also sign the committed copy if you rebuild it:

```bash
codesign --force --sign - native/osx-aarch_64/librust_webrtc_ffi.dylib
```

To produce the Linux `.so` or Windows `.dll`, build the `rust-webrtc-ffi` crate on that OS/arch and
copy the output into the matching `native/<platform>/` directory before packaging there.

## Publishing to Maven Central (future)

This version is **not deployed**. When it is, publish each platform's native jar plus the
platform-neutral API jar into a single staged repository. Because the native classifier must be
built on its own OS, the usual flow is:

```bash
# On macOS:
mvn -Pplatform-macos deploy -DskipTests        # deploys ...-native:0.1.0:osx-aarch_64
# On Windows:
mvn -Pplatform-windows deploy -DskipTests      # deploys ...-native:0.1.0:windows-x86_64
# On Linux:
mvn -Pplatform-linux deploy -DskipTests        # deploys ...-native:0.1.0:linux-x86_64
# Once all classifiers are staged, close + release the staging repository.
```

The `native` module's `build-helper-maven-plugin:attach-artifact` execution attaches the
platform-classified jar during `package`, so each OS build contributes its own classifier to the
shared `0.1.0` staging repository. The API jar (`JavaRust-Webrtc`) is platform-neutral and can be
deployed from any OS.

Coordinates for Maven Central will be:

```
io.github.kinsleykajiva:JavaRust-Webrtc:0.1.0
io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:<classifier>
```

## Project layout

```
native/                        # committed prebuilt cdylibs, one dir per platform
  osx-aarch_64/librust_webrtc_ffi.dylib
  windows-x86_64/rust_webrtc_ffi.dll
  linux-x86_64/                # build the .so here before packaging on Linux
native/pom.xml                # packages each binary as a classified Maven artifact
rust-webrtc-ffi/              # the Rust crate (cdylib + cbindgen header)
library/                      # pure-Java API; consumes JavaRust-Webrtc-native via classifier
demo-code/                    # runnable examples
```
