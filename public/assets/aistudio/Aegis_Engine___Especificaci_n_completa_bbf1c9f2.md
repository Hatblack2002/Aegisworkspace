# Aegis Engine – Especificación completa

Producto, diseño y arquitectura técnica · Versión 1.0 · Mockup de referencia: https://claude.ai/artifact/RdvJi1Kjyg42T4Zxau1LtP

## 1. Propósito y alcance

Aegis Engine es un motor de virtualización de aplicaciones a nivel de app (app cloner) para Android 13, equivalente en concepto a Parallel Space o VirtualApp. Permite ejecutar una app ya instalada en el teléfono dentro de un contenedor aislado, dentro del proceso de Aegis, sin reinstalarla en el sistema.

**Usuario objetivo:** desarrollador que prueba sus propias apps y juegos en un entorno aislado y quiere observar su comportamiento en runtime, por ejemplo qué librerías nativas carga el proceso clonado.

**Qué NO es:**

- No es un antivirus ni un analizador de malware. No emite veredictos de seguridad sobre las apps.
- No debe eludir protecciones de terceros (anti-cheat, licencias, comprobaciones de root o emulador, firmas) ni ocultar su presencia a las apps clonadas.
- No usa servidores externos: todo ocurre en el dispositivo.

**Significado del semáforo:** es solo un indicador técnico. VERDE = el motor pudo leer `/proc/self/maps` del proceso clonado. ROJO = no pudo. No significa app segura ni insegura.

## 2. Objetivos y no objetivos

**Objetivos (v1):**

- Clonar y ejecutar al menos una app simple y un juego sencillo en Android 13 (API 33), arm64-v8a.
- Mostrar el inventario de librerías `.so` mapeadas en el proceso clonado.
- Copiar el resultado como JSON al portapapeles.
- Compilar el proyecto completo en GitHub Actions y generar el APK.

**No objetivos (v1):**

- Compatibilidad con Android 14 o 15 como requisito de aceptación (se evalúa después).
- Soporte de apps con DRM, anti-cheat o integridad de Play (se documenta como incompatible).
- Múltiples cuentas por app, personalización de iconos o notificaciones avanzadas.
- Distribución en Google Play.

## 3. Stack y entorno

- Dispositivo objetivo: Android 13 (API 33), arm64-v8a prioritario.
- compileSdk 35, targetSdk 34, minSdk 26.
- NDK 27, Java 17, Kotlin 2.x, AGP 8.x, Gradle KTS con version catalog.
- UI: Jetpack Compose con Material 3, ViewModel y StateFlow.
- Motor base: NewBlackbox (preferido). Alternativas si falla el spike: SandVXposed o VirtualApp. Son proyectos poco mantenidos: se espera trabajo real de migración.
- Código del motor dentro del repo, en `/virtual-core`. Sin forks externos ni submódulos.

## 4. Flujo de usuario

1. **Inicio:** el usuario ve sus apps clonadas en una cuadrícula y pulsa "Añadir app".
2. **Añadir app:** elige una app instalada de la lista (con búsqueda) y pulsa "Clonar".
3. **Clonado y ejecución:** Aegis instala la app en el contenedor virtual y la lanza. Estados visibles: instalando, ejecutando, error.
4. **Resultado:** al arrancar el proceso clonado, `libaegis_guest.so` envía el resultado al host y la app muestra el semáforo, la lista de librerías, los detalles y el botón de copiar.
5. **Reejecutar:** desde el resultado se puede volver a lanzar el clon y refrescar la lectura.

## 5. Especificación de diseño (UI)

Estilo: moderno, claro y amigable, de launcher de clones. Cabecera azul con esquinas inferiores muy redondeadas, tarjetas blancas sobre fondo gris azulado, iconos de app redondeados y botones tipo píldora. El mockup contiene 4 pantallas de 390×844 dp.

### 5.1 Tokens de diseño

