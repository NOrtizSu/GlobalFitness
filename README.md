# GlobalFitness

App Android (Kotlin + Jetpack Compose, Material You) para seguir tu **peso y composición corporal**
con básculas Bluetooth y tu **actividad, sueño y pulso** desde Health Connect o directamente desde una
Xiaomi Smart Band 9. Interfaz en español, un solo perfil, y los datos se quedan en el teléfono salvo que
actives la copia en la nube.

## Funciones

- **Básculas Bluetooth (66 modelos)**: catálogo con detección automática. Al subirte a una báscula
  compatible con la app abierta, se reconoce y se configura sola; también puedes elegirla en
  *Dispositivos → Añadir desde el catálogo*. Las básculas de solo anuncios se leen de forma pasiva y las
  que necesitan conexión (GATT) se conectan solas. Los modelos sin verificar con hardware real llevan la
  etiqueta **Experimental**.
- **Composición corporal**: IMC, grasa, masa grasa, músculo, agua, hueso, grasa visceral, metabolismo
  basal, edad metabólica, proteína, peso ideal y tipo de cuerpo. Se usan los valores que da la propia
  báscula y, si no los da, las fórmulas publicadas para cada familia (Mi Fit, Yunmai, Trisa…).
- **Gráficos**: media móvil de 7 días, rango normal, peso objetivo, ritmo semanal y proyección; historial
  agrupado por meses y peso manual.
- **Health Connect**: pasos, distancia, calorías, pulso (día y 30 días), sueño con hipnograma y calidad,
  oxígeno en sangre y entrenamientos de tu pulsera o reloj (p. ej. Mi Fitness). Opcionalmente envía tus
  mediciones de báscula a Health Connect.
- **Xiaomi Smart Band 9, modo directo (experimental)**: conexión Bluetooth sin Mi Fitness para pulso en
  directo, buscar la pulsera, alarmas y reenvío de notificaciones. El pulso registrado aparece también
  en *Actividad*.
- **Copia en la nube (opcional)**: inicio de sesión con Google y copia del perfil, el historial y los
  ajustes en Firebase; restauración al reinstalar o cambiar de móvil (también desde la bienvenida).
- **Anuncios y «Quitar anuncios»**: banner y un intersticial con límite de frecuencia (AdMob, con
  consentimiento UMP en la UE); nunca durante la bienvenida, el inicio de sesión, la compra o una
  medición. Pago único con Google Play Billing para quitarlos.
- **Exportar / importar CSV** (separador `;`, decimales con coma).

## Navegación

Cinco secciones en la barra inferior:

| Sección | Contenido |
|---|---|
| **Hoy** | Anillos de pasos, sueño y pulso en reposo (Health Connect), medición en curso, último peso con tendencia de 30 días, composición destacada y estado de la báscula, la nube y la pulsera. |
| **Cuerpo** | Gráfico por métrica y periodo, resumen, proyección, composición e historial por meses (detalle y borrado). Botón «Añadir peso manual». |
| **Actividad** | Datos de Health Connect y sincronización. |
| **Dispositivos** | Básculas (configuradas, detectadas, catálogo), pulsera en modo directo, Health Connect y diagnóstico BLE. |
| **Perfil** | Perfil, cuenta y copia en la nube, quitar anuncios, CSV, envío a Health Connect, política de privacidad y avisos de código abierto. |

La primera vez se muestra una bienvenida: restaurar desde la nube o continuar sin cuenta, perfil,
permisos (Bluetooth y Health Connect) y listo.

## Compilar

Requisitos: JDK 17 y Android SDK (plataforma 36). No hace falta Android Studio.

1. Crea `local.properties` con la ruta del SDK (en Windows hay que escapar los dos puntos):

   ```properties
   sdk.dir=C\:/Users/<usuario>/AppData/Local/Android/Sdk
   ```

2. Copia tu `app/google-services.json` de Firebase (ver más abajo). Sin él el plugin de Google Services
   no puede compilar.

3. Compila, prueba y pasa lint:

   ```bat
   gradlew assembleDebug testDebugUnitTest lintDebug
   ```

El APK queda en `app\build\outputs\apk\debug\app-debug.apk`.

## Instalar

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

O copia el APK al teléfono y ábrelo (hay que permitir «instalar apps desconocidas»). Requiere Android 8.0
o superior; Health Connect necesita Android 9 o superior (en Android 13 o anterior, la app de Health
Connect de Google Play).

## Configuración de servicios

- **Firebase** (cuenta y copia en la nube): crea un proyecto, añade la app Android
  `com.enjoyyourtime.globalfitness` con las huellas SHA-1/SHA-256 de tus claves de firma, activa
  *Authentication → Google* y *Cloud Firestore* (región UE) y descarga `google-services.json` a `app/`.
  Las reglas de seguridad están en `firestore.rules` (`firebase deploy --only firestore:rules`).
- **AdMob**: las compilaciones de depuración usan siempre los identificadores de prueba de Google. Para
  la versión publicada define `ADMOB_APP_ID`, `ADMOB_BANNER_ID` y `ADMOB_INTERSTITIAL_ID` como
  propiedades de Gradle (`-P…` o `~/.gradle/gradle.properties`) o en `local.properties`. Configura el
  mensaje de consentimiento (UMP) en la consola de AdMob.
- **Google Play**: crea en Play Console el producto de compra única `remove_ads` («Quitar anuncios») y
  completa las declaraciones de *Seguridad de los datos* y de Health Connect.
- **Política de privacidad**: `docs/privacy-policy.md` es un **borrador** pendiente de revisión legal; se
  incluye en la app (*Perfil → Política de privacidad*) y hay que publicarlo en una URL pública.

## Herramientas de depuración

Solo en compilaciones de depuración (protegidas con el permiso `DUMP`, es decir, solo `adb`):

```bat
:: Historial de peso de ejemplo para el perfil actual
adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugMeasurementSeedReceiver --ei days 120
:: Datos de pulsera de ejemplo en Health Connect (ver DebugHealthSeedReceiver)
adb shell am broadcast -n com.enjoyyourtime.globalfitness/.health.debug.DebugHealthSeedReceiver --ei days 10
```

## Limitaciones conocidas

- Las básculas solo se leen **con la app abierta en primer plano** (no hay servicio en segundo plano) y no
  se sincroniza el historial interno que algunas básculas guardan sin teléfono cerca.
- Sin impedancia (calcetines, pies secos o muy fríos) se guarda solo el peso.
- Las métricas son **estimaciones**, no un diagnóstico médico.
- El modo directo de la pulsera es experimental: mientras está abierto, Mi Fitness no puede sincronizar.

## Licencia

Los protocolos de las básculas están portados de [openScale](https://github.com/oliexdev/openScale)
(**GPL-3.0**) y el de la Xiaomi Smart Band de [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)
(**AGPL-3.0**); los avisos están en *Perfil → Código abierto*. **Antes de publicar hay que decidir la
licencia del proyecto**: distribuir la app con ese código obliga a una licencia compatible (p. ej.
AGPL-3.0) y a ofrecer el código fuente, o bien a sustituir esas partes.
