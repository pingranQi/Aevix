#include <dirent.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "common/logging.h"
#include "core/native_api.h"
#include "elf/symbol_cache.h"

#include "zygisk_next_api.h"

/**
 * @file hyos_runtime.cpp
 * @brief Native hooking support for applications running on the HyperOS Rust Runtime.
 *
 * A process spawned by /system_ext/bin/hyos_spawner is not an ART process. It has no JVM, no
 * JNIEnv, and no binder of its own, so none of the machinery the rest of Aevix is built on is
 * available inside it: the framework DEX cannot be loaded, and the daemon cannot be asked what is
 * in scope. What such a process does have is the dynamic loader, and therefore native libraries.
 *
 * This translation unit is the whole of Aevix's presence there. It is compiled into the same
 * libzygisk.so the Zygisk loader injects everywhere else, and it is reached through Zygisk Next's
 * Runtime API, which the module declares itself for in zn_modules.txt:
 *
 *   path=/system_ext/bin/hyos_spawner companion zygisk/arm64-v8a.so
 *
 * The flow, in order:
 *
 *   1. Zygisk Next injects this library into hyos_spawner and calls `zn_module`'s onModuleLoaded.
 *      getRuntime() reports ZN_RUNTIME_HYOS, and we register `zn_companion_module`'s callbacks.
 *   2. The spawner forks an application process, applies its uid, gid, groups and SELinux context,
 *      and calls onAppSpecialized in the child.
 *   3. The child asks the companion -- a root process Zygisk Next forked for us, reached over a
 *      socket the spawner connected and every child inherited -- for the path the Aevix daemon
 *      publishes its per-package state under.
 *   4. The child reads the index for its own package, which names the native libraries of every
 *      Xposed module in scope for it, and loads each one. Each library's `native_init` is then
 *      called with the same NativeAPIEntries an ART process gets, so a module's native part needs
 *      no change to work here: it hooks the HyperOS process with the primitives it was already
 *      written against.
 *
 * The hook primitives come from Zygisk Next rather than from Aevix's own Dobby, for the reason
 * SetHookBackend documents: two engines patching the same address destroy each other's trampolines,
 * and the engine that already owns this process has the better claim.
 */

