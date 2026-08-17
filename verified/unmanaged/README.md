# Native Z3 Solver for Stainless (Ring 6)

This directory contains the pre-built ScalaZ3 jar (`scalaz3_3-4.13.4.jar`) that
enables the **native Z3 interface** in Stainless's Inox solver. Without it,
Stainless falls back to `smt-z3` (Z3 via SMT-LIB subprocess), which is ~2x
slower and may time out on harder verification conditions.

## Performance Comparison

| Solver      | 111 VCs total time | Notes                          |
|-------------|--------------------|--------------------------------|
| `smt-z3`    | ~2.5s              | Z3 via SMT-LIB text protocol   |
| `nativez3`  | ~1.2s              | Z3 via JNI (this jar)          |

## Prerequisites

| Tool      | Version           | Notes                                    |
|-----------|-------------------|------------------------------------------|
| JDK       | 17+ (tested 26)   | `java -version`                          |
| sbt       | 1.12.12+          | Build tool for adk4s                     |
| gcc       | 11+               | Only needed to **rebuild** the jar       |
| Z3 binary | 4.13.4            | Only needed to **rebuild** the jar       |
| OS        | Linux x86_64      | glibc 2.35+ (Ubuntu 22.04+)              |

The pre-built jar in this directory (`scalaz3_3-4.13.4.jar`) bundles:
- `lib-bin/libz3.so` — Z3 native library
- `lib-bin/libz3java.so` — Z3 Java JNI bindings
- `lib-bin/libscalaz3.so` — ScalaZ3 C wrapper (compiled with gcc)
- `com/microsoft/z3/*.class` — Java Z3 bindings (from `com.microsoft.z3.jar`)
- `z3/*.class` — ScalaZ3 wrapper classes (Scala 3.7.2)

## Running Ring 6 (Verification)

```bash
sbt -J-Xmx6g ring6
```

The `ring6` alias:
1. Sets `stainlessEnabled := true` on the `verified` project
2. The `mergeScalaZ3Plugin` task runs automatically (see below)
3. Compiles the verified module with the Stainless plugin

You should see output like:

```
[info] Merging ScalaZ3 into Stainless plugin jar: ...-merged.jar  (first run only)
...
[info] ║ total: 111  valid: 102  (73 from cache, 29 trivial) invalid: 9  unknown: 0  time: 1.24 ║
[info]   nativez3, non-batched
```

