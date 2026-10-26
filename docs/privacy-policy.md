# Política de privacidad de Fortia

Última actualización: 26 de octubre de 2026 · Contacto: **enjoyyourtime.online@gmail.com** ·
Versión web: [globalfitness-app.web.app/privacy.html](https://globalfitness-app.web.app/privacy.html)

## 1. Quién es el responsable

Fortia (antes GlobalFitness) es una app para Android publicada por **Enjoy Your Time**, que es el responsable
del tratamiento. Para cualquier cuestión sobre privacidad escribe a
**enjoyyourtime.online@gmail.com**.

## 2. Resumen

- La app funciona **sin cuenta**: por defecto todos tus datos se guardan **solo en tu teléfono**.
- La **copia en la nube es opcional**: solo se activa si inicias sesión con Google, y puedes
  borrarla (junto con la cuenta) desde la propia app cuando quieras.
- Tus **datos de salud nunca se usan para publicidad** ni se venden.
- La app muestra **anuncios de Google AdMob**, salvo si compras «Quitar anuncios». En la UE te
  pedimos el consentimiento antes de mostrar anuncios personalizados.

## 3. Datos que se guardan en tu teléfono

- **Perfil**: nombre, sexo, fecha de nacimiento, altura y peso objetivo (para calcular la
  composición corporal).
- **Mediciones**: fecha y hora, peso, impedancia bioeléctrica y si vienen de una báscula o se
  introdujeron a mano. A partir de ellas se calculan en el teléfono el IMC, la grasa corporal y
  otras estimaciones.
- **Ajustes** de la app y **básculas configuradas** (modelo, nombre y opciones).
- **Entrenamiento**: los entrenos que registras (fecha y hora, nombre y notas, ejercicios y series con
  el peso, las repeticiones, el tiempo, la distancia, el nivel o las calorías que introduzcas), las
  máquinas y ejercicios que creas (nombre, músculos y notas de ajuste), tus favoritos y, si las añades,
  **fotos de tus máquinas**. Las fotos las haces con la cámara o eliges tú una a una con el selector de
  fotos de Android (la app no tiene acceso a tu galería); se reducen y se guardan en el almacenamiento
  privado de la app. Con tu peso registrado, el teléfono calcula el 1RM estimado y el progreso por músculo.
- **Pulsera Xiaomi Smart Band**: la clave de emparejamiento de la pulsera se guarda solo en el
  teléfono y **nunca sale de él** (ni a la nube ni a la copia de seguridad de Android). El pulso
  que registras en el modo directo (fecha, hora y pulsaciones) también se guarda solo en el teléfono.

Estos datos se quedan en el teléfono hasta que los borres o desinstales la app.

## 4. Dispositivos Bluetooth

La app usa Bluetooth para leer las mediciones de tu báscula y de tu pulsera. En Android 11 o
anterior, Android exige el permiso de ubicación para buscar dispositivos Bluetooth; la app **no
usa tu ubicación** para nada más y no la guarda. Las direcciones Bluetooth de tus dispositivos
se guardan solo en el teléfono.

**Reenvío de notificaciones (opcional).** Si activas el reenvío de notificaciones a la pulsera y
concedes a la app el acceso a las notificaciones, la app lee el nombre de la app, el título y el
texto de las notificaciones de las apps que elijas y los envía por Bluetooth a tu pulsera, solo
mientras está conectada en modo directo. Ese contenido **no se guarda, no se registra y no sale
del teléfono** salvo hacia tu pulsera. Puedes retirar el acceso en los ajustes de Android.

## 5. Health Connect

Si lo autorizas, la app **lee y escribe** en Health Connect los tipos de datos que concedas en la
pantalla de permisos de Android (por ejemplo peso, composición corporal o frecuencia cardiaca).
Puedes retirar el permiso en cualquier momento desde los ajustes de Health Connect.

Si lo autorizas, la app también **escribe en Health Connect tus entrenos terminados** como sesiones de
ejercicio (inicio y fin, tipo de ejercicio, nombre y notas del entreno y un segmento por serie con sus
repeticiones; los pesos no se escriben) para que otras apps de fitness puedan verlos. Si borras un entreno
en la app, se borra también de Health Connect; si desactivas la opción, deja de escribir y lo ya guardado
se conserva. La app no vuelve a leer sus propias sesiones.

- Los datos de Health Connect se usan **solo para mostrarte tu progreso y sincronizar tus
  mediciones** con otras apps que tú elijas. Lo que se lee se guarda en una caché en el teléfono.
- **No se suben** a la copia en la nube de Fortia, **no se usan para publicidad**, no se
  venden y no se comparten con terceros.
- El uso de la información recibida de Health Connect cumple la política de permisos de Health
  Connect, incluidos los requisitos de uso limitado.

## 6. Cuenta de Google y copia en la nube (opcional)

Si inicias sesión con Google (mediante Firebase Authentication):

- **Datos de la cuenta**: identificador de usuario de Firebase, correo electrónico, nombre y foto
  de perfil de tu cuenta de Google. Se usan para identificarte y mostrar la cuenta en la app.
- **Copia de seguridad** (Google Cloud Firestore): tu perfil (con un identificador aleatorio del
  conjunto de datos, para no mezclar nunca datos de personas distintas), tus mediciones (fecha,
  peso, impedancia, composición corporal que dé la báscula, origen y modelo de báscula) y tus
  básculas configuradas (modelo, nombre y número de usuario en la báscula), y tus entrenos, tus
  máquinas y ejercicios propios con sus notas, favoritos y fotos. **No** se suben la
  clave de la pulsera, las claves de cifrado, PIN o claves de emparejamiento de las básculas, las
  direcciones Bluetooth, los datos de Health Connect ni ajustes propios del teléfono.
  Si has comprado «Quitar anuncios», se guarda además un indicador informativo `premium`.
- **Dónde**: en servidores de Google Cloud en la **Unión Europea (región europe-southwest1,
  Madrid)**. Los datos viajan cifrados (TLS) y Google los guarda cifrados en reposo.
- **Para qué**: guardar una copia y recuperarla si cambias de móvil o reinstalas la app, y
  mantener sincronizados tus teléfonos.
- **Base legal**: tu consentimiento explícito (art. 6.1.a y 9.2.a del RGPD), que das al iniciar
  sesión y puedes retirar en cualquier momento.
- **Cuánto tiempo**: mientras mantengas la cuenta. Si cierras sesión, la copia se conserva en la
  nube (puedes volver a iniciar sesión para recuperarla).
- **Cómo borrarla**: en la app, **Perfil → Cuenta y copia en la nube → «Borrar mis datos de la nube
  y la cuenta»**. Se borran de forma definitiva tu copia en Firestore y tu cuenta de Firebase; los
  datos de tu teléfono no se tocan. También puedes pedirlo por correo a
  enjoyyourtime.online@gmail.com. Instrucciones completas:
  [globalfitness-app.web.app/delete-account.html](https://globalfitness-app.web.app/delete-account.html)

## 7. Publicidad (Google AdMob)

La versión gratuita muestra anuncios de **Google AdMob**. Para ello, Google puede tratar
identificadores del dispositivo (como el ID de publicidad), la dirección IP y datos de
interacción con los anuncios, según la
[política de privacidad de Google](https://policies.google.com/privacy) y
[cómo usa Google los datos de las apps de sus partners](https://policies.google.com/technologies/partner-sites).

- En el Espacio Económico Europeo y el Reino Unido usamos la **Plataforma de Mensajes de Usuario
  (UMP) de Google** para pedir tu consentimiento antes de mostrar anuncios. Puedes cambiar tu
  decisión en **Perfil → «Opciones de privacidad de anuncios»**.
- **Nunca enviamos a los anuncios tus datos de salud o corporales** (peso, composición corporal,
  frecuencia cardiaca, perfil…): las solicitudes de anuncios no llevan segmentación ni palabras
  clave de la app.
- No se muestran anuncios en las pantallas de inicio de sesión, compra o consentimiento.

## 8. Compra «Quitar anuncios» (Google Play Billing)

La compra la procesa **Google Play**. La app solo recibe el estado de la compra (comprada,
pendiente) y un identificador de compra para confirmarla; **no recibimos tus datos de pago**.
El estado se guarda en el teléfono para que sigas sin anuncios aunque no tengas conexión.

## 9. Copia de seguridad de Android

Si tienes activada la copia de seguridad de Android, el sistema puede guardar una copia cifrada
de la base de datos y de los ajustes de la app en tu cuenta de Google Drive, y restaurarla al
reinstalar o cambiar de móvil. **Quedan excluidas** las pulseras y relojes configurados (con sus claves, ajustes
y el pulso registrado en modo directo), la caché de Health Connect y las fotos de tus máquinas (por su
tamaño; si usas la copia en la nube de la app, se guardan allí). Puedes desactivarla en los ajustes de Android.

## 10. Con quién se comparten los datos

Solo con los proveedores necesarios para que la app funcione, que actúan como encargados del
tratamiento o como responsables independientes según el caso: **Google** (Firebase Authentication,
Cloud Firestore, AdMob/UMP y Google Play). No vendemos datos personales.

**Música para hoy (Spotify).** Las portadas y los nombres de las listas de Spotify que sugiere la app
solo se cargan **si lo permites**: la app lo pregunta una vez, al empezar, justo después del
consentimiento de anuncios, y puedes cambiarlo cuando quieras en **Perfil → Ajustes de entreno →
«Mostrar portadas de Spotify»** (base legal: tu consentimiento, art. 6.1.a del RGPD). Mientras no lo
permitas, la app no hace ninguna petición a Spotify y las listas se muestran con su nombre, sin portada.
Si lo permites, el teléfono los pide directamente a Spotify: a open.spotify.com (su servicio oEmbed, que
da el nombre y la portada de cada lista) y a sus servidores de imágenes (`*.scdn.co`,
`*.spotifycdn.com`). Spotify recibe así tu dirección IP y un User-Agent genérico de la app («Fortia/»
y su versión, sin el modelo del teléfono); no se envía ninguna cuenta, identificador ni dato personal:
solo el enlace público de la lista. Los nombres y las portadas se guardan en la caché del teléfono y se
borran cuando una lista lleva 30 días sin sugerirse, y en cuanto desactivas las portadas o las
sugerencias (Android también puede vaciar la caché si necesita espacio). Abrir una lista es decisión
tuya y no depende de este permiso: te lleva a la app o la web de Spotify, que se rigen por sus propias
condiciones y política de privacidad. Puedes ocultar todas las sugerencias en **Perfil → Ajustes de
entreno**.

## 11. Menores

La app no está dirigida a menores de 16 años.

## 12. Tus derechos

Puedes acceder a tus datos, rectificarlos, borrarlos, oponerte o limitar su tratamiento, retirar
tu consentimiento y pedir la portabilidad (la app permite **exportar tus mediciones a CSV**).
Escríbenos a enjoyyourtime.online@gmail.com. Si no estás conforme, puedes reclamar ante la
**Agencia Española de Protección de Datos** (www.aepd.es).

## 13. Cambios

Si cambiamos esta política, publicaremos la nueva versión con su fecha y, si el cambio es
importante, te avisaremos en la app.

---

# Fortia Privacy Policy (English)

Last updated: 26 October 2026 · Contact: **enjoyyourtime.online@gmail.com**

**Controller.** Fortia (formerly GlobalFitness) is published by **Enjoy Your Time**, the data controller. Contact:
**enjoyyourtime.online@gmail.com**.

**Summary.** The app works **without an account** and keeps everything **on your phone** by default.
**Cloud backup is optional** (Google sign-in) and can be deleted, together with the account, from
inside the app. **Health data is never used for advertising** and is never sold. The free version
shows **Google AdMob** ads unless you buy "Remove ads".

**Stored on the phone:** profile (name, sex, date of birth, height, target weight), measurements
(date, weight, bioelectrical impedance, source) and derived body-composition estimates, settings and
configured scales, and your training log (workouts, sets, your own machines with setting notes and optional
machine photos taken with the camera or picked with Android's photo picker). The Xiaomi Smart Band pairing
key stays on the phone only and is never uploaded or included in Android backups.

**Bluetooth:** used to read your scale and band. On Android 11 and lower the location permission is
required by Android to scan for Bluetooth devices; location is not used for anything else. Heart rate
recorded in direct band mode stays on the phone.

**Notification forwarding (optional):** if you enable it and grant notification access, the app reads
the app name, title and text of notifications from the apps you choose and sends them over Bluetooth
to your band, only while it is connected in direct mode. That content is not stored, logged or sent
anywhere else.

**Health Connect:** with your permission the app reads and writes the data types you grant (e.g.
weight, body composition, heart rate). This data is only used to show your progress and sync with
apps you choose, and is cached on the phone; it is **not uploaded to the Fortia cloud backup,
not used for ads, not sold and not shared**. Use of information received from Health Connect follows
the Health Connect Permissions policy, including the Limited Use requirements. If you allow it, the app
also writes your finished workouts to Health Connect as exercise sessions (times, type, name, notes,
repetitions per set; no weights), deletes them when you delete the workout, and never reads them back.

**Optional Google account and cloud backup:** Firebase Authentication (user id, email, name, profile
photo) and Google Cloud Firestore in the **EU (europe-southwest1, Madrid)** store your profile (with a
random dataset id), measurements (incl. scale-reported body composition and scale model) and
configured scales (model, name, user slot), your workouts, own machines/exercises with their notes,
favourites and photos, plus an informational `premium` flag if you bought
"remove ads". Never uploaded: band key, scale bind keys / PINs / pairing keys, Bluetooth addresses,
Health Connect data, device-specific settings. Legal basis: your explicit consent (GDPR art. 6(1)(a) and 9(2)(a)).
Kept until you delete it: **Profile → Account and cloud backup → "Delete my cloud data and
account"** permanently deletes the cloud copy and the Firebase account (phone data is kept), or email
us; see [globalfitness-app.web.app/delete-account.html](https://globalfitness-app.web.app/delete-account.html).

**Ads (Google AdMob):** Google may process device identifiers (such as the advertising ID), IP
address and ad interaction data under the [Google Privacy Policy](https://policies.google.com/privacy).
In the EEA/UK we ask for consent with Google's User Messaging Platform; you can change it under
Profile → "Ad privacy options". Ad requests never include health or body data or
any targeting from the app. No ads are shown on the sign-in, purchase or consent screens.

**Google Play Billing:** purchases are processed by Google Play; we only receive the purchase state
and token, never payment details. The state is cached on the phone so offline users stay ad-free.

**Android Auto Backup:** if enabled, Android may back up the app database and settings to your Google
Drive (encrypted) and restore them on reinstall; the band key, the Health Connect cache and the machine
photos (kept in the app's cloud backup instead) are excluded.

**Sharing:** only with Google as service provider (Firebase, AdMob/UMP, Google Play). We do not sell
personal data. The app is not directed at children under 16.

**Music for today (Spotify):** the covers and names of the suggested Spotify playlists are only loaded
if you allow it (asked once at start, right after the ad consent; change it under Profile → Training
settings → "Show Spotify covers"; legal basis: consent). Until then nothing is requested from Spotify.
If allowed, the phone requests them from open.spotify.com (oEmbed) and Spotify's image servers
(`*.scdn.co`, `*.spotifycdn.com`), which receive your IP address and a generic app User-Agent
("Fortia/" and its version); no account or personal data is sent, only the playlist's public link.
They are cached on the phone and deleted after 30 days without being suggested, or when you turn the
covers or the suggestions off. Opening a playlist (your choice, no permission needed) takes you to
Spotify's app or website, under Spotify's own terms and privacy policy.

**Your rights:** access, rectification, erasure, restriction, objection, portability (CSV export in
the app) and withdrawal of consent: email enjoyyourtime.online@gmail.com. You may complain to the
Spanish Data Protection Agency (www.aepd.es) or your local authority.