namespace vector::native::hyos {

namespace {

// --- The published state, and how it reaches this process --------------------------------------

/**
 * Where the daemon writes what each HyperOS Runtime process should load.
 *
 * Inside Aevix's own tree rather than in the shared /data/misc, because nobody but the companion
 * reads it: the answer is looked up by the companion and handed to the spawner over the connection
 * the two already share, long before any fork. Nothing here has to be reachable by the application
 * the index describes, which is why there is no pointer file any more and no path published
 * anywhere -- the channel is the companion connection, not the filesystem.
 *
 * Magisk holds /data/adb at mode 0700 owned by root, so this subtree is root-only by construction.
 */
constexpr auto kIndexDir = "/data/adb/lspd/hyos";
constexpr std::string_view kGenerationName = ".generation";
constexpr std::string_view kIndexMagic = "aevix-hyos 2";

/// The two things the companion can be asked for.
constexpr char kRequestGeneration = 'G';
constexpr char kRequestTable = 'T';

/// Refuse a table larger than this rather than believe a length field.
constexpr uint32_t kMaxTableLength = 8u << 20;

/**
 * The one socket this side of the runtime is reached through.
 *
 * The Aevix daemon connects and reads a single byte: `1` means the Zygisk Next Runtime API was
 * registered and applications will be specialized, anything else means the runtime did not want us.
 *
 * The path and the leading byte are LSPosed 2.2.0's, recovered from its released daemon -- its
 * `ILSPManagerService` transaction 67 connects here and reads exactly that byte, and its manager
 * turns the answer into the notice the user sees.
 *
 * Only root can reach it, and that is a property of the tree rather than of this file: Magisk holds
 * /data/adb at mode 0700 owned by root and labels it adb_data_file, so no application process can
 * traverse it whatever mode the socket is given -- LSPosed's own copy is chmod 0600 on top of that.
 * The daemon is therefore the only caller this socket will ever have, and the status byte is the
 * whole of what it is asked. A process the runtime spawned cannot come here for its libraries and
 * does not try: those arrive over the connection the spawner holds to the companion, the one
 * channel a forked child already has.
 */
constexpr auto kMonitorPath = "/data/adb/lspd/hyos_monitor";

/// How long the companion waits for the spawner's status byte before assuming the worst.
constexpr time_t kStatusByteTimeoutSeconds = 2;

/// How long either side waits on the companion connection.
constexpr time_t kCompanionTimeoutSeconds = 2;

// --- The Zygisk Next view of this process -----------------------------------------------------

const ZygiskNextAPI *g_api = nullptr;

// The companion connection, established by the spawner before any fork. -1 when the companion could
// not be started, which costs everything: the status byte, and the table itself.
int g_companion_fd = -1;

// Whether the HyperOS Runtime API was actually registered here. Zygisk Next's Runtime API is an
// optional feature of that loader: an older build does not offer it, and one that does can still
// refuse the registration. None of that is this process's problem, so every such path turns the
// feature off rather than failing -- an ordinary Zygisk process and a hyos_spawner running without
// us are both perfectly good outcomes.
bool g_registered = false;

// What the monitor hands out: 1 once the Runtime API is registered, 0 until then and forever if it
// never is. Written by the companion when the spawner reports, read by every monitor client -- two
// threads of one process, which is why it is atomic rather than plain.
std::atomic<char> g_injection_status{0};

/// One module native library to load into this process.
struct LibraryEntry {
    std::string module_package;
    std::string library_name;
    std::string library_path;
};

// What the spawner fetched, and which generation of the daemon's index it came from. Built before
// the fork, read after it: a process the runtime spawns looks its own name up in this and does no
// I/O at all, which is the point -- the callback runs in a child of a multithreaded parent, where
// reaching for a file or a socket means reaching for a lock some other thread may have held.
std::map<std::string, std::vector<LibraryEntry>> g_index;
uint64_t g_generation = 0;
bool g_index_loaded = false;

// Whether this process has already done its work. A child is forked once and specializes once, but
// a runtime is free to call the callback again, and loading a module's libraries twice would run
// its native_init twice.
bool g_specialized = false;
bool g_native_api_ready = false;

// --- Small I/O helpers -------------------------------------------------------------------------

/// Reads a whole file, up to a sane bound. False when it cannot be read at all.
bool ReadFile(const std::string &path, std::string &out) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    out.clear();
    char buf[512];
    for (;;) {
        const ssize_t n = read(fd, buf, sizeof(buf));
        if (n < 0) {
            close(fd);
            out.clear();
            return false;
        }
        if (n == 0) break;
        out.append(buf, static_cast<size_t>(n));
        if (out.size() > (1u << 20)) {
            close(fd);
            out.clear();
            return false;
        }
    }
    close(fd);
    return true;
}

/// Writes every byte, or reports that it could not.
bool WriteAll(int fd, const std::string &data) {
    size_t written = 0;
    while (written < data.size()) {
        const ssize_t n = write(fd, data.data() + written, data.size() - written);
        if (n <= 0) return false;
        written += static_cast<size_t>(n);
    }
    return true;
}

/// Reads exactly `length` bytes, or reports that it could not.
bool ReadAll(int fd, void *data, size_t length) {
    auto *out = static_cast<char *>(data);
    size_t read_so_far = 0;
    while (read_so_far < length) {
        const ssize_t n = read(fd, out + read_so_far, length - read_so_far);
        if (n <= 0) return false;
        read_so_far += static_cast<size_t>(n);
    }
    return true;
}

void AppendU32(std::string &out, uint32_t value) {
    out.append(reinterpret_cast<const char *>(&value), sizeof(value));
}

bool TakeU32(const std::string &in, size_t &pos, uint32_t &value) {
    if (pos + sizeof(value) > in.size()) return false;
    memcpy(&value, in.data() + pos, sizeof(value));
    pos += sizeof(value);
    return true;
}

/// Splits one index line into its three tab-separated fields and rejects delimiter injection.
bool ParseIndexLine(const std::string &line, LibraryEntry &out) {
    const auto first = line.find('\t');
    if (first == std::string::npos) return false;
    const auto second = line.find('\t', first + 1);
    if (second == std::string::npos || line.find('\t', second + 1) != std::string::npos) return false;

    out.module_package = line.substr(0, first);
    out.library_name = line.substr(first + 1, second - first - 1);
    out.library_path = line.substr(second + 1);
    if (out.module_package.empty() || out.library_name.empty() || out.library_path.empty()) return false;
    if (out.library_name.find('/') != std::string::npos ||
        out.library_name.find('\\') != std::string::npos ||
        out.library_name.size() < 4 ||
        out.library_name.compare(out.library_name.size() - 3, 3, ".so") != 0 ||
        out.library_path.front() != '/') return false;
    for (unsigned char ch : out.module_package + out.library_name + out.library_path) {
        if (ch < 0x20 || ch == 0x7f) return false;
    }
    const auto slash = out.library_path.find_last_of('/');
    return out.library_path.substr(slash + 1) == out.library_name;
}

std::string MakeScopeKey(std::string_view process_name, uid_t uid) {
    return std::string(process_name) + "#" + std::to_string(uid);
}

bool ParseScopeFilename(const std::string &filename, std::string &process_name, uid_t &uid) {
    const auto separator = filename.rfind('#');
    if (separator == std::string::npos || separator == 0 || separator + 1 >= filename.size()) return false;
    process_name = filename.substr(0, separator);
    if (process_name.size() > 244) return false;
    for (unsigned char ch : process_name) {
        if (ch < 0x21 || ch > 0x7e || ch == '/' || ch == '\\' || ch == '#') return false;
    }
    const std::string uid_text = filename.substr(separator + 1);
    for (unsigned char ch : uid_text) {
        if (ch < '0' || ch > '9') return false;
    }
    char *end = nullptr;
    errno = 0;
    const unsigned long parsed = strtoul(uid_text.c_str(), &end, 10);
    if (errno != 0 || end == uid_text.c_str() || *end != '\0' ||
        parsed > static_cast<unsigned long>(static_cast<uid_t>(-1))) return false;
    uid = static_cast<uid_t>(parsed);
    return true;
}

/// Turns one published index into the libraries it names. The first line is the format's marker.
std::vector<LibraryEntry> ParseIndex(const std::string &content) {
    std::vector<LibraryEntry> entries;
    bool first = true;
    for (size_t start = 0; start < content.size();) {
        const auto end = content.find('\n', start);
        const std::string line =
            content.substr(start, end == std::string::npos ? std::string::npos : end - start);
        start = end == std::string::npos ? content.size() : end + 1;

        if (first) {
            first = false;
            if (line == kIndexMagic) continue;
            LOGE("AevixHyperRuntime: an index does not start with its marker; ignoring it.");
            return {};
        }
        if (line.empty() || line[0] == '#') continue;

        LibraryEntry entry;
        if (!ParseIndexLine(line, entry)) {
            LOGW("AevixHyperRuntime: skipping a malformed index line.");
            continue;
        }
        entries.push_back(std::move(entry));
    }
    return entries;
}

// --- The companion's half ----------------------------------------------------------------------

/// The daemon's generation stamp, which moves whenever the published set changes.
uint64_t ReadGeneration() {
    std::string content;
    const std::string path = std::string(kIndexDir) + "/" + std::string(kGenerationName);
    if (!ReadFile(path, content)) return 0;
    return strtoull(content.c_str(), nullptr, 10);
}

/**
 * @brief Packs every published index into one blob.
 *
 * Sent as a whole rather than answered per name, because the spawner does not know which name it
 * will be asked about: the runtime picks the package after the fork, so the only safe moment to
 * learn the answer is before it. One transfer per generation, into memory the fork then shares.
 */
std::string BuildTable() {
    std::vector<std::pair<std::string, std::string>> records;

    DIR *dir = opendir(kIndexDir);
    if (dir != nullptr) {
        while (struct dirent *entry = readdir(dir)) {
            if (entry->d_name[0] == '.') continue;
            std::string content;
            const std::string path = std::string(kIndexDir) + "/" + entry->d_name;
            if (ReadFile(path, content)) records.emplace_back(entry->d_name, std::move(content));
        }
        closedir(dir);
    }

    std::string payload;
    AppendU32(payload, static_cast<uint32_t>(records.size()));
    for (const auto &record : records) {
        AppendU32(payload, static_cast<uint32_t>(record.first.size()));
        payload += record.first;
        AppendU32(payload, static_cast<uint32_t>(record.second.size()));
        payload += record.second;
    }
    return payload;
}

/**
 * @brief Serves the spawner: a generation to compare against, or the table itself.
 *
 * One client only -- the spawner -- so the exchange needs no correlation and no care about replies
 * arriving out of order. The companion is also the only reader of the index, and it runs as root.
 */
void *ServeCompanion(void *arg) {
    const int fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    for (;;) {
        char request = 0;
        if (read(fd, &request, 1) != 1) break;

        if (request == kRequestGeneration) {
            const uint64_t generation = ReadGeneration();
            if (!WriteAll(fd, std::string(reinterpret_cast<const char *>(&generation),
                                          sizeof(generation)))) {
                break;
            }
            continue;
        }
        if (request == kRequestTable) {
            const std::string payload = BuildTable();
            const uint32_t length = static_cast<uint32_t>(payload.size());
            std::string framed(reinterpret_cast<const char *>(&length), sizeof(length));
            framed += payload;
            if (!WriteAll(fd, framed)) break;
            LOGI("AevixHyperRuntime: published {} bytes to the spawner.", payload.size());
            continue;
        }
        LOGD("AevixHyperRuntime: ignoring request {}.", static_cast<int>(request));
    }
    close(fd);
    return nullptr;
}

/**
 * @brief Rebuilds the lookup the spawned processes will read, if the daemon has published since.
 *
 * Runs in the spawner, and on the parent side of the fork, because that is the last moment at which
 * this process may still open a socket: what it produces is memory, and memory is what the fork
 * copies. The generation stamp is asked for first and is eight bytes, so the ordinary case -- a
 * fork with nothing republished since the last one -- costs one round trip and no payload.
 */
void RefreshIndex() {
    if (g_companion_fd < 0) return;

    const char request = kRequestGeneration;
    if (write(g_companion_fd, &request, 1) != 1) return;
    uint64_t generation = 0;
    if (!ReadAll(g_companion_fd, &generation, sizeof(generation))) return;
    if (generation == 0 || (generation & 1u) != 0) {
        LOGD("AevixHyperRuntime: the daemon has not finished publishing an index.");
        return;
    }
    if (g_index_loaded && generation == g_generation) return;

    const char fetch = kRequestTable;
    if (write(g_companion_fd, &fetch, 1) != 1) return;
    uint32_t length = 0;
    if (!ReadAll(g_companion_fd, &length, sizeof(length)) || length > kMaxTableLength) {
        LOGW("AevixHyperRuntime: the companion sent no usable table.");
        return;
    }
    std::string payload(length, '\0');
    if (!ReadAll(g_companion_fd, payload.data(), length)) return;

    // The daemon uses an odd/even generation seqlock around its multi-file publication. Refuse
    // the table if a writer overlapped this read, rather than accepting mixed old/new scopes.
    const uint64_t confirmed_generation = ReadGeneration();
    if (confirmed_generation != generation || (confirmed_generation & 1u) != 0) {
        LOGD("AevixHyperRuntime: the index changed while its table was being read; retry next fork.");
        return;
    }

    size_t pos = 0;
    uint32_t count = 0;
    if (!TakeU32(payload, pos, count) || count > 65536) {
        LOGW("AevixHyperRuntime: the companion table has an invalid record count.");
        return;
    }
    std::map<std::string, std::vector<LibraryEntry>> parsed;
    for (uint32_t i = 0; i < count; i++) {
        uint32_t key_length = 0;
        if (!TakeU32(payload, pos, key_length) || key_length == 0 || key_length > 255 ||
            key_length > payload.size() - pos) return;
        std::string filename = payload.substr(pos, key_length);
        pos += key_length;
        std::string process_name;
        uid_t scope_uid = 0;
        if (!ParseScopeFilename(filename, process_name, scope_uid)) return;

        uint32_t content_length = 0;
        if (!TakeU32(payload, pos, content_length) || content_length > (1u << 20) ||
            content_length > payload.size() - pos) {
            return;
        }
        parsed[MakeScopeKey(process_name, scope_uid)] = ParseIndex(payload.substr(pos, content_length));
        pos += content_length;
    }
    if (pos != payload.size()) {
        LOGW("AevixHyperRuntime: the companion table has trailing bytes; ignoring it.");
        return;
    }

    g_index = std::move(parsed);
    g_generation = generation;
    g_index_loaded = true;
    LOGI("AevixHyperRuntime: holding {} process/UID scope(s).", g_index.size());
}

/// Runs in the parent, before the fork that creates the next application process.
void PrepareIndex() {
    if (g_registered) RefreshIndex();
}

void PrepareFork() {
    // The loader hook runs in the spawner too. Take its registry lock before fork so the child
    // cannot inherit it owned by a vanished loader thread.
    if (g_native_api_ready) NativeAPIAtForkPrepare();
    PrepareIndex();
}

void ParentAfterFork() {
    if (g_native_api_ready) NativeAPIAtForkParent();
}

void ChildAfterFork() {
    if (g_native_api_ready) NativeAPIAtForkChild();
}

void LoadModuleLibraries(const std::vector<LibraryEntry> &entries) {
    // The loader hook is installed in hyos_spawner before it forks. That process is still
    // single-threaded here, so its mutex and hook state are safe to inherit into app children.
    const NativeAPIEntries *api_entries = GetNativeAPIEntries();
    if (api_entries == nullptr) {
        LOGE("AevixHyperRuntime: the native API entries could not be built; not loading modules.");
        return;
    }
    if (!g_native_api_ready) {
        LOGW("AevixHyperRuntime: loader interception is unavailable; native_init will be called "
             "directly for each scoped library.");
    }

    for (const auto &entry : entries) {
        if (g_native_api_ready) RegisterNativeLib(entry.library_name);

        void *handle = dlopen(entry.library_path.c_str(), RTLD_NOW);
        if (handle == nullptr) {
            LOGE("AevixHyperRuntime: cannot load {} for '{}': {}.", entry.library_path.c_str(),
                 entry.module_package.c_str(), dlerror());
            continue;
        }
        LOGI("AevixHyperRuntime: loaded {} for '{}'.", entry.library_path.c_str(),
             entry.module_package.c_str());

        if (g_native_api_ready) continue;
        void *init_sym = dlsym(handle, "native_init");
        if (init_sym == nullptr) {
            LOGW("AevixHyperRuntime: {} does not export native_init.", entry.library_path.c_str());
            continue;
        }
        reinterpret_cast<NativeInit>(init_sym)(api_entries);
    }
}

}  // namespace

