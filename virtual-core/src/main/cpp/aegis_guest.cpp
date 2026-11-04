#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <signal.h>
#include <string>
#include <vector>
#include <set>
#include <sstream>
#include <mutex>
#include <unordered_map>

#define LOG_TAG "AegisGuest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_vfsMutex;
std::string g_targetPackage;
std::string g_sandboxRoot;
std::string g_guestNativeLibDir;

// Registro de eventos de resolución de rutas y diagnóstico
struct RedirectionEvent {
    std::string logicalPath;
    std::string resolvedPath;
    std::string operation;
    bool exists;
};

std::vector<RedirectionEvent> g_eventHistory;
const size_t MAX_HISTORY_EVENTS = 50;

// Estructuras para manejo de señales en Sandbox
struct sigaction g_oldSegv;
struct sigaction g_oldAbrt;
struct sigaction g_oldBus;
struct sigaction g_oldIll;
struct sigaction g_oldFpe;
bool g_crashHandlerInstalled = false;

void nativeCrashSignalHandler(int sig, siginfo_t* info, void* context) {
    const char* sigName = "UNKNOWN";
    switch (sig) {
        case SIGSEGV: sigName = "SIGSEGV (Segmentation Fault)"; break;
        case SIGABRT: sigName = "SIGABRT (Abort)"; break;
        case SIGBUS:  sigName = "SIGBUS (Bus Error)"; break;
        case SIGILL:  sigName = "SIGILL (Illegal Instruction)"; break;
        case SIGFPE:  sigName = "SIGFPE (Floating Point Exception)"; break;
    }

    void* faultAddr = info ? info->si_addr : nullptr;
    pid_t pid = getpid();
    LOGE("CRASH DETECTADO EN PROCESO STUB (PID=%d, SEÑAL=%d: %s, ADDR=%p)", pid, sig, sigName, faultAddr);
    LOGE("Aegis Crash Sandbox aislando fallo: terminando únicamente el proceso stub :p0/:p1 para proteger Aegis Host.");

    // Terminar inmediatamente el proceso stub con _exit para no arrastrar al proceso anfitrión
    _exit(128 + sig);
}

std::string escapeJsonString(const std::string& input) {
    std::ostringstream ss;
    for (char c : input) {
        switch (c) {
            case '"': ss << "\\\""; break;
            case '\\': ss << "\\\\"; break;
            case '\b': ss << "\\b"; break;
            case '\f': ss << "\\f"; break;
            case '\n': ss << "\\n"; break;
            case '\r': ss << "\\r"; break;
            case '\t': ss << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    ss << buf;
                } else {
                    ss << c;
                }
        }
    }
    return ss.str();
}

bool endsWithSo(const std::string& path) {
    if (path.length() < 3) return false;
    if (path.compare(path.length() - 3, 3, ".so") == 0) {
        return true;
    }
    size_t pos = path.find(".so");
    if (pos != std::string::npos) {
        if (pos + 3 == path.length() || path[pos + 3] == '.') {
            return true;
        }
    }
    return false;
}

std::string errnoToCodeName(int err) {
    switch (err) {
        case EACCES: return "EACCES (Permiso denegado / SELinux)";
        case EPERM: return "EPERM (Operación no permitida)";
        case ENOENT: return "ENOENT (/proc/self/maps no existe)";
        case ENOMEM: return "ENOMEM (Memoria insuficiente)";
        default: {
            std::ostringstream oss;
            oss << "ERRNO_" << err << " (" << strerror(err) << ")";
            return oss.str();
        }
    }
}

// Resolución explícita y cooperativa entre ruta lógica y física del sandbox
std::string resolveLogicalPath(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_vfsMutex);
    if (g_sandboxRoot.empty() || g_targetPackage.empty()) {
        return path;
    }

    std::string targetPatternData = "/data/data/" + g_targetPackage;
    std::string targetPatternUser = "/data/user/0/" + g_targetPackage;

    if (path.rfind(targetPatternData, 0) == 0) {
        return g_sandboxRoot + path.substr(targetPatternData.length());
    }
    if (path.rfind(targetPatternUser, 0) == 0) {
        return g_sandboxRoot + path.substr(targetPatternUser.length());
    }
    return path;
}