If you see `smt-z3` instead of `nativez3`, or the warning "The Z3 native
interface is not available. Falling back onto smt-z3.", the ScalaZ3 jar is
missing or the merge failed. See [Troubleshooting](#troubleshooting) below.

## How It Works

### The Classloader Problem

Stainless 0.9.9.3 uses the ScalaZ3 wrapper (`z3.Z3Wrapper`) to access Z3 via
JNI. The Inox solver's `SolverFactory.hasNativeZ3` method calls
`z3.Z3Wrapper.withinJar()` to check if the native interface is available.

The problem: the Stainless compiler plugin runs in its own classloader that
**only searches the plugin jar** — not the project classpath, not
`scalaInstance`, not `-Xplugin:`-listed jars. So even if `scalaz3_3-4.13.4.jar`
is on the compile classpath, the plugin can't see `z3.Z3Wrapper`.

### The Solution: Jar Merging

The `mergeScalaZ3Plugin` task in `build.sbt` merges the ScalaZ3 jar into the
Stainless plugin jar **before** compilation:

1. Extract the Stainless plugin jar (`stainless-dotty-plugin_3.7.2-0.9.9.3.jar`)
   into a temp directory
2. Extract the ScalaZ3 jar (`scalaz3_3-4.13.4.jar`) into the same temp directory
3. Repackage as `stainless-dotty-plugin_3.7.2-0.9.9.3-merged.jar` (uncompressed)
4. Override `scalacOptions` to use `-Xplugin:<merged-jar>` instead of the
   original plugin jar

The merge is **idempotent**: if the merged jar already exists and contains
`z3/Z3Wrapper.class`, the merge is skipped.

### The Z3Wrapper.java Patches

The upstream `Z3Wrapper.java` has two issues that prevent it from working in
sbt:

1. **`System.exit(1)` in the static initializer**: When `withinJar()` returns
   false, the original code calls `System.exit(1)`. sbt's security manager
   catches this as a `SecurityException`, which propagates as
   `NoClassDefFoundError`, making the class permanently unloadable. Our patch
   skips loading instead of exiting.

2. **`catch (Exception e)` in `loadFromJar()`**: `System.load()` can throw
   `UnsatisfiedLinkError`, which is an `Error`, not an `Exception`. If it
   propagates, the static initializer fails and the class becomes unloadable.
   Our patch catches `Throwable` instead.

## Rebuilding the ScalaZ3 Jar

If you need to rebuild `scalaz3_3-4.13.4.jar` (e.g., for a different Z3
version, different Scala version, or different platform), follow these steps.

### Step 1: Install Z3

Download Z3 4.13.4 for your platform from
[the Z3 releases page](https://github.com/Z3Prover/z3/releases):

```bash
# For Linux x86_64 (glibc 2.35+):
mkdir -p ~/opt/z3-4.13.4
cd ~/opt/z3-4.13.4
wget https://github.com/Z3Prover/z3/releases/download/z3-4.13.4/z3-4.13.4-x64-glibc-2.35.zip
unzip z3-4.13.4-x64-glibc-2.35.zip
# This creates: ~/opt/z3-4.13.4/z3-4.13.4-x64-glibc-2.35/
```

Verify the installation:

```bash
~/opt/z3-4.13.4/z3-4.13.4-x64-glibc-2.35/bin/z3 --version
# Should print: Z3 version 4.13.4 - 64 bit

ls ~/opt/z3-4.13.4/z3-4.13.4-x64-glibc-2.35/bin/
# Should contain: z3, libz3.so, libz3java.so, com.microsoft.z3.jar
```

### Step 2: Clone and Patch ScalaZ3

```bash
git clone https://github.com/epfl-lara/ScalaZ3.git /tmp/scalaz3
cd /tmp/scalaz3
```

#### Patch `project/build.properties`

Update sbt version to 1.12.12 (the original uses 1.7.3, which has issues
with newer JDKs):

```properties
sbt.version=1.12.12
```

#### Patch `build.sbt`

Set `scalaVersion` to match the verified module (3.7.2):

```scala
scalaVersion := "3.7.2",
```

#### Patch `project/Build.scala`

Replace the Z3 source build with the pre-built Z3 path. Set:

```scala
lazy val z3SourceTag  = "z3-4.13.4"
lazy val z3PreBuiltPath = file("/home/<USER>/opt/z3-4.13.4/z3-4.13.4-x64-glibc-2.35/bin")
lazy val z3BuildPath = z3PreBuiltPath  // Use pre-built instead of building from source
```

And replace the `z3Task` with a version that verifies the pre-built files
instead of cloning/building Z3 from source:

```scala
val z3Task = Def.task {
  val s = streams.value
  s.log.info("Using pre-built Z3 4.13.4 at " + z3PreBuiltPath.absolutePath)
  for (file <- (z3BinaryFiles :+ z3JarFile) if !file.exists) {
    sys.error("Could not find pre-built Z3 file: " + file.absolutePath)
  }
  s.log.info("Pre-built Z3 4.13.4 verified.")
  "z3-4.13.4-prebuilt"
}
```

#### Patch `src/main/java/z3/Z3Wrapper.java`

Apply two fixes to the static initializer and `loadFromJar()`:

**Static initializer** — remove `System.exit(1)`:

```java
static {
    if (!withinJar()) {
        System.err.println("It seems you are not running ScalaZ3 from its JAR — native Z3 will be unavailable");
        // Don't call System.exit(1) — sbt's security manager catches it
        // and the exception propagates as NoClassDefFoundError, which makes
        // Inox's hasNativeZ3 return false. Instead, just skip loading.
    } else {
        System.setProperty("z3.skipLibraryLoad", "true");
        loadFromJar();
        debug("Z3 version: " + z3VersionString());
    }
}
```

**`loadFromJar()`** — catch `Throwable` instead of `Exception`:

```java
private static void loadFromJar() {
    String path = "SCALAZ3_" + versionString;
    File libDir = new File(System.getProperty("java.io.tmpdir") + DS + path + LIB_BIN);
    try {
        if (!libDir.isDirectory() || !libDir.canRead()) {
            libDir.mkdirs();
            extractFromJar(libDir);
        }
        System.load(libDir.getAbsolutePath() + DS + System.mapLibraryName("z3"));
        System.load(libDir.getAbsolutePath() + DS + System.mapLibraryName("z3java"));
        System.load(libDir.getAbsolutePath() + DS + System.mapLibraryName("scalaz3"));
    } catch (Throwable e) {
        // Catch Throwable (not just Exception) because System.load can
        // throw UnsatisfiedLinkError, which is an Error, not an Exception.
        System.err.println("ScalaZ3 native library loading failed: " + e.getMessage());
        e.printStackTrace();
    }
}
```

### Step 3: Build the Jar

```bash
cd /tmp/scalaz3
sbt package
```

This will:
1. Verify the pre-built Z3 files exist
2. Generate `LibraryChecksum.java` (MD5 of source files)
3. Compile `libscalaz3.so` (the C wrapper) using gcc
4. Compile the Scala + Java sources
5. Package everything into `target/scala-3.7.2/scalaz3_3-4.8.14.jar`

The output jar version is `4.8.14` (the ScalaZ3 internal version), but it
works with Z3 4.13.4.

### Step 4: Install the Jar

```bash
cp /tmp/scalaz3/target/scala-3.7.2/scalaz3_3-4.8.14.jar \
   /home/<USER>/git/rs/adk4s/verified/unmanaged/scalaz3_3-4.13.4.jar
```

### Step 5: Verify

```bash
cd /home/<USER>/git/rs/adk4s
rm -f target/scala-3.8.4/compiler_plugins/*-merged.jar
rm -rf /tmp/SCALAZ3_*
sbt -J-Xmx6g ring6
```

You should see:

```
[info] Merging ScalaZ3 into Stainless plugin jar: ...-merged.jar
...
[info]   nativez3, non-batched
```

## Troubleshooting

### "The Z3 native interface is not available. Falling back onto smt-z3."

This means Inox's `SolverFactory.hasNativeZ3` returned false. Check:

1. **Jar exists**: `ls verified/unmanaged/scalaz3_3-4.13.4.jar` — should be ~16MB
2. **Jar contains native libs**: `jar tf verified/unmanaged/scalaz3_3-4.13.4.jar | grep lib-bin/` — should list 3 `.so` files
3. **Jar contains Z3Wrapper**: `jar tf verified/unmanaged/scalaz3_3-4.13.4.jar | grep Z3Wrapper` — should list `z3/Z3Wrapper.class`
4. **Merged jar exists**: `ls target/scala-3.8.4/compiler_plugins/*-merged.jar` — should exist after first `ring6` run
5. **Merged jar has Z3Wrapper**: `jar tf target/scala-3.8.4/compiler_plugins/*-merged.jar | grep Z3Wrapper` — should list `z3/Z3Wrapper.class`
6. **Debug mode**: Run with `-J-Dscalaz3.debug.load=true` to see extraction/loading logs:
   ```bash
   sbt -J-Xmx6g -J-Dscalaz3.debug.load=true ring6 2>&1 | grep -E "Z3|scalaz3|Extracting|loading"
   ```

### "ScalaZ3 native library loading failed: ..."

The native libraries couldn't be loaded. Check:

1. **Z3 version compatibility**: The `.so` files in the jar must match the Z3
   version they were compiled against. If you rebuild for a different Z3
   version, you must rebuild the entire jar.
2. **glibc version**: The `.so` files are linked against glibc 2.35. On older
   systems, use an older Z3 release or rebuild from source.
3. **Architecture**: The jar contains x86_64 binaries. For ARM/aarch64, you
   must rebuild.

### "Cyclic reference involving mergeScalaZ3Plugin"

This shouldn't happen with the current `build.sbt`. The `mergeScalaZ3Plugin`
task computes the plugin jar path from `Versions.Scala` and
`Versions.ScalaVerified` constants — it does NOT depend on `scalacOptions`.
If you modify the task, ensure it doesn't read `scalacOptions` (which creates
a cycle since `scalacOptions` depends on `mergeScalaZ3Plugin`).

### Merged jar is corrupted (EOFException / NoClassDefFoundError)

The `jar cf0` command (uncompressed) is used to avoid compression issues with
native `.so` files. If you still see corruption, try manually merging:

```bash
mkdir /tmp/merge
cd /tmp/merge
jar xf <stainless-plugin-jar>
jar xf verified/unmanaged/scalaz3_3-4.13.4.jar
jar cf0 <stainless-plugin-jar>-merged.jar -C . .
```

### JVM cached the old plugin jar (merge ran but native Z3 still not detected)

If the Stainless plugin jar was already loaded by the JVM in the current sbt
session (e.g., from a previous compile), modifying it won't take effect until
the next sbt invocation. The current build avoids this by creating a **new**
`-merged.jar` file (not modifying the original in-place), so the JVM always
loads the fresh merged jar.

If you still see this issue, delete the merged jar and restart sbt:

```bash
rm -f target/scala-3.8.4/compiler_plugins/*-merged.jar
rm -rf /tmp/SCALAZ3_*
sbt -J-Xmx6g ring6
```

## File Layout

```
verified/
├── unmanaged/
│   ├── scalaz3_3-4.13.4.jar    # Pre-built ScalaZ3 jar (16MB)
│   └── README.md               # This file
└── src/main/scala/             # Verification model sources

target/scala-3.8.4/compiler_plugins/
├── stainless-dotty-plugin_3.7.2-0.9.9.3.jar         # Original Stainless plugin
└── stainless-dotty-plugin_3.7.2-0.9.9.3-merged.jar  # Merged with ScalaZ3 (auto-generated)
```

## build.sbt Integration

The relevant `build.sbt` settings (in the `verified` project):

```scala
// Declare ScalaZ3 as a Stainless extra dependency (for the compile classpath)
stainlessExtraDeps += "ch.epfl.lara" % "scalaz3_3" % "4.13.4"
  from s"file://${baseDirectory.value / "unmanaged" / "scalaz3_3-4.13.4.jar"}",

// Task: merge ScalaZ3 into the Stainless plugin jar (idempotent)
mergeScalaZ3Plugin := { ... },  // returns java.io.File (path to merged jar)

// Override scalacOptions to use the merged plugin jar
Compile / scalacOptions := {
  val opts   = (Compile / scalacOptions).value
  val merged = mergeScalaZ3Plugin.value
  if (stainlessEnabled.value) {
    opts.map { opt =>
      if (opt.startsWith("-Xplugin:") && opt.contains("stainless-dotty-plugin"))
        "-Xplugin:" + merged.getAbsolutePath
      else opt
    }
  } else opts
}
```

## References

- [ScalaZ3 GitHub](https://github.com/epfl-lara/ScalaZ3) — the wrapper we build
- [Z3 Prover](https://github.com/Z3Prover/z3) — the SMT solver
- [Stainless](https://github.com/epfl-lara/stainless) — the verification framework
- [Inox](https://github.com/epfl-lara/inox) — Stainless's solver layer (contains `SolverFactory.hasNativeZ3`)