/**
 * @brief Called once per specialized application process, after its uid, gid, groups and SELinux
 *        context have been applied.
 *
 * This runs in a process forked from a possibly multithreaded parent, so it does no I/O and starts
 * no threads: the answer was fetched by the spawner before the fork and is already in memory, and
 * all this does with it is look the process up and dlopen what it names. Nothing here reaches for a
 * file or a socket, which is deliberate -- a lock the parent held at fork time is held forever in
 * the child, and the launch would hang on it with nothing to report.
 */
void OnAppSpecialized(const ZnHyosAppSpecializeArgs *args) {
    if (!g_registered || g_specialized) return;
    if (args == nullptr || args->package_name == nullptr || args->process_name == nullptr) {
        LOGE("AevixHyperRuntime: specialization arrived without a process or package name.");
        return;
    }
    // UID is applied before this callback (per the Zygisk Next Runtime API contract), and is
    // required to distinguish identical package/process names in different Android users.
    const uid_t uid = getuid();
    g_specialized = true;

    LOGI("AevixHyperRuntime: specializing process '{}' (package '{}', uid {}).", args->process_name,
         args->package_name, static_cast<unsigned int>(uid));

    // The process name first, because that is what a scope is keyed by and what this process
    // actually is: a package with more than one process has a different scope in each. The package
    // name is the fallback, for a runtime that gave us a name we cannot use -- an inherited one, or
    // one truncated to the kernel's fifteen characters -- and it always resolves to the main
    // process, which is the more useful of the two wrong answers.
    const std::vector<LibraryEntry> *entries = nullptr;
    const auto process_scope = g_index.find(MakeScopeKey(args->process_name, uid));
    if (process_scope != g_index.end()) entries = &process_scope->second;
    if (entries == nullptr) {
        const auto package_scope = g_index.find(MakeScopeKey(args->package_name, uid));
        if (package_scope != g_index.end()) entries = &package_scope->second;
    }
    if (entries == nullptr || entries->empty()) {
        LOGD("AevixHyperRuntime: '{}' is in no module's scope.", args->package_name);
        return;
    }

    LOGI("AevixHyperRuntime: '{}' has {} module librar{} to load.", args->package_name,
         entries->size(), entries->size() == 1 ? "y" : "ies");
    LoadModuleLibraries(*entries);
}

