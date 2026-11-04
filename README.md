# Aegis Engine v1.0

Motor de virtualización de aplicaciones a nivel de aplicación (*app cloner*) para **Android 13+ (API 33+)** orientado a desarrolladores que necesitan ejecutar aplicaciones y juegos en un contenedor aislado (`:p0`, `:p1`) e inspeccionar en tiempo real el inventario de librerías nativas (`.so`) mapeadas en `/proc/self/maps`.

---

## 1. Arquitectura y Estructura del Repositorio

- **`/app`**: Interfaz de usuario en **Jetpack Compose (Material 3)** siguiendo estrictamente los tokens de diseño de la Sección 5 (`AegisTheme`, `HomeScreen`, `AddAppScreen`, `ResultScreen`, `CloneProgressDialog`) y gestión de estado reactivo con `AegisViewModel` (`StateFlow<UiState>`).
- **`/virtual-core`**: Motor de virtualización modular e inspección nativa:
  - `VirtualEngineHost`: Gestiona el contenedor aislado (`/files/virtual_env/<package>`), extrae librerías `.so` del APK base y *split APKs* (`split_config.arm64_v8a.apk`, etc.), verifica compatibilidad de ABI y coordina el lanzamiento de procesos stub.
  - `StubProcessServiceP0` / `StubProcessServiceP1` y `StubProcessActivityP0` / `StubProcessActivityP1`: Componentes ejecutándose en procesos aislados (`:p0`, `:p1`).
  - `GuestRuntimeEngine`: Inicializa el `DexClassLoader` del paquete clonado con su directorio de librerías nativas, redirige las rutas de datos (`VirtualGuestContext`), adjunta la clase `Application` del guest e invoca `NativeGuestBridge`.
  - `AegisReportProvider`: Canal IPC `ContentProvider` por el cual el proceso clonado (`:p0`) transmite el reporte JSON validado al proceso principal del host.
- **`/virtual-core/src/main/cpp`**:
  - `CMakeLists.txt` y `aegis_guest.cpp`: Librería nativa `libaegis_guest.so` en C++17 compilada con el flag obligatorio de alineación de página de 16 KB (`-Wl,-z,max-page-size=16384`). Carga con `System.loadLibrary("aegis_guest")`, mapea las librerías del contenedor mediante `dlopen` y parsea `/proc/self/maps`.
- **`/.github/workflows/build.yml`**: Pipeline de integración continua con JDK 17, NDK 27, generación de APK y verificación de alineamiento ELF de 16 KB.
- **`THIRD_PARTY_LICENSES.md`**: Análisis de licencias (VirtualApp, SandVXposed, NewBlackbox vs. `:virtual-core` Apache 2.0) y avisos de terceros.

---

## 2. Contrato de Datos JSON (`GuestMapsReport`)

El botón **"Copiar JSON"** en la pantalla de resultado exporta exactamente el contrato especificado en la Sección 6.4:

```json
{
  "ok": true,
  "pid": 14820,
  "packageName": "com.dev.migame",
  "libs": [
    "/data/user/0/com.aistudio.aegisengine.vcore/files/virtual_env/com.dev.migame/lib/libgame.so",
    "/data/app/.../lib/arm64/libaegis_guest.so",
    "/apex/com.android.runtime/lib64/bionic/libc.so"
  ],
  "error": null
}
```

- **Semáforo VERDE (`ok = true`)**: `libaegis_guest.so` abrió `/proc/self/maps` en el proceso clonado (`:p0` / `:p1`) y extrajo al menos una ruta `.so`.
- **Semáforo ROJO (`ok = false`)**: El motor no pudo abrir `/proc/self/maps` (por ejemplo, `EACCES` por política SELinux), la lista de `.so` resultó vacía o la ABI del paquete es incompatible.

---

## 3. Informe de Estado, Compatibilidad y Limitaciones Documentadas

1. **Qué funciona y está verificado**:
   - Compilación completa con Gradle KTS, Version Catalog y CMake/NDK (`libaegis_guest.so` generada para `arm64-v8a`, `x86_64` y `armeabi-v7a` con alineación de segmentos `LOAD` a `0x4000` / 16 KB).
   - Descubrimiento de aplicaciones instaladas mediante `<queries>` (`ACTION_MAIN` + `CATEGORY_LAUNCHER`) sin requerir `QUERY_ALL_PACKAGES` y excluyendo automáticamente a Aegis Engine.
   - Clonado en contenedor aislado, extracción de librerías `.so` desde APK base y *split APKs*, arranque en proceso stub (`:p0` / `:p1`), lectura nativa de `/proc/self/maps` desde `libaegis_guest.so` y envío del reporte JSON al host vía `AegisReportProvider`.
   - Pantallas Compose fieles al diseño (Inicio con cuadrícula de 3 columnas y botón punteado `+`, Añadir app con cuadrícula de 4 columnas y búsqueda en píldora, Resultado Verde y Resultado Rojo con copia de JSON, copia individual de rutas `.so` y visor de `logcat`).
2. **Incompatibilidades conocidas (No objetivos en v1)**:
   - Apps con DRM estricto, anti-cheat kernel/userland o Google Play Integrity API que validan la firma del proceso anfitrión o el instalador de origen se documentan como no compatibles (Aegis Engine no elude protecciones de terceros ni oculta su presencia).
   - Apps de sistema que requieren UID `system` (`android.uid.system`) para inicializar su `Application` se inspeccionan a nivel de librerías `.so` en el contenedor `:p0` registrando el aviso correspondiente en `AegisGuest` sin bloquear la lectura de `/proc/self/maps`.
