# Política de privacidad de GlobalFitness

> **BORRADOR PENDIENTE DE REVISIÓN.** Este texto es un primer borrador técnico, no asesoramiento
> legal. Antes de publicarlo (ficha de Google Play, sitio web) hay que revisarlo, completar los
> datos del responsable y comprobar que coincide con la versión publicada de la app y con la
> declaración de «Seguridad de los datos» de Google Play.

Última actualización: _pendiente_ · Contacto: **enjoyyourtime.online@gmail.com**

## 1. Quién es el responsable

GlobalFitness es una app para Android publicada por **Enjoy Your Time** (_completar nombre legal,
NIF y dirección_). Para cualquier cuestión sobre privacidad escribe a
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
- **Pulsera Xiaomi Smart Band**: la clave de emparejamiento de la pulsera se guarda solo en el
  teléfono y **nunca sale de él** (ni a la nube ni a la copia de seguridad de Android).

Estos datos se quedan en el teléfono hasta que los borres o desinstales la app.

## 4. Dispositivos Bluetooth

La app usa Bluetooth para leer las mediciones de tu báscula y de tu pulsera. En Android 11 o
anterior, Android exige el permiso de ubicación para buscar dispositivos Bluetooth; la app **no
usa tu ubicación** para nada más y no la guarda. Las direcciones Bluetooth de tus dispositivos
se guardan solo en el teléfono.

## 5. Health Connect

Si lo autorizas, la app **lee y escribe** en Health Connect los tipos de datos que concedas en la
pantalla de permisos de Android (por ejemplo peso, composición corporal o frecuencia cardiaca).
Puedes retirar el permiso en cualquier momento desde los ajustes de Health Connect.

- Los datos de Health Connect se usan **solo para mostrarte tu progreso y sincronizar tus
  mediciones** con otras apps que tú elijas.
- **No se suben** a la copia en la nube de GlobalFitness, **no se usan para publicidad**, no se
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
  básculas configuradas (modelo, nombre y número de usuario en la báscula). **No** se suben la
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
- **Cómo borrarla**: en la app, **Cuenta y copia en la nube → «Borrar mis datos de la nube y la
  cuenta»**. Se borran de forma definitiva tu copia en Firestore y tu cuenta de Firebase; los datos
  de tu teléfono no se tocan. También puedes pedirlo por correo a enjoyyourtime.online@gmail.com.

## 7. Publicidad (Google AdMob)

La versión gratuita muestra anuncios de **Google AdMob**. Para ello, Google puede tratar
identificadores del dispositivo (como el ID de publicidad), la dirección IP y datos de
interacción con los anuncios, según la
[política de privacidad de Google](https://policies.google.com/privacy) y
[cómo usa Google los datos de las apps de sus partners](https://policies.google.com/technologies/partner-sites).

- En el Espacio Económico Europeo y el Reino Unido usamos la **Plataforma de Mensajes de Usuario
  (UMP) de Google** para pedir tu consentimiento antes de mostrar anuncios. Puedes cambiar tu
  decisión en **Perfiles → «Opciones de privacidad de anuncios»**.
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
reinstalar o cambiar de móvil. **Quedan excluidas** la clave de la pulsera y la caché de Health
Connect. Puedes desactivarla en los ajustes de Android.

## 10. Con quién se comparten los datos

Solo con los proveedores necesarios para que la app funcione, que actúan como encargados del
tratamiento o como responsables independientes según el caso: **Google** (Firebase Authentication,
Cloud Firestore, AdMob/UMP y Google Play). No vendemos datos personales.

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

# GlobalFitness Privacy Policy (English)

> **DRAFT FOR REVIEW.** Technical first draft, not legal advice. Review it, complete the
> controller details and check it against the published app and the Google Play Data safety form
> before publishing.

Contact: **enjoyyourtime.online@gmail.com**

**Summary.** The app works **without an account** and keeps everything **on your phone** by default.
**Cloud backup is optional** (Google sign-in) and can be deleted, together with the account, from
inside the app. **Health data is never used for advertising** and is never sold. The free version
shows **Google AdMob** ads unless you buy "Quitar anuncios" (remove ads).

**Stored on the phone:** profile (name, sex, date of birth, height, target weight), measurements
(date, weight, bioelectrical impedance, source) and derived body-composition estimates, settings and
configured scales. The Xiaomi Smart Band pairing key stays on the phone only and is never uploaded or
included in Android backups.

**Bluetooth:** used to read your scale and band. On Android 11 and lower the location permission is
required by Android to scan for Bluetooth devices; location is not used for anything else.

**Health Connect:** with your permission the app reads and writes the data types you grant (e.g.
weight, body composition, heart rate). This data is only used to show your progress and sync with
apps you choose; it is **not uploaded to the GlobalFitness cloud backup, not used for ads, not sold
and not shared**. Use of information received from Health Connect follows the Health Connect
Permissions policy, including the Limited Use requirements.

**Optional Google account and cloud backup:** Firebase Authentication (user id, email, name, profile
photo) and Google Cloud Firestore in the **EU (europe-southwest1, Madrid)** store your profile (with a
random dataset id), measurements (incl. scale-reported body composition and scale model) and
configured scales (model, name, user slot), plus an informational `premium` flag if you bought
"remove ads". Never uploaded: band key, scale bind keys / PINs / pairing keys, Bluetooth addresses,
Health Connect data, device-specific settings. Legal basis: your explicit consent (GDPR art. 6(1)(a) and 9(2)(a)).
Kept until you delete it: **Account & cloud backup → "Borrar mis datos de la nube y la cuenta"**
permanently deletes the cloud copy and the Firebase account (phone data is kept), or email us.

**Ads (Google AdMob):** Google may process device identifiers (such as the advertising ID), IP
address and ad interaction data under the [Google Privacy Policy](https://policies.google.com/privacy).
In the EEA/UK we ask for consent with Google's User Messaging Platform; you can change it under
Perfiles → "Opciones de privacidad de anuncios". Ad requests never include health or body data or
any targeting from the app. No ads are shown on the sign-in, purchase or consent screens.

**Google Play Billing:** purchases are processed by Google Play; we only receive the purchase state
and token, never payment details. The state is cached on the phone so offline users stay ad-free.

**Android Auto Backup:** if enabled, Android may back up the app database and settings to your Google
Drive (encrypted) and restore them on reinstall; the band key and the Health Connect cache are
excluded.

**Sharing:** only with Google as service provider (Firebase, AdMob/UMP, Google Play). We do not sell
personal data. The app is not directed at children under 16.

**Your rights:** access, rectification, erasure, restriction, objection, portability (CSV export in
the app) and withdrawal of consent: email enjoyyourtime.online@gmail.com. You may complain to the
Spanish Data Protection Agency (www.aepd.es) or your local authority.
