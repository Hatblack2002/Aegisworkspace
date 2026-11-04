# Aegis Engine v1.0 – Motor de Virtualización e Inspección de Librerías Nativas

Aegis Engine es un motor de virtualización de aplicaciones a nivel de aplicación (*app cloner*) para Android 13+ que permite a desarrolladores clonar aplicaciones y juegos instalados en un contenedor aislado dentro del propio espacio de procesos de Aegis (`:p0`, `:p1`), ejecutarlos y observar en tiempo real el inventario de librerías nativas (`.so`) mapeadas en memoria mediante `/proc/self/maps`.

---

## Decisiones Confirmadas y Revisión del Usuario

> [!IMPORTANT]
> **Resumen de decisiones confirmadas en la fase de clarificación:**

- **Alcance del Proyecto**: Construir **Aegis Engine v1.0** siguiendo íntegramente la especificación técnica y de diseño adjunta (descartando las instrucciones del proyecto anterior en el prompt de sistema).
- **Arquitectura del Motor (`/virtual-core`)**: Implementar un módulo de virtualización propio y autocontenido en `:virtual-core` basado en procesos *stub* aislados (`:p0`, `:p1`), cargador dinámico (`DexClassLoader` / `PathClassLoader` con `Application` y `Activity` proxy/instrumentation), redirección de directorio de datos del contenedor e integración nativa con `libaegis_guest.so` compilada mediante CMake con soporte para páginas de 16 KB (`-Wl,-z,max-page-size=16384`).
- **Descubrimiento de Aplicaciones**: Utilizar `<queries>` en `AndroidManifest.xml` filtrando por `android.intent.action.MAIN` y `android.intent.category.LAUNCHER`, evitando el permiso restringido `QUERY_ALL_PACKAGES` y excluyendo el propio paquete de Aegis Engine.

---

## 1. Visión General y Concepto Principal

- **Qué hace**: Permite seleccionar cualquier aplicación o juego instalado en el dispositivo, instalarlo en un entorno virtual gestionado por Aegis Engine, lanzarlo en un proceso secundario (`:p0`), inyectar/cargar `libaegis_guest.so` en el arranque del proceso clonado para inspeccionar `/proc/self/maps`, y reportar al proceso host el listado exacto de librerías `.so` cargadas junto con el estado técnico del motor (Semáforo VERDE o ROJO).
- **Usuario objetivo**: Desarrolladores de Android que necesitan auditar y depurar el comportamiento en runtime de sus propias aplicaciones y motores de juegos (Unity, Unreal, SDL, NDK puro) sin modificar el APK original ni requerir root.
- **Valor clave**: Visibilidad inmediata y determinista de las dependencias nativas reales cargadas en memoria por un proceso clonado, exportable en un clic con un contrato JSON estricto, todo 100% en el dispositivo y sin servidores externos.

---

## 2. Experiencia de Usuario y Diseño Visual

### Flujos de Usuario Clave

1. **Pantalla 1 – Inicio (`HomeScreen`)**:
   - Cabecera azul (`#2563EB`) con esquinas inferiores redondeadas (`32.dp`), etiqueta superior `"AEGIS ENGINE"`, título `"Mis apps clonadas"` (`30.sp`) y subtítulo `"Ejecútalas en un espacio aislado"`.
   - Si no hay clones: tarjeta blanca con ilustración vectorial limpia, mensaje `"Aún no has clonado ninguna app"` y botón píldora `"Añadir app"`.
   - Con apps clonadas: tarjeta blanca con cuadrícula de 3 columnas (`AppGrid`), iconos reales de `64.dp` con radio del 30% (`19.dp`), nombre de la app y celda final `"Añadir"` con borde punteado azul (`#2563EB`) y símbolo `"+"`.
   - Interacciones: toque simple lanza el clon y abre el resultado; pulsación larga abre menú contextual con **Reejecutar**, **Ver resultado** y **Eliminar clon**.
2. **Pantalla 2 – Añadir App (`AddAppScreen`)**:
   - Cabecera azul con botón circular translúcido de retorno, título `"Añadir app"` (`22.sp`) y barra de búsqueda en píldora blanca integrada.
   - Cuadrícula de 4 columnas de apps instaladas en el dispositivo (icono real de `56.dp` + etiqueta). Al seleccionar una app, muestra insignia circular verde (`#16A34A`) con icono check.
   - Botón píldora inferior `"Clonar 1 app"` (deshabilitado cuando no hay selección).
