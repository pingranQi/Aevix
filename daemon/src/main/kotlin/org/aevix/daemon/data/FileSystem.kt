package org.aevix.daemon.data

import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SELinux
import android.os.SharedMemory
import android.os.SystemProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import org.aevix.ipc.ModuleCode
import org.aevix.daemon.BuildConfig
import org.aevix.daemon.utils.ObfuscationManager

private const val TAG = "AevixFileSystem"

/**
 * What the module installer labels the module tree, and what [FileSystem] holds it to.
 *
 * Chosen over `xposed_file` because the manager APK is read by whoever receives it: the shipped
 * `sepolicy.rule` grants `xposed_file` to `shell` but not to `untrusted_app`, so an installed
 * manager could no longer update itself. The platform allows every `appdomain` and `coredomain`
 * to read a `system_file`, which covers both.
 */
private const val SYSTEM_FILE_CONTEXT = "u:object_r:system_file:s0"
private const val HYOS_ACTIVE_PROPERTY = "rust.runtime_active"
private const val HYOS_VERSION_PROPERTY = "rust.runtime_version"
private const val HYOS_INDEX_DIR = "hyos"
private const val HYOS_GENERATION_NAME = ".generation"
private const val HYOS_INDEX_MAGIC = "aevix-hyos 2"
private const val HYOS_LIBRARY_DIR = "libhyos"

data class HyosModuleLibrary(val modulePackage: String, val libraryName: String, val libraryPath: String)

/**
 * What came of trying to load a module APK.
 *
 * The loader used to answer every refusal with the same null, so a module built against libxposed
 * API 100 — which this framework drops outright — reached the user as the same "the framework could
 * not load it" as a zip that will not parse. That refusal is the one with somewhere to go: the
 * module is not broken, it is old, and only a rebuild by its author moves it. The rest really are
 * indistinguishable from here.
 */
sealed interface ModuleLoad {
  /** Parsed, and ready to hand to a forking process. */
  data class Loaded(val apk: ModuleCode) : ModuleLoad

  /** Declares libxposed API 100, and carries nothing else this framework can load. */
  data object UnsupportedApi : ModuleLoad

  /** Will not parse, has no init files, or names no module classes. */
  data object Unusable : ModuleLoad
}

/** The APK when it loaded and null when it did not, for callers with nothing to say about why. */
val ModuleLoad.apkOrNull: ModuleCode?
  get() = (this as? ModuleLoad.Loaded)?.apk

object FileSystem {
  val basePath: Path = Paths.get("/data/adb/lspd")
  val logDirPath: Path = basePath.resolve("log")
  val oldLogDirPath: Path = basePath.resolve("log.old")
  val modulePath: Path = basePath.resolve("modules")
  val socketPath: Path = basePath.resolve(".cli_sock")
  val daemonApkPath: Path = Paths.get(System.getProperty("java.class.path", ""))
  val managerApkPath: Path = daemonApkPath.parent.resolve("manager.apk")
  val configDirPath: Path = basePath.resolve("config")
  val dbPath: File = configDirPath.resolve("modules_config.db").toFile()

  @Volatile private var preloadDex: SharedMemory? = null
  private var preloadDexObfuscate: Boolean? = null
  private var preloadDexApiProtection: Boolean? = null

  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
  private val lockPath: Path = basePath.resolve("lock")

  init {
    runCatching {
          Files.createDirectories(basePath)
          Os.chmod(basePath.toString(), "700".toInt(8))
          SELinux.setFileContext(basePath.toString(), SYSTEM_FILE_CONTEXT)
          Files.createDirectories(configDirPath)
        }
        .onFailure { Log.e(TAG, "Failed to initialize directories", it) }
    ensureManagerApkLabel()
  }

  /**
   * Reasserts the label the flash gave [managerApkPath].
   *
   * The manager APK is the one file in the module directory that leaves it as a descriptor rather
   * than as bytes: `FrameworkService.openManagerApk` hands it to the host process to inject, and
   * `ManagerService.getManagerApk` hands it to an installed manager to update itself. The kernel
   * judges that hand-off by the *receiver* — `selinux_binder_transfer_file` checks the file against
   * `cred_sid(to)` — so the read is asked of `u:r:shell:s0` for the parasitic host, and of the
   * app's own domain for an installed one, never of the daemon's root context. Opening the file
   * here therefore proves nothing about whether it can be passed on.
   *
   * The installer leaves the module tree `system_file`, which every `appdomain` and `coredomain`
   * may read, and that is what makes the hand-off work. Nothing keeps it that way: `/data/adb` is
   * `adb_data_file` in the platform's `file_contexts`, and init's `restorecon --recursive
   * --skip-ce /data` walks the tree again whenever the `file_contexts` digest changes — a system
   * image flashed over a kept /data — which puts the default back. `adb_data_file` is readable by
   * `adbd` alone, so from then on every launch fails the transfer, the host is handed nothing, and
   * the manager dies in the host's own activity with a null intent to show for it. Magisk does not
   * repair this at boot either: its own restorecon only relabels files that are `unlabeled`.
   */
  private fun ensureManagerApkLabel() {
    if (!managerApkPath.exists()) return
    val path = managerApkPath.toString()
    runCatching {
          if (SELinux.getFileContext(path) == SYSTEM_FILE_CONTEXT) return@runCatching
          if (!SELinux.setFileContext(path, SYSTEM_FILE_CONTEXT)) {
            Log.w(TAG, "Failed to relabel $path as $SYSTEM_FILE_CONTEXT")
          }
        }
        .onFailure { Log.e(TAG, "Failed to relabel $path", it) }
  }

