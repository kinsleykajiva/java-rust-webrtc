# Packaging & Distribution

Version **0.1.0** is available on **[Maven Central](https://central.sonatype.com/search?q=io.github.kinsleykajiva)**
under the group `io.github.kinsleykajiva`.

---

## Artifacts

The project publishes two artifacts:

| Artifact ID | Description | Platform? |
|-------------|-------------|-----------|
| `library` | Pure-Java FFM API (`io.github.kinsleykajiva.webrtc.*`) | Platform-neutral |
| `JavaRust-Webrtc-native` | Prebuilt Rust `cdylib` bundled at `/native/<libname>` | Per-platform (classifier) |

The native library is published as a set of **classified** jars — one per OS/arch. Pick the
classifier that matches your target platform:

| Classifier | Native binary | Platform |
|------------|---------------|----------|
| `osx-aarch_64` | `librust_webrtc_ffi.dylib` | macOS (Apple Silicon) |
| `windows-x86_64` | `rust_webrtc_ffi.dll` | Windows x86-64 |
| `linux-x86_64` | `librust_webrtc_ffi.so` | Linux x86-64 |

The Java runtime loader (`NativeLibraryLoader`) finds the right binary on the classpath at
`/native/<libname>` and extracts it to a temp directory before `System.load()`. You normally do
**not** need to set `-Dwebrtc.native.lib`; use that flag only to override with a hand-built
native binary while iterating on the Rust side.

---

## Maven

Add the API jar and the native jar for your platform. Using a property keeps the classifier in
one place:

```xml
<properties>
    <!-- Change to windows-x86_64 or linux-x86_64 to match your OS. -->
    <webrtc.native.classifier>osx-aarch_64</webrtc.native.classifier>
</properties>

<dependencies>
    <!-- Java WebRTC API (platform-neutral) -->
    <dependency>
        <groupId>io.github.kinsleykajiva</groupId>
        <artifactId>library</artifactId>
        <version>0.1.0</version>
    </dependency>

    <!-- Native Rust engine for your platform -->
    <dependency>
        <groupId>io.github.kinsleykajiva</groupId>
        <artifactId>JavaRust-Webrtc-native</artifactId>
        <version>0.1.0</version>
        <classifier>${webrtc.native.classifier}</classifier>
    </dependency>

    <!-- Logging facade (bring your own implementation) -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.13</version>
    </dependency>
</dependencies>
```

The library uses the Java Foreign Function & Memory API. Add the JVM flag when running your app:

```xml
<!-- maven-surefire-plugin / maven-failsafe-plugin -->
<argLine>--enable-native-access=ALL-UNNAMED</argLine>
```

Or on the command line:

```bash
java --enable-native-access=ALL-UNNAMED -jar app.jar
```

---

## Gradle (Groovy DSL)

```groovy
dependencies {
    // Java WebRTC API (platform-neutral)
    implementation 'io.github.kinsleykajiva:library:0.1.0'

    // Native Rust engine — swap the classifier for your OS:
    //   osx-aarch_64 | windows-x86_64 | linux-x86_64
    implementation 'io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:osx-aarch_64'

    // Logging facade (bring your own implementation)
    implementation 'org.slf4j:slf4j-api:2.0.13'
}

// Required: the FFM API needs explicit native-access permission.
application {
    applicationDefaultJvmArgs = ['--enable-native-access=ALL-UNNAMED']
}

// Also cover tasks that fork a JVM directly:
tasks.withType(JavaExec).configureEach {
    jvmArgs '--enable-native-access=ALL-UNNAMED'
}
```

---

## Gradle (Kotlin DSL)

```kotlin
dependencies {
    // Java WebRTC API (platform-neutral)
    implementation("io.github.kinsleykajiva:library:0.1.0")

    // Native Rust engine — swap the classifier for your OS:
    //   osx-aarch_64 | windows-x86_64 | linux-x86_64
    implementation("io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:osx-aarch_64")

    // Logging facade (bring your own implementation)
    implementation("org.slf4j:slf4j-api:2.0.13")
}

// Required: the FFM API needs explicit native-access permission.
application {
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Also cover tasks that fork a JVM directly:
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

---

## Selecting the right native classifier

| OS | Classifier |
|----|-----------|
| macOS (Apple Silicon / M-series) | `osx-aarch_64` |
| Windows 64-bit | `windows-x86_64` |
| Linux 64-bit | `linux-x86_64` |

To auto-detect the platform at Gradle build time:

```groovy
// Groovy DSL — auto-detect platform
def nativeClassifier = {
    def os = System.getProperty('os.name').toLowerCase()
    if (os.contains('mac'))   return 'osx-aarch_64'
    if (os.contains('win'))   return 'windows-x86_64'
    if (os.contains('linux')) return 'linux-x86_64'
    throw new GradleException("Unsupported platform: $os")
}()

dependencies {
    implementation "io.github.kinsleykajiva:library:0.1.0"
    implementation "io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:${nativeClassifier}"
}
```

---

## Building from source (local install)

You need Rust (stable ≥ 1.80), jextract 25, and Maven 3.8+.

```bash
# 1. Build the Rust cdylib.
cd rust-webrtc-ffi
cargo build --release
cd ..

# 2. Compile the Java library, generate FFM bindings via jextract, and install locally.
mvn clean install -Djextract.home=/path/to/jextract-25

# 3. Run any demo.
java --enable-native-access=ALL-UNNAMED \
     -cp "demo-code/target/classes:library/target/classes:..." \
     io.github.kinsleykajiva.RtpForwarderDemo
```

On macOS the dylib must carry an ad-hoc code signature before the JVM will load it. The `library`
module signs the cargo-built binary automatically, but if you manually rebuild it:

```bash
codesign --force --sign - native/osx-aarch_64/librust_webrtc_ffi.dylib
```

---

## Publishing a new release to Maven Central

Each platform's native classifier must be built on its own OS. The typical release flow:

```bash
# On macOS:
mvn -Prelease,platform-macos clean deploy -DskipTests

# On Windows:
mvn -Prelease,platform-windows clean deploy -DskipTests

# On Linux:
mvn -Prelease,platform-linux clean deploy -DskipTests

# Once all three platform jars plus the API jar are staged, release the bundle via
# the Sonatype Central Portal UI or the central-publishing-maven-plugin auto-publish.
```

The `native` module's `build-helper-maven-plugin:attach-artifact` attaches the classified jar
during `package`. The `central-publishing-maven-plugin` (version 0.8.0) uploads everything to
Sonatype Central Portal.

---

## Maven Central coordinates

```
io.github.kinsleykajiva:library:0.1.0
io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:<classifier>
```

---

## Project layout

```
native/                              # Committed prebuilt cdylibs, one subdir per platform
  osx-aarch_64/librust_webrtc_ffi.dylib
  windows-x86_64/rust_webrtc_ffi.dll
  linux-x86_64/librust_webrtc_ffi.so
native/pom.xml                       # Packages each binary as a classified Maven artifact
rust-webrtc-ffi/                     # Rust crate (cdylib + cbindgen C header)
library/                             # Pure-Java API (artifactId: library)
demo-code/                           # Runnable examples
```