3. **Diálogo / Estado de Progreso (`CloneProgressDialog`)**:
   - Modal claro y estructurado que transiciona por los pasos reales del motor: `"Instalando en el contenedor…"` → `"Iniciando proceso…"` → `"Leyendo librerías…"`.
   - Si ocurre un fallo (ej. ABI incompatible o cierre prematuro), presenta causa legible y botón `"Ver log"`.
4. **Pantalla 3 y 4 – Resultado (`ResultScreen` – Verde / Rojo)**:
   - Cabecera azul con botón volver y nombre de la app.
   - **Tarjeta de Semáforo (`StatusCard`)**:
     - **Estado VERDE (`ok = true`)**: Círculo verde de `80.dp` con icono check, etiqueta `"VERDE"` y subtítulo `"El motor leyó /proc/self/maps del proceso clonado"`. Acciones: `"Copiar JSON"` (principal) y `"Reejecutar"` (secundario con borde).
     - **Estado ROJO (`ok = false`)**: Círculo rojo de `80.dp` con icono X, etiqueta `"ROJO"` y subtítulo `"El motor no pudo leer /proc/self/maps"`. Acciones: `"Reintentar"` y `"Copiar log"`.
   - **Tarjeta de Librerías (`LibsCard`) / Log**: En estado verde, lista todas las rutas `.so` en tipografía monoespaciada (`11.sp`) con ajuste de línea; tocar cualquier ruta la copia al portapapeles. En estado rojo, muestra las últimas líneas de `logcat` filtradas por `Aegis` / `AegisGuest`.
   - **Tarjeta de Detalles (`DetailsCard`)**: Muestra PID, nombre de paquete, nombre del proceso stub (`:p0`), estado de error (`"ninguno"` o código POSIX como `EACCES`), expandible para inspección completa.

### Identidad Visual y Tokens de Diseño

| Token Semántico | Valor Hex | Aplicación en Componentes |
| :--- | :--- | :--- |
| `primary` | `#2563EB` | Cabecera curva, botones píldora principales, acentos activos |
| `onPrimary` | `#FFFFFF` | Texto e iconos sobre la cabecera y botones primarios |
| `primaryText` / Borde | `#1D4ED8` | Texto y borde de botones secundarios (`Reejecutar`, `Copiar log`) |
| `background` | `#F3F5FB` | Fondo general de todas las pantallas |
| `surface` | `#FFFFFF` | Tarjetas de contenido con radio `22.dp` y sombra suave |
| `onSurface` | `#0F172A` | Títulos de tarjetas, nombres de apps y valores principales |
| `onSurfaceVariant` | `#475569` | Subtítulos, contadores y etiquetas secundarias |
| `success` | `#16A34A` (texto `#15803D`) | Semáforo VERDE, insignia de selección de app |
| `error` | `#DC2626` (texto `#B91C1C`) | Semáforo ROJO, alertas de incompatibilidad o fallo de lectura |
| `outline` | `#2563EB` | Trazo punteado del botón `"+"` en la cuadrícula de inicio |

---

## 3. Decisiones Técnicas y Compromisos (Trade-Offs)

- **Decisión 1: Motor de Virtualización Modular en `:virtual-core` + CMake Nativo**
  - *Enfoque elegido*: Implementar el módulo `:virtual-core` con gestión de contenedores virtuales (copia/enlace de APK base y extracción de librerías nativas del guest al entorno aislado), ejecución en procesos stub dedicados (`:p0`, `:p1`), hooks de contexto/Binder (`PackageManager` / `ActivityThread` / `Instrumentation` proxy) y carga directa de `libaegis_guest.so` en el proceso stub.
  - *Por qué*: Los forks históricos como *NewBlackbox* o *VirtualApp* contienen miles de líneas con dependencias obsoletas de AGP 4/7 y fallan al compilar en NDK 27 / Android 13+ sin una reescritura profunda. Un motor modular en `:virtual-core` diseñado específicamente para Android 13 (API 33+) con soporte de ocultamiento de APIs (`AndroidHiddenApiBypass` / reflexión segura) y alineación de página de 16 KB garantiza compilación limpia, ejecución determinista del proceso clonado y lectura real de `/proc/self/maps`.