/**
 * @brief Answers the daemon's question about this runtime.
 *
 * Started with the companion rather than with the first connection, because "started and nothing
 * registered yet" has to be answerable: it is what a runtime the loader refused looks like, and the
 * difference between that and no companion at all is the whole reason the daemon asks.
 */
void *ServeMonitor(void *) {
    // A socket left behind by a companion that was killed would make every later bind fail, and the
    // daemon would keep reading the old inode instead of ours.
    unlink(kMonitorPath);

    const int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) {
        LOGE("AevixHyperRuntime: cannot create {}: {}.", kMonitorPath, strerror(errno));
        return nullptr;
    }

    struct sockaddr_un address {};
    address.sun_family = AF_UNIX;
    strlcpy(address.sun_path, kMonitorPath, sizeof(address.sun_path));
    if (bind(listener, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0 ||
        listen(listener, 8) != 0) {
        LOGE("AevixHyperRuntime: cannot listen on {}: {}.", kMonitorPath, strerror(errno));
        close(listener);
        return nullptr;
    }
    // The daemon runs as root and the directory is root-only, so the mode is not what admits it;
    // it is set anyway so that a stale socket is never what refuses a legitimate reader.
    if (chmod(kMonitorPath, 0666) != 0) {
        LOGW("AevixHyperRuntime: cannot relax {}: {}.", kMonitorPath, strerror(errno));
    }
    LOGI("AevixHyperRuntime: reporting injection status on {}.", kMonitorPath);

    for (;;) {
        const int client = accept(listener, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            break;
        }
        const char status = g_injection_status.load();
        if (write(client, &status, 1) != 1) {
            LOGD("AevixHyperRuntime: a monitor client went away before its answer.");
        }
        close(client);
    }
    close(listener);
    return nullptr;
}