void recordEvent(const std::string& op, const std::string& logical, const std::string& resolved, bool exists) {
    std::lock_guard<std::mutex> lock(g_vfsMutex);
    if (g_eventHistory.size() >= MAX_HISTORY_EVENTS) {
        g_eventHistory.erase(g_eventHistory.begin());
    }
    g_eventHistory.push_back({logical, resolved, op, exists});
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("libaegis_guest.so inicializada en proceso PID=%d (Modo cooperativo e instrumentación verificable)", getpid());
    return JNI_VERSION_1_6;
}

/**
 * Instalación del Crash Handler nativo (SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE).
 * Asegura que cualquier fallo o aborto nativo de la app clonada sea contenido en su propio
 * proceso (:p0 o :p1) y no arrastre ni bloquee al proceso principal de Aegis Engine.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_aegis_virtualcore_NativeGuestBridge_nativeInstallCrashHandler(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    if (g_crashHandlerInstalled) {
        return;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = nativeCrashSignalHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, &g_oldSegv);
    sigaction(SIGABRT, &sa, &g_oldAbrt);
    sigaction(SIGBUS,  &sa, &g_oldBus);
    sigaction(SIGILL,  &sa, &g_oldIll);
    sigaction(SIGFPE,  &sa, &g_oldFpe);

    g_crashHandlerInstalled = true;
    LOGI("Aegis Crash Sandbox nativo instalado correctamente en PID=%d", getpid());
}

/**
 * Configuración explícita del VFS Cooperativo:
 * Mapeo entre las rutas lógicas del paquete huésped (/data/data/<pkg>) y la ruta física del sandbox de Aegis.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_aegis_virtualcore_NativeGuestBridge_nativeConfigureVfsMapping(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring packageNameJstr,
        jstring sandboxRootJstr,
        jstring guestNativeLibDirJstr) {
    std::lock_guard<std::mutex> lock(g_vfsMutex);
    if (packageNameJstr != nullptr) {
        const char* cPkg = env->GetStringUTFChars(packageNameJstr, nullptr);
        g_targetPackage = cPkg ? cPkg : "";
        env->ReleaseStringUTFChars(packageNameJstr, cPkg);
    }
    if (sandboxRootJstr != nullptr) {
        const char* cRoot = env->GetStringUTFChars(sandboxRootJstr, nullptr);
        g_sandboxRoot = cRoot ? cRoot : "";
        env->ReleaseStringUTFChars(sandboxRootJstr, cRoot);
    }
    if (guestNativeLibDirJstr != nullptr) {
        const char* cLib = env->GetStringUTFChars(guestNativeLibDirJstr, nullptr);
        g_guestNativeLibDir = cLib ? cLib : "";
        env->ReleaseStringUTFChars(guestNativeLibDirJstr, cLib);
    }
    LOGI("VFS cooperativo configurado: paquete=%s -> sandbox=%s", g_targetPackage.c_str(), g_sandboxRoot.c_str());
}

/**
 * Adaptador cooperativo para File APIs:
 * Resuelve la ruta lógica a la ruta real del sandbox de forma transparente y ejecuta compatibilidad
 * con fopen, access y stat, registrando la operación en el diagnóstico.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_aegis_virtualcore_NativeGuestBridge_nativeResolveSandboxPath(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring logicalPathJstr) {
    if (logicalPathJstr == nullptr) return nullptr;
    const char* cPath = env->GetStringUTFChars(logicalPathJstr, nullptr);
    std::string logical = cPath ? cPath : "";
    env->ReleaseStringUTFChars(logicalPathJstr, cPath);

    std::string resolved = resolveLogicalPath(logical);
    struct stat st;
    bool exists = (stat(resolved.c_str(), &st) == 0);
    recordEvent("RESOLVE", logical, resolved, exists);

    return env->NewStringUTF(resolved.c_str());
}

/**
 * Inspección y validación de permisos en sandbox mediante access(2) y stat(2) sin ocultar instrumentación.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aistudio_aegis_virtualcore_NativeGuestBridge_nativeCheckSandboxFileAccess(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring pathJstr,
        jint mode) {
    if (pathJstr == nullptr) return JNI_FALSE;
    const char* cPath = env->GetStringUTFChars(pathJstr, nullptr);
    std::string logical = cPath ? cPath : "";
    env->ReleaseStringUTFChars(pathJstr, cPath);

    std::string resolved = resolveLogicalPath(logical);
    int res = access(resolved.c_str(), mode);
    bool ok = (res == 0);
    recordEvent("ACCESS", logical, resolved, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Obtiene el historial de redirecciones y diagnósticos VFS en formato JSON.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_aegis_virtualcore_NativeGuestBridge_nativeGetVfsDiagnosticLogs(
        JNIEnv* env,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_vfsMutex);
    std::ostringstream ss;
    ss << "[\n";
    for (size_t i = 0; i < g_eventHistory.size(); ++i) {
        const auto& ev = g_eventHistory[i];
        ss << "  {"
           << "\"op\": \"" << escapeJsonString(ev.operation) << "\", "
           << "\"logical\": \"" << escapeJsonString(ev.logicalPath) << "\", "
           << "\"resolved\": \"" << escapeJsonString(ev.resolvedPath) << "\", "
           << "\"exists\": " << (ev.exists ? "true" : "false")
           << "}";
        if (i + 1 < g_eventHistory.size()) ss << ",";
        ss << "\n";
    }
    ss << "]";
    return env->NewStringUTF(ss.str().c_str());
}

/**
 * Carga explícita mediante dlopen de la librería nativa (.so) del contenedor virtual.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_aegis_virtualcore_NativeGuestBridge_nativeDlopenLibrary(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring libPathJstr) {
    if (libPathJstr == nullptr) {
        return env->NewStringUTF("NULL_PATH");
    }
    const char* libPath = env->GetStringUTFChars(libPathJstr, nullptr);
    if (libPath == nullptr) {
        return env->NewStringUTF("OOM_STRING");
    }

    dlerror();
    void* handle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
    std::string result;
    if (handle != nullptr) {
        LOGI("Guest SO cargada en memoria: %s", libPath);
        result = "OK";
    } else {
        const char* err = dlerror();
        std::string errMsg = err ? err : "dlopen unknown error";
        LOGW("Aviso al cargar SO con RTLD_NOW (%s): %s. Reintentando con RTLD_LAZY...", libPath, errMsg.c_str());
        dlerror();
        handle = dlopen(libPath, RTLD_LAZY | RTLD_LOCAL);
        if (handle != nullptr) {
            LOGI("Guest SO cargada con RTLD_LAZY: %s", libPath);
            result = "OK";
        } else {
            const char* lazyErr = dlerror();
            result = lazyErr ? lazyErr : errMsg;
            LOGE("No se pudo mapear %s: %s", libPath, result.c_str());
        }
    }

    env->ReleaseStringUTFChars(libPathJstr, libPath);
    return env->NewStringUTF(result.c_str());
}

/**
 * Lee /proc/self/maps del proceso actual (:p0 / :p1), filtra las rutas .so únicas
 * CON IDENTIFICACIÓN VERIFICABLE (sin ocultación de instrumentación ni supresión de Aegis).
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_aegis_virtualcore_NativeGuestBridge_nativeReadProcSelfMapsJson(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring packageNameJstr,
        jboolean forceFailSimulation) {
    const int currentPid = static_cast<int>(getpid());
    std::string pkgName = "unknown";
    if (packageNameJstr != nullptr) {
        const char* chars = env->GetStringUTFChars(packageNameJstr, nullptr);
        if (chars != nullptr) {
            pkgName = chars;
            env->ReleaseStringUTFChars(packageNameJstr, chars);
        }
    }

    LOGI("Iniciando inspección verificable de /proc/self/maps para paquete=%s en PID=%d", pkgName.c_str(), currentPid);

    if (forceFailSimulation) {
        LOGE("Fallo de acceso a /proc/self/maps (EACCES: Permission denied by SELinux policy)");
        std::ostringstream errJson;
        errJson << "{\n"
                << "  \"ok\": false,\n"
                << "  \"pid\": " << currentPid << ",\n"
                << "  \"packageName\": \"" << escapeJsonString(pkgName) << "\",\n"
                << "  \"libs\": [],\n"
                << "  \"error\": \"EACCES (SELinux policy blocked /proc/self/maps access)\"\n"
                << "}";
        return env->NewStringUTF(errJson.str().c_str());
    }

    FILE* fp = fopen("/proc/self/maps", "r");
    if (fp == nullptr) {
        int err = errno;
        std::string reason = errnoToCodeName(err);
        LOGE("Error al abrir /proc/self/maps en PID=%d: %s", currentPid, reason.c_str());
        std::ostringstream errJson;
        errJson << "{\n"
                << "  \"ok\": false,\n"
                << "  \"pid\": " << currentPid << ",\n"
                << "  \"packageName\": \"" << escapeJsonString(pkgName) << "\",\n"
                << "  \"libs\": [],\n"
                << "  \"error\": \"" << escapeJsonString(reason) << "\"\n"
                << "}";
        return env->NewStringUTF(errJson.str().c_str());
    }

    std::set<std::string> seenLibs;
    std::vector<std::string> orderedLibs;
    char line[2048];

    while (fgets(line, sizeof(line), fp) != nullptr) {
        std::string rawLine(line);
        while (!rawLine.empty() && (rawLine.back() == '\n' || rawLine.back() == '\r')) {
            rawLine.pop_back();
        }

        size_t slashPos = rawLine.find('/');
        if (slashPos == std::string::npos) {
            continue;
        }

        std::string path = rawLine.substr(slashPos);
        const std::string deletedSuffix = " (deleted)";
        if (path.length() > deletedSuffix.length() &&
            path.compare(path.length() - deletedSuffix.length(), deletedSuffix.length(), deletedSuffix) == 0) {
            path = path.substr(0, path.length() - deletedSuffix.length());
        }

        if (endsWithSo(path)) {
            if (seenLibs.insert(path).second) {
                orderedLibs.push_back(path);
            }
        }
    }

    fclose(fp);

    if (orderedLibs.empty()) {
        LOGE("Lectura de /proc/self/maps completada en PID=%d pero no se encontraron librerías .so", currentPid);
        std::ostringstream emptyJson;
        emptyJson << "{\n"
                  << "  \"ok\": false,\n"
                  << "  \"pid\": " << currentPid << ",\n"
                  << "  \"packageName\": \"" << escapeJsonString(pkgName) << "\",\n"
                  << "  \"libs\": [],\n"
                  << "  \"error\": \"EMPTY_MAPS (Lista de librerías .so vacía en /proc/self/maps)\"\n"
                  << "}";
        return env->NewStringUTF(emptyJson.str().c_str());
    }

    std::vector<std::string> prioritizedLibs;
    std::vector<std::string> systemLibs;
    for (const auto& lib : orderedLibs) {
        if (lib.find("/data/") != std::string::npos || lib.find("libaegis_guest.so") != std::string::npos) {
            prioritizedLibs.push_back(lib);
        } else {
            systemLibs.push_back(lib);
        }
    }
    prioritizedLibs.insert(prioritizedLibs.end(), systemLibs.begin(), systemLibs.end());

    LOGI("Lectura verificable de /proc/self/maps en PID=%d: %zu librerías .so únicas detectadas (identidad Aegis preservada)",
         currentPid, prioritizedLibs.size());

    std::ostringstream outJson;
    outJson << "{\n"
            << "  \"ok\": true,\n"
            << "  \"pid\": " << currentPid << ",\n"
            << "  \"packageName\": \"" << escapeJsonString(pkgName) << "\",\n"
            << "  \"libs\": [\n";

    for (size_t i = 0; i < prioritizedLibs.size(); ++i) {
        outJson << "    \"" << escapeJsonString(prioritizedLibs[i]) << "\"";
        if (i + 1 < prioritizedLibs.size()) {
            outJson << ",";
        }
        outJson << "\n";
    }

    outJson << "  ],\n"
            << "  \"error\": null\n"
            << "}";

    return env->NewStringUTF(outJson.str().c_str());
}