- **Decisión 2: Comunicación Guest → Host mediante `ContentProvider` Dedicado**
  - *Enfoque elegido*: El proceso clonado (`:p0`), tras ejecutar `libaegis_guest.so` y cargar las librerías del APK huésped, invoca `ContentResolver.call()` hacia `AegisReportProvider` en el proceso principal del host enviando el payload JSON validado.
  - *Por qué*: En Android 13+, `ContentProvider.call()` entre procesos de la misma firma/UID es síncrono, inmune a restricciones de background broadcasts y no requiere sockets abiertos ni permisos adicionales.

---

## 4. Arquitectura Técnica y Estrategia de Datos

### Diagrama de Arquitectura del Sistema

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        PROCESO HOST (Aegis UI)                          │
│                                                                         │
│  ┌──────────────────┐    StateFlow<UiState>    ┌─────────────────────┐  │
│  │ Jetpack Compose  │ ◄──────────────────────► │   AegisViewModel    │  │
│  │ (Home/Add/Result)│                          │  & CloneRepository  │  │
│  └──────────────────┘                          └──────────┬──────────┘  │
│                                                           │             │
│  ┌────────────────────────────────────────────────────────▼──────────┐  │
│  │               :virtual-core (VirtualEngineHost)                   │  │
│  │  • VirtualPackageInstaller (entorno aislado, extracción de .so)   │  │
│  │  • StubProcessLauncher (asigna y arranca proceso :p0 / :p1)       │  │
│  │  • AegisReportProvider (ContentProvider receptor de telemetría)   │  │
│  └───────────────────────────────┬───────────────────────────────────┘  │
└──────────────────────────────────┼──────────────────────────────────────┘
                                   │ Intent / Binder IPC (:p0)
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    PROCESO GUEST STUB (:p0 / :p1)                       │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │              StubProcessActivity / GuestRuntimeLoader             │  │
│  │  1. Redirige rutas de datos al sandbox de :virtual-core           │  │
│  │  2. Crea ClassLoader con APK + nativeLibraryDir del guest         │  │
│  │  3. Precarga librerías nativas del guest e inicializa Application │  │
│  │  4. Carga System.loadLibrary("aegis_guest")                       │  │
│  └───────────────────────────────┬───────────────────────────────────┘  │
│                                  │ JNI Call                             │
│                                  ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                 libaegis_guest.so (C++17 / NDK 27)                │  │
│  │  • Abre y parsea /proc/self/maps en el PID del proceso :p0        │  │
│  │  • Filtra entradas .so únicas y verifica permisos / errores       │  │
│  │  • Compilada con -Wl,-z,max-page-size=16384 (16 KB page align)    │  │
│  └───────────────────────────────┬───────────────────────────────────┘  │
│                                  │ JSON Contract via ContentProvider    │
│                                  ▼                                      │
│        { "ok": true, "pid": 14820, "packageName": "...", "libs": [...] }│
└─────────────────────────────────────────────────────────────────────────┘
```

### Modelo de Datos y Contrato JSON

- **`GuestMapsReport` (Contrato JSON Exacto)**:
  ```json
  {
    "ok": true,
    "pid": 12345,
    "packageName": "com.dev.migame",
    "libs": ["/data/app/.../libgame.so", "/system/lib64/libc.so"],
    "error": null
  }
  ```
- **`ClonedAppRecord`**: Persistencia local de las apps clonadas (paquete, nombre, fecha de clonado, ruta del contenedor virtual, ABI detectada y último `GuestMapsReport` más logs de ejecución).
- **Flujo de CI/CD y Documentación**:
  - Configuración de `.github/workflows/build.yml` con JDK 17, NDK 27, compilación de `:virtual-core` y `:app`, y verificación de alineamiento de 16 KB (`zipalign -c -P 16` y `llvm-readelf`).
  - Inclusión de `THIRD_PARTY_LICENSES.md` y reporte técnico de compatibilidad en Android 13.