void OnCompanionLoaded() {
    LOGI("AevixHyperRuntime: companion loaded in pid {}.", static_cast<int>(getpid()));
    pthread_t thread;
    if (pthread_create(&thread, nullptr, ServeMonitor, nullptr) != 0) {
        LOGE("AevixHyperRuntime: cannot serve {} on a thread; the daemon will see no runtime.",
             kMonitorPath);
        return;
    }
    pthread_detach(thread);
}

void OnModuleConnected(int fd) {
    LOGI("AevixHyperRuntime: companion connected on fd {}.", fd);

    // The spawner's first write is its status byte, and it is written before any fork, so it is
    // already on its way when this runs. Bounded anyway: a spawner that died between connecting and
    // writing must not leave the companion -- and the loader's own loop that called us -- stuck.
    struct timeval timeout {};
    timeout.tv_sec = kStatusByteTimeoutSeconds;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    char status = 0;
    if (read(fd, &status, 1) == 1 && status == 1) {
        g_injection_status.store(1);
        LOGI("AevixHyperRuntime: the runtime API is registered; the daemon will be told so.");
    } else {
        g_injection_status.store(0);
        LOGW("AevixHyperRuntime: no registration status arrived; the daemon will be told that "
             "injection is not working.");
    }

    pthread_t thread;
    auto *argument = reinterpret_cast<void *>(static_cast<intptr_t>(fd));
    if (pthread_create(&thread, nullptr, ServeCompanion, argument) == 0) {
        pthread_detach(thread);
        return;
    }
    LOGE("AevixHyperRuntime: cannot serve the companion on a thread; serving inline.");
    ServeCompanion(argument);
}

