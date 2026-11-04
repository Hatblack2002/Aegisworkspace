# Third-Party Licenses & Architectural Provenance

## 1. Evaluación del Motor de Virtualización (Paso 0 / Spike de Licencias)

Durante la fase de diseño y spike técnico para **Aegis Engine v1.0** (Android 13 / API 33+, NDK 27), se evaluaron las alternativas históricas de virtualización a nivel de aplicación y sus implicaciones de licencia y compatibilidad técnica:

| Proyecto Evaluado | Licencia | Efecto en Distribución | Estado Técnico en Android 13 (API 33) + NDK 27 |
| :--- | :--- | :--- | :--- |
| **VirtualApp** (`asLody/VirtualApp`) | **GPLv3 / Comercial** | Obliga a publicar bajo GPLv3 todo el código derivado o adquirir licencia comercial del autor original. | Repositorio público archivado/desactualizado; hooks de `IActivityManager` y `PackageManager` rotos desde Android 11+. |
| **SandVXposed / VirtualXposed** | **GPLv3** | Copyleft fuerte (GPLv3). Condiciona la distribución binaria a la entrega del código fuente completo bajo GPLv3. | Basado en VirtualApp + SandHook; incompatible con NDK 27 (`c++_shared` moderno y alineación ELF de 16 KB) sin reescritura completa. |
| **NewBlackbox** (`FBlackBox/BlackBox`) | **Apache License 2.0** (con componentes AIDL/refl) | Permite distribución abierta y privada conservando avisos de copyright y `NOTICE`. | El repositorio original fue retirado/archivado y sus hooks de reflexión/native C++ usan Android.mk heredado sin soporte directo para AGP 8/9 y NDK 27. |
| **Aegis Virtual-Core (`:virtual-core`)** | **Apache License 2.0** | Libre distribución comercial y abierta sin restricciones copyleft. | Implementación limpia en `:virtual-core` diseñada para Android 13+ con procesos stub aislados (`:p0`, `:p1`), `DexClassLoader` + redirección de contenedor virtual y `libaegis_guest.so` compilada con CMake y alineación de página de 16 KB (`-Wl,-z,max-page-size=16384`). |

---

## 2. Dependencias Incluidas y Avisos de Licencia

### Android Jetpack & Jetpack Compose
- **Artefactos**: `androidx.core:core-ktx`, `androidx.activity:activity-compose`, `androidx.lifecycle:*`, `androidx.navigation:navigation-compose`, `androidx.compose.material3:material3`, `androidx.compose.ui:*`
- **Licencia**: Apache License, Version 2.0
- **Copyright**: Copyright (C) The Android Open Source Project

### Kotlin Standard Library & Kotlinx Coroutines
- **Artefactos**: `org.jetbrains.kotlin:kotlin-stdlib`, `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `org.jetbrains.kotlinx:kotlinx-coroutines-core`
- **Licencia**: Apache License, Version 2.0
- **Copyright**: Copyright (C) JetBrains s.r.o. and Kotlin Programming Language contributors

### Android NDK (`liblog`, `libc++_shared`, `dlfcn`)
- **Componente**: Toolchain CMake / LLVM de Android NDK para `libaegis_guest.so`
- **Licencia**: Apache License 2.0 with LLVM Exception / BSD 2-Clause
- **Copyright**: Copyright (C) The Android Open Source Project

---

## Texto de la Licencia Apache 2.0

```text
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
