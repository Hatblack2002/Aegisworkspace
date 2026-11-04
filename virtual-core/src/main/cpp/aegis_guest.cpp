#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <set>
#include <sstream>

#define LOG_TAG "AegisGuest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

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
    // Also support versioned shared objects or .so inside split APKs if mapped
    size_t pos = path.find(".so");
    if (pos != std::string::npos) {
        // Check if ".so" is at the end or followed by ".1", etc.
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

} // namespace

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("libaegis_guest.so inicializada en proceso PID=%d", getpid());
    return JNI_VERSION_1_6;
}

/**
 * Carga explícitamente una librería nativa (.so) extraída del contenedor virtual
 * dentro del espacio de memoria del proceso clonado (:p0 / :p1) usando dlopen(),
 * para que quede mapeada en /proc/self/maps incluso si la Activity de la app clonada
 * difiere su carga.
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

    dlerror(); // Clear any existing error
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
 * Lee /proc/self/maps del proceso actual (:p0 / :p1), filtra las rutas .so únicas,
 * y devuelve el contrato JSON exacto requerido por la especificación:
 * {
 *   "ok": true,
 *   "pid": 12345,
 *   "packageName": "com.dev.migame",
 *   "libs": ["/data/app/.../libgame.so", "/system/lib64/libc.so"],
 *   "error": null
 * }
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

    LOGI("Iniciando inspección de /proc/self/maps para paquete=%s en PID=%d", pkgName.c_str(), currentPid);

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
        // Formato de /proc/self/maps:
        // 7b2c400000-7b2c458000 r-xp 00000000 fe:09 12345   /system/lib64/libc.so
        std::string rawLine(line);
        // Eliminar salto de línea final
        while (!rawLine.empty() && (rawLine.back() == '\n' || rawLine.back() == '\r')) {
            rawLine.pop_back();
        }

        size_t slashPos = rawLine.find('/');
        if (slashPos == std::string::npos) {
            continue;
        }

        std::string path = rawLine.substr(slashPos);
        // Limpiar sufijos como " (deleted)" si existen
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

    // Priorizar las librerías propias del contenedor / data / libaegis_guest al principio de la lista
    // para que el desarrollador vea inmediatamente las librerías de su app y libaegis_guest.so
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

    LOGI("Lectura exitosa de /proc/self/maps en PID=%d: %zu librerías .so únicas detectadas",
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