void OnModuleLoaded(void *self_handle, const ZygiskNextAPI *api) {
    if (api == nullptr) {
        LOGW("AevixHyperRuntime: the loader handed us no API; disabled in this process.");
        return;
    }
    g_api = api;

    // Every refusal below is a warning, not an error. The Runtime API is an optional part of the
    // loader: an older build does not have it, one that does can still say no, and in both cases
    // the right outcome is the same -- nothing of ours runs here, and the process carries on
    // exactly as it would without this feature. Saying so at warning level is the whole report.
    const ZygiskNextRuntime *runtime = api->getRuntime ? api->getRuntime() : nullptr;
    if (runtime == nullptr) {
        LOGW("AevixHyperRuntime: this loader offers no Runtime API; disabled in this process.");
        return;
    }
    if (runtime->type != ZN_RUNTIME_HYOS) {
        LOGW("AevixHyperRuntime: runtime type {} is not the HyperOS one; disabled in this process.",
             static_cast<int>(runtime->type));
        return;
    }
    if (runtime->api_version < ZYGISK_NEXT_HYOS_API_VERSION) {
        LOGW("AevixHyperRuntime: the runtime speaks API {}, we need {}; disabled in this process.",
             runtime->api_version, ZYGISK_NEXT_HYOS_API_VERSION);
        return;
    }
    if (runtime->registerModule == nullptr) {
        LOGW("AevixHyperRuntime: the runtime cannot register modules; disabled in this process.");
        return;
    }

    // Use Zygisk Next's own hook engine before installing the loader hook and before handing the
    // Native API table to modules. Both callbacks have the exact HookFunType ABI used by Aevix.
    if (api->inlineHook == nullptr || api->inlineUnhook == nullptr) {
        SetExternalInlineHookBackend(nullptr, nullptr);
        LOGW("AevixHyperRuntime: the runtime offers no complete inline-hook pair; using Aevix's "
             "configured backend.");
    } else {
        SetExternalInlineHookBackend(api->inlineHook, api->inlineUnhook);
        LOGI("AevixHyperRuntime: using Zygisk Next inline-hook backend.");
    }

    static const ZygiskNextHyosModule hyos_module = {
        .target_api_version = ZYGISK_NEXT_HYOS_API_VERSION,
        .onAppSpecialized = OnAppSpecialized,
    };
    if (runtime->registerModule(&hyos_module) != ZN_SUCCESS) {
        LOGW("AevixHyperRuntime: the runtime refused our specialization callback; disabled in this "
             "process.");
        return;
    }
    g_registered = true;
    LOGI("AevixHyperRuntime: registered; applications spawned here will be specialized.");

    // Installed here, in the spawner, and inherited by every process it forks. Here rather than in
    // the child is the point: this runs before main, on one thread, with nothing else in the
    // process to race against and no other thread's lock for the fork to have caught mid-hook.
    // Only worth doing now that a callback is coming -- without one, nothing would ever load a
    // module library, and patching a system process's loader for that is a change with no purpose.
    // Install the dlopen interception in the spawner before main() and before any fork. The
    // loader hook and its registry mutex are therefore inherited in an unlocked, single-threaded
    // state by every specialized application process.
    g_native_api_ready = InitializeNativeAPI(lsplant::InitInfo{
        .inline_hooker =
            [](void *target, void *replacement) {
                void *backup = nullptr;
                return HookInline(target, replacement, &backup) == 0 ? backup : nullptr;
            },
        .art_symbol_resolver =
            [](auto symbol) { return ElfSymbolCache::GetLinker()->getSymbAddress(symbol); },
    });
    if (!g_native_api_ready) {
        LOGW("AevixHyperRuntime: native loader interception failed; scoped libraries will use "
             "direct native_init invocation.");
    }

    // One connection, made now and kept for the life of the process. The spawner is its only
    // client -- a child never touches it -- so the exchange on it needs no correlation and no
    // protection against replies crossing.
    if (api->connectCompanion == nullptr) {
        LOGW("AevixHyperRuntime: the loader offers no companion connection; applications spawned "
             "here will run unhooked.");
        return;
    }
    g_companion_fd = api->connectCompanion(self_handle);
    if (g_companion_fd < 0) {
        LOGW("AevixHyperRuntime: no companion, so the target list cannot be obtained; applications "
             "spawned here will run unhooked.");
    } else {
        // Set here rather than in the child, because it is a property of the connection every child
        // shares and setting it once is enough. The child's read is on the application's startup
        // path; without a bound, a companion that stopped answering would hold the launch open.
        struct timeval timeout{};
        timeout.tv_sec = kCompanionTimeoutSeconds;
        if (setsockopt(g_companion_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
            LOGW("AevixHyperRuntime: cannot bound the companion reply: {}.", strerror(errno));
        }
        LOGI("AevixHyperRuntime: companion connection established on fd {}.", g_companion_fd);

        // The companion's first read is this byte, before it serves anything else, so it has to
        // arrive before the first fork -- which it does, because it is written here and children
        // only exist once the spawner's main runs. It is the answer the daemon will be handed when
        // it asks whether this runtime is being injected at all.
        const char status = g_registered ? 1 : 0;
        if (write(g_companion_fd, &status, 1) != 1) {
            LOGW("AevixHyperRuntime: cannot report the injection status to the companion: {}.",
                 strerror(errno));
        }

        // The table is fetched now so that the first fork has something to hand down, and again on
        // the parent side of every later fork. The hook is registered here rather than in the child
        // for the same reason the loader hook is: this runs before main, on one thread.
        if (pthread_atfork(PrepareFork, ParentAfterFork, ChildAfterFork) != 0) {
        LOGW("AevixHyperRuntime: cannot register fork synchronization; disabling loader "
             "interception and using direct native_init calls.");
        g_native_api_ready = false;
    }
        RefreshIndex();
    }
}

}  // namespace vector::native::hyos

// =========================================================================================
// Zygisk Next module registration
// =========================================================================================
//
// Both structures are looked up by name in this library when Zygisk Next injects it into
// /system_ext/bin/hyos_spawner, which is what zn_modules.txt asks for. The ordinary Zygisk entry
// point above (REGISTER_ZYGISK_MODULE in module.cpp) is untouched: a library exports as many entry
// points as the loaders loading it need, and the two never run in the same process.

extern "C" __attribute__((visibility("default"))) ZygiskNextModule zn_module = {
    .target_api_version = ZYGISK_NEXT_API_VERSION,
    .onModuleLoaded = vector::native::hyos::OnModuleLoaded,
};

extern "C" __attribute__((visibility("default"))) ZygiskNextCompanionModule zn_companion_module = {
    .target_api_version = ZYGISK_NEXT_API_VERSION,
    .onCompanionLoaded = vector::native::hyos::OnCompanionLoaded,
    .onModuleConnected = vector::native::hyos::OnModuleConnected,
};