| Token | Valor | Uso |
| --- | --- | --- |
| primary | #2563EB | Cabecera, botones principales |
| onPrimary | #FFFFFF | Texto sobre azul |
| primaryText (enlaces/borde) | #1D4ED8 | Texto de botón secundario |
| background | #F3F5FB | Fondo de pantalla |
| surface | #FFFFFF | Tarjetas |
| onSurface | #0F172A | Texto principal |
| onSurfaceVariant | #475569 | Texto secundario |
| success | #16A34A (texto #15803D) | Semáforo verde |
| error | #DC2626 (texto #B91C1C) | Semáforo rojo |
| outline | #2563EB | Borde punteado del botón "+" |

Colores de icono de app (marcadores en el mockup): #F59E0B, #2563EB, #7C3AED, #DB2777, #0D9488, #EA580C, #475569, #16A34A. En la app real se usa el icono real de cada app.

**Tipografía:** fuente del sistema (Roboto). Monoespaciada (`FontFamily.Monospace`) para rutas, PID y logs. Escala: título de pantalla 30 sp (inicio) o 22 sp (secundarias), cuerpo 14-15 sp, etiquetas 12-13 sp, rutas 11 sp.

**Formas:** cabecera con radio inferior 32 dp; tarjetas 20-24 dp; botones píldora (altura 54 dp principal, 46 dp dentro de tarjeta); icono de app con radio del 30 % de su lado; tamaño de icono 64 dp en inicio y 56 dp en selector.

**Elevación:** sombra suave en tarjetas (aprox. 0 4 16 con 6-8 % de opacidad).

**Accesibilidad:** objetivos táctiles de al menos 44 dp; contraste mínimo 4,5:1 en texto; el semáforo siempre lleva icono (check o X) y texto (VERDE o ROJO), nunca solo color; `contentDescription` en iconos sin texto (volver, añadir).

### 5.2 Pantalla 1: Inicio

- Cabecera azul: etiqueta "AEGIS ENGINE", título "Mis apps clonadas", subtítulo "Ejecútalas en un espacio aislado".
- Tarjeta blanca con cuadrícula de 3 columnas: icono + nombre de cada app clonada y, al final, un botón "Añadir" con borde punteado azul y signo "+".
- Botón píldora inferior "Añadir app".
- Toque en un icono: lanza el clon. Pulsación larga: menú (Reejecutar, Ver resultado, Eliminar clon).
- Estado vacío: ilustración simple y texto "Aún no has clonado ninguna app" con el botón "Añadir app".

### 5.3 Pantalla 2: Añadir app

- Cabecera azul con botón volver (circular, translúcido), título "Añadir app" y campo de búsqueda en píldora blanca.
- Tarjeta con cuadrícula de 4 columnas de apps instaladas (icono 56 dp + nombre). La app seleccionada muestra una insignia verde con check.
- Botón inferior "Clonar N apps" (deshabilitado si no hay selección).
- Excluye Aegis de la lista. La lista usa `<queries>` en el manifest; se evita `QUERY_ALL_PACKAGES` si es posible.
- v1: selección de una sola app (el botón dice "Clonar 1 app").

### 5.4 Pantalla 3: Resultado verde

- Cabecera azul con volver y nombre de la app. Sobre ella, tarjeta con círculo verde de 80 dp con check, texto "VERDE" y la frase "El motor leyó /proc/self/maps del proceso clonado".
- Botones: "Copiar JSON" (principal) y "Reejecutar" (secundario con borde).
- "Librerías (N)": tarjeta con rutas completas en monoespaciada, con ajuste de línea.
- Tarjeta "Detalles": PID, paquete, proceso (por ejemplo `:p0`), error ("ninguno").
- Toque en una librería: la copia al portapapeles. La tarjeta de detalles puede expandirse con la lista completa de rutas.

### 5.5 Pantalla 4: Resultado rojo

- Misma estructura con círculo rojo con X, texto "ROJO" y "El motor no pudo leer /proc/self/maps".
- Botones: "Reintentar" y "Copiar log".
- Tarjeta "Log": últimas líneas del logcat filtrado por etiqueta `Aegis`/`AegisGuest`.
- Tarjeta "Detalles": PID, paquete, error (por ejemplo `EACCES`).