  fun setupCli(): String {
    val cliSource = daemonApkPath.parent.resolve("cli").toFile()
    val cliDest = basePath.resolve("cli").toFile()
    if (cliSource.exists()) {
      runCatching {
            cliSource.copyTo(cliDest, overwrite = true)
            Os.chmod(cliDest.absolutePath, "700".toInt(8))
          }
          .onFailure { Log.e(TAG, "Failed to deploy CLI script", it) }
    }

    val cliSocket: String = socketPath.toString()
    val socketFile = File(cliSocket)
    if (socketFile.exists()) {
      Log.d(TAG, "Existing $cliSocket deleted")
      socketFile.delete()
    }

    return cliSocket
  }

  /**
   * Tries to lock the daemon lockfile. Returns false if another daemon is running.
   *
   * The lock lives in the open descriptor, so the descriptor has to outlive this method, and that
   * is harder than storing it: a `FileChannel` nothing refers to is closed by its own finalizer,
   * and closing *any* descriptor for a file drops every POSIX lock the process holds on that file,
   * including one taken through a descriptor that is still open. Two fields of our own used to
   * hold the pair, which worked in a debug build and in no release build: nothing read them, the
   * shrinker deletes a private field nothing reads, and the channel was then collected a fraction
   * of a second into every boot. From there any later `service.sh` — every KernelSU soft reboot
   * replays one — started a second daemon past this check, both claimed the same rendezvous name,
   * each latched the other as `SystemServerService.originService`, and the pair forwarded one
   * transaction back and forth until a binder thread's stack gave out (issue #878).
   *
   * A shutdown hook is where the reference goes instead. The platform holds the hook and the hook
   * reads both halves, so no shrinking can reach them and no keep rule has to be remembered, and
   * an orderly exit hands the lock back rather than leaving it to the kernel.
   */
  fun tryLock(): Boolean {
    return runCatching {
          val permissions =
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
          val channel =
              FileChannel.open(
                  lockPath, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE), permissions)
          val lock = runCatching { channel.tryLock() }.getOrNull()
          if (lock?.isValid != true) {
            // A daemon that loses the race exits in a moment anyway, but it closes its own
            // descriptor on the way rather than leave that to the finalizer this method exists
            // not to depend on.
            channel.close()
            return@runCatching false
          }
          Runtime.getRuntime()
              .addShutdownHook(
                  Thread {
                    runCatching { lock.release() }
                    runCatching { channel.close() }
                  })
          true
        }
        .getOrDefault(false)
  }

  /** Clears all special file attributes (like immutable) on a directory. */
  fun chattr0(path: Path): Boolean {
    return runCatching {
          val fd = Os.open(path.toString(), OsConstants.O_RDONLY, 0)
          // 0x40086602 for 64-bit, 0x40046602 for 32-bit (FS_IOC_SETFLAGS)
          val req = if (Process.is64Bit()) 0x40086602 else 0x40046602
          HiddenApiBridge.Os_ioctlInt(fd, req, 0)
          Os.close(fd)
          true
        }
        .recover { e -> if (e is ErrnoException && e.errno == OsConstants.ENOTSUP) true else false }
        .getOrDefault(false)
  }

  /** Recursively sets SELinux context. Crucial for modules to read their data. */
  fun setSelinuxContextRecursive(path: Path, context: String) {
    runCatching {
          SELinux.setFileContext(path.toString(), context)
          if (path.isDirectory()) {
            Files.list(path).use { stream ->
              stream.forEach { setSelinuxContextRecursive(it, context) }
            }
          }
        }
        .onFailure { Log.e(TAG, "Failed to set SELinux context for $path", it) }
  }

  /**
   * Lazily loads resources from the daemon's APK path via reflection. This allows FakeContext to
   * access strings/drawables without a real application context.
   */
  val resources: Resources by lazy {
    val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
    val addAssetPath =
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
          isAccessible = true
        }
    addAssetPath.invoke(am, daemonApkPath.toString())
    @Suppress("DEPRECATION") Resources(am, null, null)
  }

  /** Loads a single DEX file into SharedMemory, optionally applying obfuscation. */
  private fun readDex(
      inputStream: InputStream,
      expectedSize: Long,
      obfuscate: Boolean,
      apiProtection: Boolean
  ): SharedMemory {
    require(expectedSize in 1..Int.MAX_VALUE.toLong()) {
      "Invalid DEX entry size: $expectedSize"
    }

    var memory: SharedMemory? = null
    try {
      val allocated = SharedMemory.create(null, expectedSize.toInt())
      memory = allocated
      val mapped = allocated.mapReadWrite()
      try {
        copyExactly(inputStream, mapped)
      } finally {
        SharedMemory.unmap(mapped)
      }

      if (obfuscate || apiProtection) {
        // API protection is an independent switch: it must rewrite Xposed API names even when
        // general framework-name obfuscation is disabled (for example, in a debug build).
        val previous = checkNotNull(memory)
        val transformed = ObfuscationManager.obfuscateDex(previous, obfuscate, apiProtection)
        memory = transformed
        if (previous !== transformed) previous.close()
      }
      val result = checkNotNull(memory)
      result.setProtect(OsConstants.PROT_READ)
      return result
    } catch (failure: Throwable) {
      runCatching { memory?.close() }
      throw failure
    }
  }

  /**
   * The packages a module claims, when its module.prop fixes the scope. Null when it does not, so
   * a caller can tell "claims nothing" from "claims no restriction".
   *
   * staticScope is documented as "the module scope is fixed and users should not apply the module
   * on apps outside the scope list". Enforcing that in the manager alone leaves the socket CLI, a
   * backup restore and the module's own requestScope walking straight past it, so the daemon has
   * to know about it too.
   */
  fun readStaticScope(apkPath: String): Set<String>? =
      runCatching {
            ZipFile(File(apkPath)).use { zip ->
              val props =
                  Properties().apply {
                    zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                      runCatching { zip.getInputStream(entry).use { load(it) } }
                    }
                  }
              if (!props.getProperty("staticScope").toBoolean()) return@use null
              val claimed =
                  zip.getEntry("META-INF/xposed/scope.list")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                      lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                  } ?: emptySet()
              // A module that fixes its scope and then names nothing has fixed it at "no apps at
              // all": every write through here is refused, and pruneScopeToClaimed deletes the rows
              // the user had already chosen on the next cache rebuild. That is a packaging mistake
              // rather than an intention — a module ships staticScope=true with a scope.list it
              // forgot to generate — and the cost of reading it literally is a module that can
              // never hook anything, silently. So the declaration is ignored and the scope stays
              // the user's; the manager's ModuleDetection ignores it too, so the picker it draws
              // and the writes accepted here agree about what the module may hook.
              if (claimed.isEmpty()) {
                Log.w(TAG, "$apkPath fixes its scope but names nothing; ignoring staticScope")
                return@use null
              }
              claimed
            }
          }
          .onFailure { Log.w(TAG, "Cannot read the scope list of $apkPath", it) }
          .getOrNull()

  /** Parses the module APK, extracts init lists, and loads DEXes into SharedMemory. */
  fun loadModule(apkPath: String, obfuscate: Boolean, apiProtection: Boolean): ModuleLoad {
    val file = File(apkPath)
    if (!file.exists()) return ModuleLoad.Unusable

    val preLoadedApk = ModuleCode()
    val preLoadedDexes = mutableListOf<SharedMemory>()
    // Every failure below hands back a ModuleLoad the caller cannot clean up from: a SharedMemory
    // is a memfd plus a mapping, and this local list holds the only reference to them. Returning
    // without closing leaks one descriptor and one mapping per DEX that had already been read -
    // and a module that fails once fails on every process start, so those add up.

    // DEX handles are locally owned until the complete ModuleCode is returned.
    // Any parse/read failure must release handles already acquired.
    fun fail(reason: ModuleLoad): ModuleLoad {
      preLoadedDexes.forEach { runCatching { it.close() } }
      preLoadedDexes.clear()
      return reason
    }
    val moduleClassNames = mutableListOf<String>()
    val moduleLibraryNames = mutableListOf<String>()
    var isLegacy = false
    var exceptionPassthrough = false
    var targetApiVersion = 0
    var autoHotReload = false

    runCatching {
          ZipFile(file).use { zip ->
            // module.prop is specified as Java Properties format. Parsing it by hand mishandles
            // ':' as a separator, '!' comments, escapes and line continuations, and the manager
            // app already reads the same file with Properties.load.
            val props =
                Properties().apply {
                  zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                    // Properties.load rejects a malformed \uXXXX escape, which the old hand-rolled
                    // parser tolerated. Keep that tolerance: a bad module.prop must not make the
                    // APK unloadable, not least because a legacy module is selected by
                    // assets/xposed_init and needs no module.prop at all.
                    runCatching { zip.getInputStream(entry).use { load(it) } }
                        .onFailure { Log.w(TAG, "Malformed module.prop in $apkPath", it) }
                  }
                }

            val targetApi = leadingInt(props.getProperty("targetApiVersion"))
            targetApiVersion = targetApi
            autoHotReload = props.getProperty("autoHotReload")?.trim().toBoolean()
            // The module-wide mode ExceptionMode.DEFAULT resolves to. Anything that is not
            // "passthrough" - absent, misspelled, or an explicit "protective" - keeps the
            // protective default the API specifies.
            exceptionPassthrough =
                props.getProperty("exceptionMode")?.trim().equals("passthrough", true)

            val hasLegacyFile = zip.getEntry("assets/xposed_init") != null

            // Determine Loading Strategy based on Priority: API 101+ > Legacy > API 100
            val strategy =
                when {
                  targetApi >= 101 -> "MODERN"
                  hasLegacyFile -> "LEGACY"
                  targetApi == 100 -> "UNSUPPORTED" // API 100 is dropped
                  else -> "NONE"
                }

            // Helper to read the list files
            fun readList(name: String, dest: MutableList<String>) {
              zip.getEntry(name)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                  lines
                      .map { it.trim() }
                      .filter { it.isNotEmpty() && !it.startsWith("#") }
                      .forEach { dest.add(it) }
                }
              }
            }

            when (strategy) {
              "MODERN" -> {
                isLegacy = false
                readList("META-INF/xposed/java_init.list", moduleClassNames)
                readList("META-INF/xposed/native_init.list", moduleLibraryNames)
              }
              "LEGACY" -> {
                isLegacy = true
                readList("assets/xposed_init", moduleClassNames)
                readList("assets/native_init", moduleLibraryNames)
              }
              "UNSUPPORTED" -> {
                Log.w(TAG, "Module $apkPath uses API 100 which is no longer supported.")
                return fail(ModuleLoad.UnsupportedApi)
              }
              else -> return fail(ModuleLoad.Unusable) // No valid init files found
            }

            if (moduleClassNames.isEmpty()) return fail(ModuleLoad.Unusable)

            // Read DEX files
            var secondary = 1
            while (true) {
              val entryName = if (secondary == 1) "classes.dex" else "classes$secondary.dex"
              val dexEntry = zip.getEntry(entryName) ?: break
              zip.getInputStream(dexEntry).use {
                preLoadedDexes.add(readDex(it, dexEntry.size, obfuscate, apiProtection))
              }
              secondary++
            }
          }
        }
        .onFailure {
          Log.e(TAG, "Failed to load module $apkPath", it)
          return fail(ModuleLoad.Unusable)
        }

    if (preLoadedDexes.isEmpty()) return fail(ModuleLoad.Unusable)

    // Apply obfuscation
    if (obfuscate) {
      val signatures = ObfuscationManager.getSignatures(apiProtection)
      for (i in moduleClassNames.indices) {
        val s = moduleClassNames[i]
        signatures.entries
            .firstOrNull { s.startsWith(it.key) }
            ?.let { moduleClassNames[i] = s.replace(it.key, it.value) }
      }
    }

    preLoadedApk.apply {
      this.preLoadedDexes = preLoadedDexes
      this.moduleClassNames = moduleClassNames
      this.moduleLibraryNames = moduleLibraryNames
      this.legacy = isLegacy
      this.exceptionPassthrough = exceptionPassthrough
      this.targetApiVersion = targetApiVersion
      this.autoHotReload = autoHotReload
    }

    return ModuleLoad.Loaded(preLoadedApk)
  }

  /** Safely creates the log directory. If a file exists with the same name, it deletes it first. */
  private fun createLogDirPath() {
    if (!Files.isDirectory(logDirPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      logDirPath.toFile().deleteRecursively()
    }
    Files.createDirectories(logDirPath)
  }

  /**
   * Rotates the log directory by clearing file attributes (chattr 0), deleting the old backup, and
   * renaming the current log directory to the backup.
   */
  fun moveLogDir() {
    runCatching {
          if (Files.exists(logDirPath)) {
            if (chattr0(logDirPath)) {
              // Kotlin's deleteRecursively replaces the verbose Java SimpleFileVisitor
              oldLogDirPath.toFile().deleteRecursively()
              Files.move(logDirPath, oldLogDirPath)
            }
          }
          Files.createDirectories(logDirPath)
        }
        .onFailure { Log.e(TAG, "Failed to move log directory", it) }
  }

  fun getPropsPath(): File {
    createLogDirPath()
    return logDirPath.resolve("props.txt").toFile()
  }

  fun getKmsgPath(): File {
    createLogDirPath()
    return logDirPath.resolve("kmsg.log").toFile()
  }

  /**
   * Drops the cached DEX so the next injection maps it with the current API-protection mode.
   * Do not close the previous SharedMemory here: a concurrent Binder reply may still be writing
   * its descriptor. Once that caller releases its reference the old mapping can be reclaimed.
   */
  @Synchronized
  fun invalidatePreloadDex() {
    preloadDex = null
    preloadDexObfuscate = null
    preloadDexApiProtection = null
  }

  @Synchronized
  fun getPreloadDex(obfuscate: Boolean, apiProtection: Boolean): SharedMemory? {
    if (preloadDex == null ||
        preloadDexObfuscate != obfuscate ||
        preloadDexApiProtection != apiProtection) {
      // A fork racing a settings change always receives a DEX built with the flags it snapshots.
      // Do not close a previous SharedMemory: a Binder reply may still be parceling its descriptor.
      preloadDex = null
      preloadDexObfuscate = null
      preloadDexApiProtection = null
      runCatching {
            val dexFile = File("framework/aevix.dex")
            FileInputStream(dexFile).use {
              preloadDex = readDex(it, dexFile.length(), obfuscate, apiProtection)
              preloadDexObfuscate = obfuscate
              preloadDexApiProtection = apiProtection
            }
          }
          .onFailure { Log.e(TAG, "Failed to load framework dex", it) }
    }
    return preloadDex
  }

  fun ensureModuleFilePath(path: String?) {
    if (path == null || path.contains(File.separatorChar) || path == "." || path == "..") {
      throw RemoteException("Invalid path: $path")
    }
  }

  fun resolveModuleDir(packageName: String, dir: String, userId: Int, uid: Int): Path {
    val path = modulePath.resolve(userId.toString()).resolve(packageName).resolve(dir).normalize()
    path.toFile().mkdirs()

    if (SELinux.getFileContext(path.toString()) != "u:object_r:xposed_data:s0") {
      runCatching {
            setSelinuxContextRecursive(path, "u:object_r:xposed_data:s0")
            if (uid != -1) Os.chown(path.toString(), uid, uid)
            Os.chmod(path.toString(), "755".toInt(8))
          }
          .onFailure { throw RemoteException("Failed to set SELinux context: ${it.message}") }
    }
    return path
  }

  /**
   * Copies a module's native libraries out of its APK into a directory this framework owns, and
   * answers with that directory.
   *
   * A module loaded into system_server cannot dlopen a library straight out of its own APK.
   * Everything under /data/app is apk_data_file, and while system_server may read and map such a
   * file it may not execute it; AOSP says why in so many words - "Executable files in /data are a
   * persistence vector" - and forbids granting it. Every app domain does hold that permission,
   * which is the whole reason the same module loads the same library without trouble in an ordinary
   * process and fails only in system_server.
   *
   * The way past it is not a new rule but the one this module already ships. xposed_data is a type
   * we declare ourselves, outside the data_file_type attribute that neverallow is written against,
   * and `allow * xposed_data {file dir} *` already reaches every domain - system_server included.
   * A copy placed under it is one system_server may map executable. Extraction has a second
   * benefit: the library ends up at offset zero of an ordinary file, so it no longer has to be
   * stored uncompressed and page-aligned inside the APK to be mappable at all.
   *
   * Note that this deliberately does not consult moduleLibraryNames. That list only names the
   * libraries whose native_init we are asked to call, and a module is free to load its own
   * libraries without declaring any - the module that prompted all this does exactly that.
   *
   * Returns null when the module ships nothing for this ABI or the copy failed, in which case the
   * module still loads and only its native part fails, exactly as it does today.
   */
  fun stageNativeLibraries(
      root: Path,
      packageName: String,
      apkPath: String,
      dirName: String = "lib",
      version: String? = null,
  ): String? =
      runCatching {
            val apk = File(apkPath)
            val packageDir = root.resolve(dirName).resolve(packageName)
            val dir = if (version == null) packageDir else packageDir.resolve(version)

            // Re-extract only when the APK behind the copy changed. Getting this wrong in the
            // lenient direction would leave system_server running a module's superseded native
            // code, so the framework's own version is part of the stamp as well.
            val stamp = "${apk.length()}:${apk.lastModified()}:${BuildConfig.VERSION_CODE}"
            val stampFile = dir.resolve(".stamp").toFile()
            if (stampFile.isFile && stampFile.readText() == stamp) return@runCatching dir.toString()
            val abis =
                if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS

            ZipFile(apk).use { zip ->
              val libraries =
                  zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }
                      .toList()
              // A module built for several ABIs keeps them in sibling directories, and only the one
              // this process could load is worth copying.
              val abi =
                  abis.firstOrNull { abi -> libraries.any { it.name.startsWith("lib/$abi/") } }
                      ?: return@runCatching null

              dir.toFile().deleteRecursively()
              Files.createDirectories(dir)

              libraries
                  .filter { it.name.startsWith("lib/$abi/") }
                  .forEach { entry ->
                    val target = dir.resolve(entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                      Files.newOutputStream(target).use { input.copyTo(it) }
                    }
                    Os.chmod(target.toString(), "644".toInt(8))
                  }

              stampFile.writeText(stamp)
              // The daemon runs with a zero umask, so every mode here is set rather than inherited.
              Os.chmod(stampFile.absolutePath, "644".toInt(8))
              // The misc root is searchable but not listable; the staged tree keeps that shape, and
              // the label is what actually decides whether system_server may map these files.
              Os.chmod(dir.parent.toString(), "711".toInt(8))
              Os.chmod(dir.toString(), "711".toInt(8))
              setSelinuxContextRecursive(dir, "u:object_r:xposed_data:s0")
              SELinux.setFileContext(dir.parent.toString(), "u:object_r:xposed_data:s0")
              if (version != null) {
                // The versioned layout adds libhyos/package above the active version directory.
                Os.chmod(dir.parent.parent.toString(), "711".toInt(8))
                SELinux.setFileContext(dir.parent.parent.toString(), "u:object_r:xposed_data:s0")
              }
              dir.toString()
            }
          }
          .onFailure { Log.e(TAG, "Failed to stage the native libraries of $packageName", it) }
          .getOrNull()

  /**
   * Drops staged libraries belonging to modules that are no longer bound for system_server, so an
   * uninstalled or rescoped module does not leave a copy of its native code behind for good.
   */
  fun pruneStagedNativeLibraries(root: Path?, keep: Set<String>, dirName: String = "lib") {
    if (root == null) return
    runCatching {
          val libRoot = root.resolve(dirName)
          if (!libRoot.isDirectory()) return
          Files.list(libRoot).use { stream ->
            stream
                .filter { it.fileName.toString() !in keep }
                .forEach { it.toFile().deleteRecursively() }
          }
        }
        .onFailure { Log.e(TAG, "Failed to prune staged native libraries", it) }
  }

  fun hasHyosRuntime(): Boolean =
      SystemProperties.getBoolean(HYOS_ACTIVE_PROPERTY, false) &&
          SystemProperties.get(HYOS_VERSION_PROPERTY).orEmpty().isNotEmpty()

  fun stageHyosNativeLibraries(root: Path, packageName: String, apkPath: String): String? {
    val apk = File(apkPath)
    val version = "${apk.length()}-${apk.lastModified()}-${BuildConfig.VERSION_CODE}"
    return stageNativeLibraries(root, packageName, apkPath, HYOS_LIBRARY_DIR, version)
  }

  /** Remove retired version directories only after the new index generation is committed. */
  fun pruneHyosNativeLibraries(root: Path?, keepDirectories: Set<String>) {
    if (root == null) return
    runCatching {
      val libraryRoot = root.resolve(HYOS_LIBRARY_DIR)
      if (!libraryRoot.isDirectory()) return
      val keep = keepDirectories.mapNotNull {
        runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull()
      }.toSet()
      Files.list(libraryRoot).use { packages ->
        packages.filter { it.isDirectory() }.forEach { packageDir ->
          Files.list(packageDir).use { versions ->
            versions.forEach { versionDir ->
              if (versionDir.toAbsolutePath().normalize() !in keep) {
                versionDir.toFile().deleteRecursively()
              }
            }
          }
          val empty = Files.list(packageDir).use { !it.findAny().isPresent }
          if (empty) packageDir.toFile().delete()
        }
      }
    }.onFailure { Log.e(TAG, "Failed to prune retired HyperOS native libraries", it) }
  }

  private fun isSafeHyosField(value: String): Boolean =
      value.isNotEmpty() && value.none { it == '\t' || it == '\n' || it == '\r' || it == '\u0000' }

  /** The '#' delimiter is reserved between the Android process name and its Linux UID. */
  private fun isSafeHyosScopeKey(value: String): Boolean {
    val separator = value.lastIndexOf('#')
    if (separator <= 0 || separator == value.lastIndex) return false
    val processName = value.substring(0, separator)
    val uid = value.substring(separator + 1).toIntOrNull() ?: return false
    if (uid < 0 || processName.length > 255) return false
    return processName.all { it.code in 0x21..0x7e && it != '/' && it != '\\' && it != '#' }
  }

  /** Write and fsync a sibling temp file, then atomically replace the published file. */
  private fun atomicWriteHyosFile(target: Path, content: String) {
    val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
      FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
        val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
      }
      Os.chmod(temp.toString(), "600".toInt(8))
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  @Synchronized
  fun publishHyosIndex(index: Map<String, List<HyosModuleLibrary>>): Boolean {
    return runCatching {
      val dir = basePath.resolve(HYOS_INDEX_DIR)
      Files.createDirectories(dir)
      Os.chmod(dir.toString(), "700".toInt(8))

      val generation = dir.resolve(HYOS_GENERATION_NAME)
      val previous = runCatching { generation.toFile().readText().trim().toLong() }.getOrDefault(0L)
      // Odd means publication is in progress. Readers accept only an unchanged even generation,
      // making the individually-atomic files behave as one consistent snapshot.
      val writingGeneration = if (previous % 2L == 0L) previous + 1L else previous + 2L
      atomicWriteHyosFile(generation, writingGeneration.toString())

      val written = mutableSetOf<String>()
      index.forEach { (scopeKey, libraries) ->
        if (!isSafeHyosScopeKey(scopeKey)) {
          Log.w(TAG, "Skipping invalid HyperOS scope key")
          return@forEach
        }

        val records = libraries.filter { library ->
          val path = runCatching { Paths.get(library.libraryPath).toAbsolutePath().normalize() }.getOrNull()
          val safe = isSafeHyosField(library.modulePackage) &&
              '/' !in library.modulePackage && '\\' !in library.modulePackage &&
              isSafeHyosField(library.libraryName) &&
              library.libraryName.endsWith(".so") &&
              '/' !in library.libraryName && '\\' !in library.libraryName &&
              isSafeHyosField(library.libraryPath) &&
              path != null && path.isAbsolute &&
              path.fileName.toString() == library.libraryName &&
              path.parent?.parent?.fileName?.toString() == library.modulePackage &&
              path.parent?.parent?.parent?.fileName?.toString() == HYOS_LIBRARY_DIR &&
              Files.isRegularFile(path) && Files.isReadable(path)
          if (!safe) Log.w(TAG, "Skipping invalid HyperOS native library record for $scopeKey")
          safe
        }
        if (records.isEmpty()) return@forEach

        val target = dir.resolve(scopeKey)
        val content = buildString {
          appendLine(HYOS_INDEX_MAGIC)
          records.forEach {
            append(it.modulePackage); append('\t')
            append(it.libraryName); append('\t')
            append(it.libraryPath); append('\n')
          }
        }
        atomicWriteHyosFile(target, content)
        written.add(scopeKey)
      }

      // Finish the seqlock only after all data replacements and stale-file removals complete.
      val stale = mutableListOf<Path>()
      Files.list(dir).use { stream ->
        stream.forEach {
          if (it.fileName.toString() != HYOS_GENERATION_NAME && it.fileName.toString() !in written) {
            stale.add(it)
          }
        }
      }
      stale.forEach { Files.deleteIfExists(it) }

      atomicWriteHyosFile(generation, (writingGeneration + 1L).toString())
      true
    }.onFailure { Log.e(TAG, "Failed to publish HyperOS index", it) }.getOrDefault(false)
  }

  fun toGlobalNamespace(path: String): File {
    return if (path.startsWith("/")) File("/proc/1/root", path) else File("/proc/1/root/$path")
  }

  fun getLogs(zipFd: ParcelFileDescriptor) {
    runCatching {
          ZipOutputStream(java.io.FileOutputStream(zipFd.fileDescriptor)).use { os ->
            // The commit, not just the version code: the code is the commit count on master, so
            // every branch build at the same depth wears the number of an official build it was
            // never made from. Without it an attached archive cannot be tied to a binary.
            val comment =
                "Aevix ${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME} " +
                    "(${BuildConfig.VERSION_CODE}) ${BuildConfig.VERSION_HASH}"
            os.setComment(comment)
            os.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)

            fun addFile(name: String, file: File) {
              if (!file.exists() || !file.isFile) return
              runCatching {
                    os.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(os) }
                    os.closeEntry()
                  }
                  .onFailure { Log.e(TAG, "Failed to export $file as $name", it) }
            }

            fun addDir(basePath: String, dir: File) {
              if (!dir.exists() || !dir.isDirectory) return
              dir.walkTopDown()
                  .filter { it.isFile }
                  .forEach { file ->
                    val relativePath = dir.toPath().relativize(file.toPath()).toString()
                    val entryName =
                        if (basePath.isEmpty()) relativePath else "$basePath/$relativePath"
                    addFile(entryName, file)
                  }
            }

            fun addProcOutput(name: String, vararg cmd: String) {
              runCatching {
                val proc = ProcessBuilder(*cmd).start()
                os.putNextEntry(ZipEntry(name))
                proc.inputStream.use { it.copyTo(os) }
                os.closeEntry()
              }
            }

            // Gather system crash traces
            addDir("tombstones", File("/data/tombstones"))
            addDir("anr", File("/data/anr"))
            addDir(
                "crash_shell",
                File("/data/data/${BuildConfig.MANAGER_INJECTED_PKG_NAME}/cache/crash"))
            addDir(
                "crash_manager",
                File("/data/data/${BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME}/cache/crash"))

            // Gather system logs directly via shell
            addProcOutput("full.log", "logcat", "-b", "all", "-d")
            addProcOutput("dmesg.log", "dmesg")

            // Gather system module states safely
            val magiskDataDir = File("/data/adb/modules")
            if (magiskDataDir.exists() && magiskDataDir.isDirectory) {
              magiskDataDir.listFiles()?.forEach { moduleDir ->
                val modName = moduleDir.name
                listOf("module.prop", "remove", "disable", "update", "sepolicy.rule").forEach {
                  addFile("modules/$modName/$it", File(moduleDir, it))
                }
              }
            }

            // Gather memory/mount info for daemon and caller
            val proc = File("/proc")
            arrayOf("self", Binder.getCallingPid().toString()).forEach { pid ->
              val pidPath = File(proc, pid)
              listOf("maps", "mountinfo", "status").forEach {
                addFile("proc/$pid/$it", File(pidPath, it))
              }
            }

            // Gather Database and Scopes
            addFile("modules_config.db", dbPath)
            runCatching {
                  val scopes = ConfigCache.state.scopes
                  Log.d(TAG, "Exporting scopes for ${scopes.size} targets")
                  os.putNextEntry(ZipEntry("scopes.txt"))
                  scopes.forEach { (scope, modules) ->
                    os.write("${scope.processName}/${scope.uid}\n".toByteArray())
                    modules.forEach { mod ->
                      os.write("\t${mod.packageName}\n".toByteArray())
                      mod.code?.moduleClassNames?.forEach { cn ->
                        os.write("\t\t$cn\n".toByteArray())
                      }
                      mod.code?.moduleLibraryNames?.forEach { ln ->
                        os.write("\t\t$ln\n".toByteArray())
                      }
                    }
                  }
                  os.closeEntry()
                }
                .onFailure { Log.e(TAG, "Failed to export module scopes", it) }

            // Gather daemon logs
            addDir("log", logDirPath.toFile())
            addDir("log.old", oldLogDirPath.toFile())
          }
        }
        .onFailure { Log.e(TAG, "Failed to export logs", it) }
        .also { runCatching { zipFd.close() } }
  }

  private fun getNewLogFileName(prefix: String): String {
    return "${prefix}_${formatter.format(Instant.now())}.log"
  }

  /**
   * The parts still on disk for one of the two logs, oldest first.
   *
   * Read from the directory rather than from LogcatMonitor's LRU so that a manager opened after a
   * daemon restart still sees the history: the LRU is rebuilt empty, the files are not.
   */
  fun listLogParts(verbose: Boolean): List<String> {
    val prefix = if (verbose) "verbose_" else "modules_"
    return runCatching {
          logDirPath
              .toFile()
              .listFiles { file -> file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".log") }
              ?.map { it.name }
              // The names carry an ISO-8601 timestamp, so lexicographic order is chronological.
              ?.sorted()
              .orEmpty()
        }
        .getOrDefault(emptyList())
  }

  /**
   * Opens one part by name.
   *
   * The name arrives from an unprivileged process and is used to build a path inside a directory
   * only root can read, so it is never trusted: it has to be one of the names [listLogParts] just
   * returned, which rules out traversal and anything outside the log directory by construction
   * rather than by pattern-matching for "..".
   */
  fun openLogPart(verbose: Boolean, name: String): File? {
    if (name !in listLogParts(verbose)) return null
    return logDirPath.resolve(name).toFile().takeIf { it.isFile }
  }

  fun getNewVerboseLogPath(): File {
    createLogDirPath()
    return logDirPath.resolve(getNewLogFileName("verbose")).toFile()
  }

  fun getNewModulesLogPath(): File {
    createLogDirPath()
    return logDirPath.resolve(getNewLogFileName("modules")).toFile()
  }

  // Matches the manager's leading-integer parsing, including values such as "101.0".
  private fun leadingInt(value: String?): Int =
      value?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}
