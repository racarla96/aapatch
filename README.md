# aapatch — parcheador de apps para Android Auto

Dos partes que comparten el mismo código:
- `src/` — **núcleo** en Java puro (solo JDK): corre en PC (CLI) y dentro de la app Android.
- `android/` — **app Android** (proyecto de Android Studio): elige una app instalada, la parchea en el móvil y la instala.

## App Android (`android/`)
**Sin Android Studio:** sube esta carpeta a un repositorio de GitHub; el flujo `.github/workflows/build-apk.yml` compila el APK y lo deja en Actions > la ejecución > Artifacts (`AAPatcher-debug-apk`).

1. Abre la carpeta `android/` con Android Studio y deja que sincronice Gradle (AGP 8.7.3, compileSdk 35, minSdk 26; sin dependencias externas).
2. Instálala en el móvil. La primera vez te pedirá permitir "instalar apps desconocidas" para AA Patcher.
3. Toca una app de la lista -> opciones -> "Parchear e instalar" -> confirma el diálogo del sistema.
4. En Android Auto: modo desarrollador (tocar 10 veces en la versión) y activar "Fuentes desconocidas". Busca la app con el sufijo "(AA)".

### Pestaña "GitHub (originales)"
Descarga el APK **oficial y sin modificar** de la última versión de un repositorio de GitHub (por ejemplo `AndreyPavlenko/Fermata`) y pide a Android que registre Google Play como instalador (`SessionParams.setInstallerPackageName`, API pública desde Android 14).
Al terminar muestra el **origen que Android registró de verdad**, para comprobar si la petición se respetó.
- Mantiene paquete y firma originales: el inicio de sesión de Google sigue funcionando.
- Solo sirve para apps que **ya declaran soporte de Android Auto** (Fermata, CarStream…). Una app normal (YouTube) no aparecerá en el coche sin parchearla.
- Puedes escribir cualquier `propietario/repositorio`; el catálogo base está en `android/app/src/main/assets/catalog.json`.
- En Android 13 o inferior no se puede simular el origen sin root.

**Estado:** el código de la app Android **no se ha compilado ni probado** (el entorno donde lo escribí no tiene el SDK de Android). El núcleo sí está probado (ver abajo).
Si el compilador marca algo, probablemente sea un detalle menor de API; pega el error y lo arreglo.

Limitaciones: las apps con APKs divididos se parchean base + splits con la misma clave, pero ese camino solo está probado a nivel de manifiesto. Apps con comprobación de firma propia, DRM o Play Integrity pueden dejar de funcionar al re-firmarse. Los datos de la app original no se copian a la copia parcheada.

---

# Núcleo (CLI en PC)

Toma un APK, reescribe su `AndroidManifest.xml` binario, lo reempaqueta (alineado, sin firmas antiguas) y lo firma con APK Signature Scheme v2.
Sin dependencias: ni apksig ni BouncyCastle (por eso también funciona dentro de una app Android).

## Qué hace el manifiesto
- Renombra el paquete (`x.y` -> `x.y.aa`). Antes hace completos los nombres de clase relativos (`.Main`) contra el paquete ORIGINAL.
- Renombra autoridades de providers y permisos propios para que la copia conviva con la original.
- Quita `sharedUserId`. Opcional: cambia la etiqueta (`--label`).
- Añade `CAR_LAUNCHER` + `DEFAULT` al intent-filter del launcher, `distractionOptimized`, `appCategory=game` y `resizeableActivity`.
  Cada inyección se puede desactivar (`--no-car-launcher`, `--no-distraction-optimized`, `--no-game-category`, `--no-resizeable`).

## Uso
    ./build_and_test.sh                # compila y pasa los tests sintéticos
    ./build_and_test.sh mi.apk         # además parchea mi.apk y verifica firma, manifiesto y alineación
    java -cp out aapatch.Cli patch in.apk out.apk --keystore ks.p12 --suffix aa --label "Mi app"

## Probado
- Round trip del manifiesto de un APK real (parse -> write -> parse) sin pérdidas.
- Firma v2 verificada con un verificador independiente en Python (firma RSA y digest de los trozos).
- ZIP íntegro, 0 entradas desalineadas (4 B; 16 KB para `.so`), sin restos de firma v1.
- 17 tests sintéticos (nombres relativos, autoridades, permisos, interruptores).

## NO probado / pendiente
- **Instalación real en un móvil y que Android Auto muestre la app.** Las inyecciones de manifiesto están deducidas del análisis de una app existente;
  hay que comprobar en dispositivo cuáles hacen falta realmente (y si requiere modo desarrollador de Android Auto).
- Firma solo v2 con RSA-2048 (suficiente para minSdk >= 24). Sin v3/rotación de claves.
- Catálogo de apps descargables y actualizaciones: no hecho todavía.
- Apps con comprobación de firma propia, DRM o Play Integrity pueden dejar de funcionar al re-firmarse.