### 5.6 Estados de clonado (pantalla de progreso)

Pantalla o diálogo modal con indicador de progreso y texto: "Instalando en el contenedor…", "Iniciando proceso…", "Leyendo librerías…". Si falla, mensaje legible con causa (por ejemplo "ABI no compatible" o "El proceso se cerró al arrancar") y acceso a "Ver log". Nunca una pantalla en blanco.

### 5.7 Mapeo a Compose

- `AegisTheme` con `MaterialTheme` y `lightColorScheme` con los tokens anteriores; tipografía y formas (`Shapes`) personalizadas.
- Pantallas: `HomeScreen`, `AddAppScreen`, `ResultScreen` (un solo composable con estado `ok` verde o rojo), `CloneProgressDialog`.
- Componentes reutilizables: `AppTile`, `AppGrid`, `HeaderBar`, `StatusCard`, `PillButton`, `LibsCard`, `DetailsCard`.
- Navegación con `NavHost`: `home` → `add` → `result/{packageName}`.
- Estado en ViewModel con `StateFlow<UiState>` (sealed class: Idle, Installing, Running, Result, Error).

## 6. Arquitectura técnica

### 6.1 Modelo mental de un app cloner

- **Host:** la app Aegis (UI, gestor de instalaciones virtuales, servicios de sistema virtuales).
- **Guest:** la app clonada, que corre en un proceso stub del host (`:p0`, `:p1`, …) declarado en el manifest del host.
- El motor intercepta las llamadas del guest a servicios del sistema (ActivityManager, PackageManager, ContentResolver, etc.) mediante proxies de Binder y hooks nativos. El sistema cree que la actividad pertenece al stub del host; el guest cree que está instalado normalmente.
- Los datos del guest se redirigen a un directorio privado del host (redirección de E/S nativa).
- Las actividades, servicios y providers del guest se ejecutan mediante componentes proxy declarados en el host.

### 6.2 Estructura del repositorio

- `/app`: UI Compose y ViewModel.
- `/virtual-core`: motor de virtualización importado (NewBlackbox u otro) con sus avisos de licencia.
- `/native` (o dentro de `/virtual-core`): CMake raíz con `add_subdirectory` para el motor y `libaegis_guest.so`.
- `/.github/workflows/build.yml`: CI.
- `THIRD_PARTY_LICENSES.md`: licencias de dependencias.

### 6.3 libaegis\_guest.so

- Se compila en el mismo build CMake que el motor virtual, misma ABI y mismos flags.
- Se carga con `System.loadLibrary("aegis_guest")` dentro del proceso clonado (en `Application.attach` del guest o en el punto de arranque del proceso stub).
- Función nativa: lee `/proc/self/maps` del proceso clonado, filtra las rutas `.so`, devuelve la lista única de rutas completas y un indicador `ok` (true si pudo leer y la lista no está vacía).
- Debe manejar errores (fopen falla, SELinux, lista vacía) y devolver el motivo en `error`.

### 6.4 Canal guest → host y contrato de datos

Canal principal: ContentProvider del host (alternativas: Binder o socket local). Contrato JSON:

```json
{
  "ok": true,
  "pid": 12345,
  "packageName": "com.dev.migame",
  "libs": ["/data/app/.../libgame.so", "/system/lib64/libc.so"],
  "error": null
}
```

Reglas: `ok` es false si no se pudo leer o la lista está vacía; en ese caso `error` contiene el motivo. El botón "Copiar JSON" copia exactamente este objeto.

## 7. Requisitos de migración (Android 13)

- Gradle KTS, version catalog, AGP y Kotlin actuales, `namespace` en lugar de `package` en el manifest.
- CMakeLists o Android.mk compatibles con NDK 27 (STL, flags, toolchain).
- Alineación de 16 KB: `-Wl,-z,max-page-size=16384` en todas las `.so`, incluida `libaegis_guest.so`.
- Corregir los hooks nativos (Dobby, xHook, Whale, Substrate) hasta que compile y arranque.
- Bypass de hidden APIs (por ejemplo AndroidHiddenApiBypass).
- Revisar y corregir los hooks de ActivityManager, PackageManager y Binder para API 33 (cambios en ActivityTaskManager, IActivityManager, PackageManager). Es la parte que más tiempo suele consumir.
- Restricciones de Android 13 a vigilar: notificaciones (`POST_NOTIFICATIONS`), permisos de media granulares, tipos de foreground service, imposibilidad de ejecutar binarios desde el almacenamiento de la app con targetSdk ≥ 29.

## 8. Plan de trabajo

| Paso | Entregable |
| --- | --- |
| 0. Spike | Compilar NewBlackbox sin cambios, instalar en Android 13 y clonar una app simple. Si falla, evaluar SandVXposed o VirtualApp y recomendar. Resumir la licencia y su efecto en la distribución. |
| 1. Importar | Código en `/virtual-core` (git subtree con squash o vendorizado) con commit que indique repositorio origen y hash. `THIRD_PARTY_LICENSES.md`. |
| 2. CI temprano | Workflow de GitHub Actions que compile el estado actual. |
| 3. Migrar | Stack actualizado y hooks corregidos para API 33. |
| 4. Motor nativo | `libaegis_guest.so` integrado y canal guest → host funcionando. |
| 5. UI | Pantallas Compose según la sección 5. |
| 6. Verificación | Pruebas manuales en dispositivo y resumen final. |

Orden recomendado: 0, 1, 2, 3, 4, 5, 6.

## 9. CI (GitHub Actions)

- JDK 17, `android-actions/setup-android`, NDK 27, caché de Gradle.
- `./gradlew assembleDebug` y `upload-artifact` del APK.
- Verificación del alineamiento de 16 KB de las `.so` con `zipalign -c -P 16` y `llvm-readelf`.
- Opcional: `assembleRelease` firmado con secrets del repositorio.

## 10. Criterios de aceptación

- El workflow genera el APK sin errores.
- En Android 13 se clona y ejecuta al menos una app simple y un juego sencillo.
- El semáforo verde y la lista de librerías del proceso clonado son visibles y copiables.
- Si una app no es compatible, la UI lo indica con un error legible.
- La UI coincide con los tokens, componentes y estados de la sección 5.
- No hay ningún mensaje de "compilado" o "funcionando" sin verificación real.

## 11. Riesgos y mitigaciones

| Riesgo | Mitigación |
| --- | --- |
| Los forks de VirtualApp están abandonados y fallan en Android 12 o superior | Spike previo, comparar forks vivos, reservar tiempo para reescribir hooks |
| Hooks nativos incompatibles con NDK 27 | Actualizar o sustituir la librería de hooks; documentar lo que no se pueda arreglar |
| Licencia (GPL o comercial) condiciona la distribución | Revisarla en el paso 0 y reflejarla en `THIRD_PARTY_LICENSES.md` |
| SELinux bloquea la lectura de `/proc/self/maps` | Devolver `error` descriptivo y mostrar el semáforo rojo |
| Apps con anti-cheat o integridad de Play no arrancan | Marcarlas como no compatibles con mensaje claro; no intentar eludirlas |
| `QUERY_ALL_PACKAGES` restringido por Play | Usar `<queries>` o limitar la distribución a instalación directa |

## 12. Reglas de trabajo para el agente de desarrollo

- Rama nueva, un commit por paso, mensajes claros.
- Ejecutar los builds de verdad; si falla, mostrar el error real y corregirlo.
- Si un hook no se puede arreglar en Android 13, no ocultarlo con un stub silencioso: documentar qué falla, por qué y qué alternativas hay.
- No eliminar funcionalidad del motor para que compile; lo desactivado se lista en el informe final.
- Respetar licencias y conservar los avisos de copyright.

## 13. Entregable final

Resumen con: qué funciona (con evidencia y logs), qué falló, qué quedó pendiente, apps probadas con el resultado de cada una, y licencias de las dependencias.
