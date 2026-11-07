# NewTube — Novedades

Cambios visibles para el usuario, en español. El historial completo de
versiones anteriores está en [CHANGELOG.md](CHANGELOG.md) (en inglés).


## 1.17.1 — 22-10-2026 — Edición Pepe Viyuela

"Algo tropieza con la silla, se levanta, y la calidad sigue donde la dejaste."
Homenaje ficticio a Pepe Viyuela, para un decodificador que se cae sin
llevarse tus ajustes por delante.

### Corregido

- **Si el decodificador de vídeo no arranca, ya no baja tu calidad para
  siempre.** Cuando el decodificador del móvil no podía arrancar (por ejemplo
  justo al encender la pantalla, o si otra app lo tenía ocupado), NewTube
  pasaba ese vídeo a 1080p AVC para recuperarse y lo guardaba como tu
  formato, así que todos los vídeos siguientes quedaban limitados. Ahora el
  cambio solo vale para el vídeo que falló; el siguiente vuelve a tu
  elección. Lo mismo con el audio. Gracias al arreglo de SmartTube (#6326).

## 1.17.0 — 20-10-2026 — Edición Florentino Fernández

"Ahora cada vídeo lleva la cara de su canal, y ninguna es una imitación."
Homenaje ficticio a Florentino Fernández, para los avatares de los canales en
Inicio y Suscripciones.

### Nuevo

- **Avatares de los canales en Inicio y Suscripciones.** Cada vídeo muestra
  el avatar de su canal junto al título, como en YouTube. Toca el avatar para
  ir directo al canal; toca en cualquier otro sitio de la tarjeta para
  reproducir. Los avatares se recuerdan, así que la próxima vez aparecen al
  momento. Los mixes y las listas no llevan.

### Corregido

- **Un doble toque rápido en play/pausa reproduce o pausa**, como en YouTube.
  Con los controles ocultos, el segundo toque los volvía a esconder.
- **Traducciones:** el rumano ya cubre casi todas las pantallas propias de la
  app, gracias a quienes traducen en
  [Weblate](https://hosted.weblate.org/projects/newtube/).

## 1.16.2 — 19-10-2026 — Edición Dani Rovira

"Ocho apellidos vascos no, ocho filas en un menú, y ahora se llega a todas."
Homenaje ficticio a Dani Rovira, para los menús del reproductor que se quedan
quietos con el móvil en horizontal y los vídeos que se ven nítidos antes.
(La 1.16.1 se etiquetó pero nunca se publicó; sus arreglos van aquí.)

### Corregido

- **Los vídeos verticales se ven en 1080p.** Se quedaban en 480p, pusieras
  la calidad que pusieras.
- **La calidad máxima llega antes.** El arranque en baja calidad se sustituye
  en cuanto la conexión da para más, y el 1080p aparece en unos 5 s con Wi-Fi
  en lugar de unos 10.
- **Sin tope de 720p en la página del vídeo** cuando los "Datos en segundo
  plano" de NewTube están desactivados con datos móviles: Android lo comunica
  igual que el Ahorro de datos, así que bajaba la imagen a quien solo quería
  decir "no en segundo plano".

- **Los menús del reproductor ya no se mueven en pantalla completa.** El menú
  de ajustes ya no da un salto un par de segundos después de abrirse: las
  barras del sistema siguen ocultas y los controles de detrás no se mueven
  hasta que lo cierras.
- **"Más" se desplaza en horizontal.** Se llega a todas las filas, la última
  que cabe sale cortada por la mitad para que se note que la lista sigue, y
  arrastrar la lista ya no cierra el menú.
- **Calidad, Subtítulos y Velocidad** dejan fijos el título (y la fila de
  abajo) mientras la lista se desplaza, y al pasar de un menú al siguiente
  hay un fundido, sin que el vídeo asome en medio.
- **La fila de Calidad muestra lo que se está reproduciendo** ("Automática
  (720p)") en lugar de "Automática (1080p60)" siempre.
- Detalles: los menús abiertos del todo conservan las esquinas redondeadas, el
  de Repetición ya no tiene una barra de título vacía y girar el móvil con un
  menú abierto lo cierra.

## 1.16.0 — 19-10-2026 — Edición Andreu Buenafuente

"Buenas noches, y bienvenidos a una versión en la que la pantalla se pone
negra a propósito." Homenaje ficticio a Andreu Buenafuente, para el negro
puro, los vídeos verticales de pie y un minuto que ya no tartamudea.

### Nuevo

- **Negro puro (#73, #74).** Configuración › General › Negro puro pone el
  tema oscuro en negro (#000000) para pantallas OLED, también cuando el tema
  sigue al del sistema. Los paneles y tarjetas quedan un tono más claros para
  que se sigan distinguiendo. Se aplica al momento, sin reiniciar.
- **Los vídeos verticales van a pantalla completa de pie (#69).** La
  pantalla completa (botón o deslizar hacia arriba) en un vídeo vertical
  ahora llena la pantalla en vertical en lugar de girar a horizontal con
  barras negras. Los vídeos horizontales siguen girando.

### Corregido

- **La app vuelve a abrir en Android 7.0 (#78).** En todos los móviles con
  Android 7.0 NewTube se cerraba nada más abrirlo, desde la primera versión
  pública.
- **Sin pausa ni rebobinado al minuto (#84).** Cuando YouTube corta una
  sesión a los 60 segundos, el vídeo ya cargado sigue sonando mientras
  NewTube consigue un enlace válido para el mismo stream, así que no hay
  parón ni salto atrás. Si eso falla, la recuperación de siempre retoma en el
  segundo exacto.
- **⏮ vuelve atrás aunque haya SponsorBlock.** Tras reiniciar el vídeo, un
  segundo toque vuelve al anterior aunque SponsorBlock se haya saltado una
  intro, y el vídeo anterior sigue donde lo dejaste.
- **Pantalla completa en Android 11 (#68).** El primer toque muestra los
  controles y la barra de gestos ya no se queda en pantalla.
- **Pistas de audio mejor etiquetadas:** se distinguen "original",
  "doblada" y audiodescripción, y los subtítulos traducidos automáticamente
  ya no salen de una pista doblada automáticamente. La búsqueda reintenta con
  más fiabilidad (arreglos de SmartTube).
- **Traducciones:** las pantallas propias de la app ya están en ruso y chino
  simplificado, y a medias en rumano, gracias a quienes traducen en
  [Weblate](https://hosted.weblate.org/projects/newtube/).

## 1.15.3 — 13-10-2026 — Edición Berto Romero

"No cambia nada, y esa es la noticia." Homenaje ficticio a Berto Romero para
una versión que no vas a notar: un cambio de compilación camino de F-Droid.

### Cambiado

- **Una biblioteca menos dentro de la app.** Los vídeos se siguen cargando con
  el mismo motor Cronet integrado (HTTP/3 y HTTP/2), ahora mediante una copia
  propia del código Cronet de media3, así que la app ya no lleva una
  biblioteca que declara Google Play Services. No cambia nada en cómo cargan
  los vídeos.
- **Las traducciones están abiertas en [Weblate](https://hosted.weblate.org/projects/newtube/).**

## 1.15.2 — 13-10-2026 — Edición Joaquín Reyes

"Esto es un vídeo corto, ¿vale? Pues aquí no hay." Homenaje ficticio a
Joaquín Reyes para un parche del mismo día: canales sin Shorts y unos cabos
sueltos de la 1.15.1.

### Arreglado

- **Nada de Shorts en las páginas de canal (#63).** La tarjeta de acceso a
  Shorts de la última pestaña de un canal ya no está (abría una página en
  blanco), y esa pestaña ahora se llama "Más" en vez de un segundo "Vídeos".
- **Descargas: un audio descargado a medias solo se reanuda si es del mismo
  idioma.** Una descarga parada a mitad podía seguir con otra pista de audio.
- **Los enlaces youtube.com/@usuario abren el canal,** y un canal abierto
  desde un enlace muestra su nombre.
- **Con un menú abierto sobre el minirreproductor, al pausar la pantalla se
  puede apagar.**
- **"Quitar de la lista" en tus propias listas, Ver más tarde incluida
  (#36),** con Deshacer.

## 1.15.1 — 13-10-2026 — Edición José Mota

"Si hay que salir de pantalla completa se sale, pero salir pa'ná es
tontería." Homenaje ficticio a José Mota para una versión de arreglos de lo
que ya tendría que funcionar. ¿Apagar la pantalla cuando el vídeo acaba?
Antes era "hoy no, mañana"; ahora es hoy. Y el doblaje que eliges se queda en
ese vídeo, que las gallinas que entran por las que salen.

### Arreglado

- **Salir de pantalla completa en tabletas (#44, #60).** En tabletas con
  Android 16, Atrás, el botón de pantalla completa y deslizar hacia abajo no
  hacían nada, y el reproductor se quedaba en pantalla completa. Ahora en una
  tableta la pantalla completa no depende de girar la pantalla: funciona en
  vertical y en horizontal.
- **Imagen en imagen en tabletas.** El vídeo ocupa bien la ventana pequeña, y
  al volver queda como lo dejaste.
- **La pantalla se puede apagar cuando el vídeo acaba o está en pausa
  (#50).** El reproductor la mantenía encendida mientras estuviera abierto.
- **Pista de audio: lo que eliges vale solo para ese vídeo (#52, #53).** Un
  doblaje elegido una vez te seguía a todos los vídeos doblados. Ahora el
  siguiente vídeo empieza en su idioma original, como en YouTube.
- **Las descargas mantienen el audio original (#29),** también tras
  Reintentar o al reanudar. A veces llegaban dobladas, por ejemplo en árabe.
- **El ajuste "Títulos originales" vuelve a funcionar (#45).**
- **Los menús de las listas ya no ofrecen "Bloquear canal" ni "Añadir/quitar
  canal del grupo de suscripción" (#56).** En una lista no servían para nada.

### Privacidad

- **La copia de seguridad de Android y la transferencia a otro móvil ya no
  copian tu inicio de sesión.** NewTube restaurado o transferido empieza sin
  sesión; tus ajustes sí viajan.
- **El zip de Copia de seguridad de la app ya no lleva los
  tokens de inicio de sesión.** Al restaurar una copia hay que volver a
  iniciar sesión.

### Otros

- Solicitud a F-Droid: el nombre de la app en el código y el bloque de firma
  del APK ya pasan las comprobaciones de F-Droid. En la app no cambia nada.

## 1.15.0 — 02-10-2026 — Edición Los Morancos

"¿Desliza pa'rriba? — ¡Pa'rriba! ¿Y ahora pa'bajo? — ¡Pa'bajo!" Homenaje
ficticio a Los Morancos y sus números de hermanos, para una versión en la que
cada gesto tiene gemelo: arriba a pantalla completa y abajo para salir,
arriba y abajo para el brillo y el volumen, a los lados para avanzar. Y la
configuración, por fin, cabe en el bolsillo.

### Nuevo

- **Gestos en el reproductor (#12).** Desliza el vídeo hacia arriba para
  verlo a pantalla completa, y hacia abajo por el centro para salir. A
  pantalla completa, desliza arriba o abajo por la izquierda para el brillo y
  por la derecha para el volumen, con una píldora que marca el nivel.
  Desliza de lado sobre el vídeo para avanzar o retroceder. El brillo es solo
  del reproductor: el resto del móvil mantiene el suyo. Configuración →
  Reproducción → Gestos los desactiva.
- **Buscador en Configuración.** Una barra arriba del todo abre su propia página;
  los resultados salen mientras escribes, también con palabras que el ajuste
  no lleva en el nombre ("subtítulos", "oscuro"), y cada resultado abre su
  página con la fila resaltada.

### Cambios

- **Configuración en páginas cortas, como la de YouTube (#2).** La configuración
  venía de la app de TV: 25 pantallas y más de mil filas. Ahora son 16
  páginas cortas, y cada opción es una fila que muestra su valor. Se van las
  opciones que el móvil no usaba, la copia en Google Drive (su inicio de
  sesión nunca funcionó en móviles), la importación de ajustes y la
  contraseña de ajustes. El modo infantil y la contraseña de inicio quedan
  desactivados, con una fila solo para apagarlos.

### Arreglado

- **Pantalla completa con un idioma o país de la app (#17).** El vídeo
  salía recortado y la barra de progreso fuera de la pantalla, y al volver
  del minirreproductor se veía la barra de estado.
- **El idioma de la app se mantiene con un tema o tamaño forzado.** Algunas
  pantallas salían en el idioma del móvil hasta reabrirlas.

## 1.14.1 — 2026-10-01 — Edición Pepe Viyuela

«¿Subirlo? —Si nunca estuvo bajo, era la app.» Un homenaje inventado a Pepe
Viyuela y sus peleas con los objetos de casa, para una versión que por fin le
gana una al botón del volumen.

### Arreglado

- **Los vídeos suenan tan alto como en la app de YouTube.** El «Ajuste
  automático del volumen» bajaba casi todos los vídeos a la mitad (6 dB).
  Ahora hace lo mismo que YouTube: solo baja un vídeo que suena más fuerte que
  el nivel de YouTube, y justo lo que le sobra. El volumen general llega como
  máximo al 100 %, porque los valores más altos no subían nada.
## 1.14.0 — 01-10-2026 — Edición Cruz y Raya

«¿Y esto qué es, una raya? —Una raya que te sigue el dedo.» Homenaje ficticio
a Cruz y Raya y a sus sketches de pareja, para una versión de rayas bien
trazadas: la barra de progreso sigue tu dedo, los arrastres se notan bajo el
dedo, los nombres de las pestañas de un canal caben en una sola raya y Acerca
de estrena una estrella y un botón para compartir.

### Nuevo

- **Dale una estrella y comparte NewTube.** Ajustes → Acerca de termina con
  dos filas: «Dale una estrella a NewTube en GitHub» abre la página del
  proyecto, y «Compartir NewTube» abre el menú de compartir del teléfono con
  una frase sobre la app y el enlace a su web. Nada te pide hacer ninguna de
  las dos cosas.

### Cambiado

- **La barra de progreso sigue tu dedo.** El punto se mueve tanto como tu
  dedo en vez de saltar debajo de él, y un arrastre hasta el principio ahora
  llega a 0:00 (el final se alcanza justo antes del borde de la pantalla). Un
  toque simple en la barra ya no salta, igual que en YouTube, y «Suelta para
  cancelar» ya no se queda pegado.
- **Arrastres que se notan.** Deslizar el vídeo hacia abajo para minimizarlo,
  deslizar el mini reproductor para cerrarlo y tirar para actualizar hacen
  clic bajo el dedo cuando pasan el punto sin retorno, y el vídeo sigue más de
  cerca al dedo hasta ahí. Al soltar, aterriza con un pequeño rebote.

### Arreglado

- **Los nombres de las pestañas de un canal caben en una línea.** Los nombres
  largos de secciones en la página de un canal (como «WING IT! Production Logs
  (Pet Projects)») ya no se parten en dos líneas; la pestaña toma el ancho de
  su nombre, como en YouTube.

## 1.13.0 — 30-09-2026 — Edición Faemino y Cansado

«Tú dices una cosa, yo te contesto otra, y cada uno a su capítulo…» Homenaje
ficticio a Faemino y Cansado y a sus diálogos imposibles, para una versión en
la que por fin se puede contestar. Escribe comentarios y respuestas, encuentra
los capítulos de cada vídeo, mantén pulsado para ir a 2x y estrena una barra de
progreso como la de YouTube.

### Nuevo

- **Escribe comentarios.** Con la sesión iniciada, «Añade un comentario…»
  encabeza el panel de comentarios y cada comentario tiene un botón Responder
  (empieza la respuesta con el @nombre de la persona). Lo que publicas aparece
  arriba al momento. Tus comentarios tienen un menú ⋮ con Eliminar, previa
  confirmación. Un comentario a medias te espera hasta que cambias de vídeo, y
  si falla la publicación la app dice por qué y conserva el texto.
- **Capítulos** ([#13](https://github.com/aleixrodriala/newtube/issues/13)).
  Los capítulos de un vídeo aparecen en la barra de progreso como pequeños
  cortes, y el nombre del capítulo actual va detrás del tiempo («1:10 /
  4:26:52 · Introducción ›»). Tócalo para ver la lista completa con un
  fotograma de cada capítulo y toca uno para saltar a su inicio.
- **Mantén pulsado para ir a 2x.** Deja el dedo sobre el vídeo para verlo a
  doble velocidad; al soltar vuelve a tu velocidad.

### Cambiado

- **Una barra de progreso como la de YouTube.** Fina, de todo el ancho del
  vídeo, en su borde inferior, con un punto más pequeño y fácil de agarrar:
  un toque justo encima o debajo la coge, y un arrastre desde el borde de la
  pantalla la mueve en vez de ir atrás. Con los controles ocultos, una línea
  fina sigue mostrando por dónde vas. Mientras la
  arrastras, el resto de controles se aparta, una etiqueta muestra el tiempo
  y el capítulo bajo el dedo, una vibración suave marca cada capítulo y, si
  vuelves a donde estabas, se queda ahí: suelta para cancelar.
- **Un reproductor que responde al tacto.** Reproducir y pausa se
  transforman uno en otro; «Me gusta», «No me gusta» y Suscribirse dan una
  vibración corta; los botones que no reaccionaban al pulsarlos ahora lo
  hacen, y tocar la pestaña en la que estás vuelve el feed arriba. Las
  vibraciones siguen el ajuste de vibración al tocar del teléfono.

## 1.12.0 — 30-09-2026 — Edición Paco Martínez Soria

«Cada cosa en su sitio, y con la luz encendida…» Homenaje ficticio a Paco
Martínez Soria y al espíritu de sus hombres de pueblo abriéndose camino en la
gran ciudad, para una versión que pone las cosas donde uno las busca. Tema
claro, comentarios bajo el vídeo, Atrás que minimiza, un Inicio que no se
acaba y adiós a los Shorts.

### Nuevo

- **Tema claro** ([#8](https://github.com/aleixrodriala/newtube/issues/8)).
  Ajustes → Interfaz de usuario → Tema: Predeterminado del sistema, Claro u
  Oscuro. El reproductor sigue oscuro en los dos, como el de YouTube. Las
  instalaciones nuevas siguen al sistema; al actualizar desde una versión
  anterior se mantiene el tema oscuro hasta que lo cambies. Cambiarlo no para
  el vídeo.
- **Los comentarios, bajo el vídeo.** Se abren en un panel que sube por
  debajo del vídeo mientras sigue sonando, ordenados por Destacados o Más
  recientes. Las respuestas se abren en su propia página y Atrás te devuelve a
  donde estabas; los comentarios largos se pliegan a las cuatro líneas con
  «Leer más»; las marcas de tiempo y los enlaces se pueden tocar. Para
  cerrarlo, arrástralo hacia abajo o pulsa Atrás.
- **«No me interesa» y «No recomendar canal»** vuelven al menú de las
  tarjetas de Inicio con la sesión iniciada
  ([#1](https://github.com/aleixrodriala/newtube/issues/1)). La tarjeta sale
  de Inicio cuando YouTube lo acepta; si no, un mensaje lo dice y la tarjeta
  se queda.
- **La pista de audio, en su propia fila.** En los vídeos en más de un idioma
  (doblados o doblados automáticamente), los ajustes del reproductor muestran
  «Pista de audio» bajo Calidad, como YouTube, y Calidad solo lista
  resoluciones. Los vídeos empiezan en su idioma original; el idioma que elijas
  se mantiene en el siguiente vídeo que lo tenga.

### Cambiado

- **Atrás minimiza el reproductor** al minirreproductor, como YouTube. El
  gesto de atrás lo previsualiza, y a pantalla completa Atrás primero sale de
  ella. El botón de arriba a la izquierda es ahora una flecha hacia abajo,
  «Minimizar». Desliza el minirreproductor hacia un lado para cerrarlo.
- **Más suave, igual de rápido.** El vídeo crece desde la tarjeta que tocaste,
  los controles responden al primer toque y el minirreproductor se abre sin
  parpadeo. Las tarjetas de carga brillan, también al abrir la app en frío (en
  vez de las tarjetas de la última vez, que luego saltaban) y en la búsqueda
  (en vez de un círculo girando). Cambiar de pestaña ya no baraja las
  tarjetas.
- **Sin Shorts.** Los Shorts ya no aparecen en ningún sitio: Inicio,
  Suscripciones, Historial, búsqueda, canales, listas y A continuación. Los
  canales no tienen pestaña de Shorts, la reproducción automática se los salta
  y los ajustes de Shorts ya no están. Un enlace a un Short que te pasen se
  sigue abriendo en el reproductor normal.
- **Sin sección de Notificaciones.** YouTube niega su bandeja de
  notificaciones al inicio de sesión tipo TV que usa NewTube, así que la
  sección solo podía quedarse vacía. Se van ella y su ajuste.
- **Adiós al bloqueo de rotación** de los ajustes del reproductor. A cambio,
  después de pasar a pantalla completa con el botón, la rotación vuelve al
  móvil (con la rotación automática activada): pon el móvil en vertical y sale
  de pantalla completa, como en YouTube. Los vídeos abiertos desde otra app
  también giran.
- **«Reproducir en segundo plano» tiene dos opciones:** Imagen en imagen y
  Solo audio.

### Arreglado

- **«Solo audio» ya no encoge el vídeo en imagen en imagen** al ir a Inicio.
  El audio sigue sonando, con sus controles en la notificación.
- **Inicio sigue cargando.** Con la sesión iniciada, Inicio se paraba al rato
  y solo al recargar salían más vídeos. Ahora cada estantería de Inicio va
  continuando por turnos mientras bajas, y luego se vuelve a pedir Inicio para
  traer vídeos nuevos (sin sesión en un emulador: 124 vídeos hasta el final,
  ahora 261). Suscripciones e Historial siguen más allá de las páginas que eran
  todo Shorts, A continuación carga su segunda página (60 vídeos en vez de 30)
  y los resultados de búsqueda cargan más al bajar (se quedaban en la
  primera página).
- **La imagen en imagen desde el menú del reproductor se queda en imagen en
  imagen** en vez de volver sola al reproductor completo.
- **Tocar el vídeo que ya está sonando** (en el minirreproductor o en imagen
  en imagen) lo devuelve tal como estaba, en vez de volver a cargarlo.
- **Las versiones nuevas aparecen solas.** La fila «Actualización disponible»
  y el punto de la pestaña Tú solo salían cuando NewTube arrancaba desde cero
  y su última comprobación tenía más de 12 horas, así que una versión nueva
  podía pasar un día o más sin avisar si no tocabas Buscar actualizaciones.
  Ahora NewTube lo comprueba en silencio cada vez que vuelves a la app, como
  mucho una vez por hora. Se nota a partir de la próxima versión: la 1.11.0
  encuentra esta como antes, o con Buscar actualizaciones.

### Sigue limitado

- **Buscar con el teclado abierto y el minirreproductor a la vista** deja un
  hueco vacío abajo.
- **Tema claro en Android 11 y anteriores:** la pantalla de arranque sigue el
  tema del sistema, no el que elegiste.
- **Algunos vídeos solo se ven en la app de YouTube o tras pagar** (películas
  de pago, algo de música).

## 1.11.0 — 29-09-2026 — Edición Tony Leblanc

«Siempre hay una manera de entrar…» Homenaje ficticio a Tony Leblanc y al
espíritu de sus pícaros con recursos, para una versión que encuentra el camino
hasta cada vídeo. Los vídeos ya no se cortan al minuto, los vídeos para niños
se reproducen, los vídeos empiezan antes y las actualizaciones llegan en una
sola hoja.

### Arreglado

- **Los vídeos ya no acaban al minuto con «Unknown source error».** En
  algunas sesiones anónimas, YouTube solo sirve el primer minuto del vídeo a
  los servicios que NewTube consulta primero y rechaza el resto, también al
  saltar o retomar más allá de ese minuto. NewTube volvía a pedirlo a esos
  mismos servicios hasta rendirse. Ahora reconoce ese rechazo, lo recuerda y
  sigue con un servicio que no lo corta (el reproductor insertado, el de TV o,
  como último recurso, una versión en baja resolución). Además estrena una
  identidad anónima nueva para reproducir, así los siguientes vídeos van con
  normalidad; tu Inicio conserva la suya. Probado con rechazos reales en
  emuladores y uno simulado en dos móviles: el vídeo se paró alrededor de un
  segundo en los móviles (unos segundos en los emuladores, más lentos), a veces
  repitió unos segundos, y siguió.
- **Los vídeos para niños se reproducen.** Paraban con «Unknown source error»
  ([#5](https://github.com/aleixrodriala/newtube/issues/5)): el servicio de
  YouTube al que NewTube pregunta primero los rechaza, y los siguientes también,
  o retenían el vídeo por su anuncio previo y se rendían. Ahora NewTube pregunta,
  en un orden medido en móviles reales, al que sí los sirve, y espera el anuncio
  en vez de fallar. En un Pixel 9 con datos móviles se reprodujeron 25 vídeos
  para niños de 25, y empezaron en 1,3 s (mediana).
- **Con cuenta, los vídeos empiezan al primer o segundo intento.** NewTube
  preguntaba primero a una ruta que YouTube ya no sirve a las apps con cuenta, y
  luego a muchas otras. En una prueba con la misma cuenta, la versión anterior
  reprodujo 3 de los 7 primeros vídeos y necesitó 128 peticiones; esta
  reprodujo los 13 de los 16 vídeos de prueba que se pueden ver (los otros tres
  son de miembros, una película de pago y un vídeo solo de música) con 40.
- **Los vídeos que no se pueden ver lo dicen enseguida.** Un vídeo eliminado,
  con restricción de edad (y no insertable) o solo para miembros para en su
  propio motivo tras dos a cuatro peticiones en vez de probar todos los
  servicios, también en español. Un vídeo privado ya no hace que NewTube dé por
  bloqueados los siguientes, y la reproducción automática para tras dos vídeos
  seguidos que no se pueden ver, en vez de saltar por una lista de ellos.
- **La sincronización del historial se reintenta** si falla la primera con tu
  cuenta (hasta tres intentos), en vez de descartarse.
- **Los vídeos panorámicos se ven bien.** En vídeos más anchos que 16:9
  (películas, 2,35:1) los controles a pantalla completa oscurecían solo una
  franja 16:9, con bordes marcados sobre la imagen, y el minirreproductor
  estiraba el vídeo a su tarjeta
  ([#9](https://github.com/aleixrodriala/newtube/issues/9)). Ahora el
  oscurecido cubre todo el vídeo, los controles no quedan bajo la cámara y el
  minirreproductor muestra el vídeo con su forma.

### Más rápido

- **Los vídeos empiezan antes en el uso diario** (con la app ya instalada y
  abierta antes). En un móvil de 2018 (Xiaomi Mi 8, wifi), la imagen de un
  vídeo normal aparece en unos 0,55 s en vez de 0,8 s, la de uno para niños en
  unos 1,05 s en vez de 8,3 s y la de uno para mayores de 18 en unos 1,1 s en
  vez de 3,8 s cuando YouTube no pone anuncio previo; los directos empiezan
  como antes. La comprobación de seguridad de YouTube en segundo plano espera
  a que tu vídeo esté en pantalla, la imagen de carga se quita con el primer
  fotograma y NewTube guarda lo que ya averiguó del reproductor de YouTube en
  vez de rehacerlo con cada vídeo.
- **El primer vídeo tras una actualización del reproductor de YouTube** (cada
  pocos días, y nada más instalar) empezó entre 1,1 y 2,8 s antes en pruebas de
  esa situación si es normal o en directo: NewTube ya no espera a comprobar el reproductor nuevo para
  pedir un vídeo que no lo necesita.
- **Menos peticiones a YouTube.** Un segundo vídeo del mismo canal para niños,
  un directo tocado desde una lista y un vídeo para niños que se recupera de un
  fallo necesitan una sola petición a YouTube.

### Cambiado

- **Las actualizaciones, en una sola hoja.** Ajustes → Acerca de → Buscar
  actualizaciones, la nueva fila arriba de la pestaña Tú y un punto en esa
  pestaña abren la misma hoja: la versión, su tamaño y las novedades, y luego
  Actualizar. La descarga muestra su progreso, se puede cancelar, sigue aunque
  salgas de la app, y el instalador de Android se abre directamente al acabar.
  La primera vez, la hoja explica el permiso de Android para «instalar apps
  desconocidas». Después de actualizar, NewTube te lo dice y enseña las
  novedades. Buscar ya no descarga la actualización por su cuenta.

### Sigue limitado

- **Algunos vídeos solo se ven en la app de YouTube o tras pagar** (películas
  de pago, algo de música), y siguen parando con el «no disponible» de YouTube.
- **El cambio tras un rechazo al minuto se nota:** el vídeo se para un momento
  y puede repetir unos segundos antes de seguir.

## 1.10.4 — 28-09-2026 — Edición Lina Morgan

«Agradecidos y actualizados…» Homenaje ficticio a Lina Morgan y al espíritu de
sus despedidas desde el escenario, para una versión que responde a quien nos
escribió. El texto sigue el tamaño de letra que elegiste en el móvil, y «Buscar
actualizaciones» funciona.

### Arreglado

- **El texto sigue el tamaño de letra del móvil.** NewTube dimensionaba la barra
  superior, las pestañas, los menús y los ajustes con una densidad propia,
  heredada del diseño para TV de SmartTube, así que el tamaño de letra y de
  pantalla del móvil solo llegaban al feed de vídeos. Ahora el resto de la app
  sigue los dos (las etiquetas de las pestañas, solo el tamaño de pantalla, una
  regla de Material), y Ajustes → Interfaz de usuario → Escala de interfaz
  amplía todo por encima
  ([#3](https://github.com/aleixrodriala/newtube/issues/3)). Con los ajustes por
  defecto, la barra superior, las pestañas y los menús salen algo más grandes
  que antes (un 4% en un Pixel 9, un 18% en un móvil de pruebas con botones de
  navegación), del mismo tamaño que en las demás apps. Si habías subido la
  escala de interfaz para leer mejor, prueba un valor más bajo: ahora también
  amplía el feed.
- **Buscar actualizaciones funciona.** Ajustes → Acerca de → Buscar
  actualizaciones buscaba un archivo que las versiones de NewTube no publicaban,
  y fallaba con «Value Not of type java.lang.String»
  ([#4](https://github.com/aleixrodriala/newtube/issues/4)). Ahora cada versión
  lo lleva, la 1.10.2 y la 1.10.3 también pueden actualizarse desde la app, y si
  la búsqueda falla lo dice con palabras normales.

### Sigue limitado

- **Algunos vídeos para niños siguen parando con «Unknown source error»**
  ([#5](https://github.com/aleixrodriala/newtube/issues/5)). Ya sabemos por qué
  y cómo reproducirlos, y lo estamos midiendo en móviles reales antes de
  publicarlo.

## 1.10.3 — 28-09-2026 — Edición Martes y Trece

«¿Por dónde íbamos?…» Homenaje ficticio a Martes y Trece y al espíritu de sus
llamadas a la radio, para una versión que va de no perder el sitio. Los ajustes
recuerdan dónde estabas, la app deja de hablar de anuncios, el contador de «no
me gusta» pasa a ser opcional y los APK ya se compilan en los servidores de
GitHub.

### Cambios

- **El contador de «no me gusta» ahora es opcional.** Return YouTube Dislike es
  un servicio comunitario que sabe qué vídeos abres, así que NewTube ya no le
  pregunta salvo que actives Ajustes → Configuración del reproductor → Contador
  de no me gusta. Sin él, la página del vídeo muestra los «me gusta» de YouTube
  y un botón de «no me gusta» sin número, en vez de una estimación sacada de los
  «me gusta».
- **Las pantallas de enviar a la TV describen cada opción por lo que hace**
  (dónde se controlan la calidad y los subtítulos, qué app de la TV lo
  reproduce) y no por los anuncios.

### Arreglado

- **Los ajustes no pierden el sitio.** Marcar una casilla o elegir una opción a
  media página ya no te devuelve arriba del todo, y al volver de una subpágina
  regresas a donde estabas
  ([#2](https://github.com/aleixrodriala/newtube/issues/2)).
- **Android antiguos.** En móviles con una versión anterior a Android 10, una
  clase que solo existe desde Android 10 podía impedir que NewTube obtuviera el
  token que pide YouTube, y entonces todos los vídeos fallaban con «Can't get
  video info» (lo vio SmartTube en Android 7.1; es su arreglo).
- **Si la comprobación antibots de YouTube tarda en arrancar, ya no queda un
  generador de tokens a medias.** Si no está listo en 20 segundos, NewTube lo
  deja limpiamente y lo vuelve a intentar la próxima vez que necesite un token.

### Entre bastidores

- **Los APK se compilan en los servidores de GitHub** a partir del código de la
  etiqueta, con una atestación de compilación para cada archivo: `gh attestation
  verify <archivo>.apk -R aleixrodriala/newtube`. Además pesan unos 20 MB menos,
  porque la biblioteca del motor de JavaScript ya no lleva sus símbolos de
  depuración.

## 1.10.2 — 28-09-2026 — Edición Tip y Coll

«Se coge el móvil. Se va a Ajustes…» Homenaje ficticio a Tip y Coll y sus
lecciones paso a paso, para una versión que va de contarnos qué ha fallado. Llega
tras los avisos de «Unknown source error» en todos los vídeos al rato de estar
viéndolos (27 de septiembre, un Redmi Note 14 4G con HyperOS 3). En un emulador
con Android 16 no pasaba, y sin un ordenador no había forma de mandarnos el
registro.

### Mándanos lo que ha fallado

- **Ajustes → Acerca de → Enviar registro de diagnóstico.** Comparte un archivo
  de texto con el registro reciente de la app, más o menos la última hora.
  Incluye la sesión de antes de forzar el cierre, así que el fallo sigue ahí
  aunque reinicies la app. Delante va una cabecera corta: versión de la app,
  modelo del móvil y compilación de Android, si tienes la sesión iniciada, tipo
  de red e idioma de los subtítulos.
- **Qué entra y qué sale.** El registro dice qué vídeos y canales has abierto.
  Antes de escribir el archivo, la app quita contraseñas, tokens de sesión,
  cookies, correos, contraseñas del proxy, tokens PO y tu dirección IP de los
  enlaces de vídeo. No sale nada del móvil hasta que eliges con qué app
  compartirlo.

### Arreglado

- **Los secretos de la sesión no van al registro del sistema.** Ya no se
  registran el cuerpo de las peticiones OAuth ni el token de refresco (un
  arreglo de SmartTube), ni el código de dispositivo cuando falla el inicio de
  sesión con código de TV. Los mensajes del chat en directo, tampoco.

### Todavía limitado

- **El «Unknown source error» al rato aún no está arreglado.** No lo hemos
  podido reproducir; el registro de diagnóstico es cómo lo vamos a encontrar.
- La limpieza va línea a línea: un secreto partido en dos líneas del registro
  no se reconocería. Los primeros 240 caracteres de la página de error de un
  servidor de vídeo se quedan en el registro; es el texto de error genérico de
  YouTube.

## 1.10.1 — 27-09-2026 — Edición Gila

«¿Es el enemigo? Que se ponga… pero rápido.» Homenaje ficticio a Miguel Gila,
para una versión que va de coger antes el teléfono: los vídeos, la app y la
red. Reúne todo lo hecho desde la 1.9.0 (11 de septiembre): dos rondas de red
(24 y 25 de septiembre), una ronda de velocidad y fluidez medida en un Pixel 9
con Wi-Fi y 4G de Movistar, y un repaso completo de la interfaz del móvil. La 1.10.0 se
etiquetó pero no se llegó a repartir (ver abajo); esta es la que sale.

### Más rápida

Pixel 9, versiones de distribución, la de la época de la 1.9.0 frente a esta
(medianas; pocas muestras, un teléfono, un operador):

| | Wi-Fi | Datos móviles |
| --- | --- | --- |
| Abrir la app, primera pantalla | 397 → 240 ms | 458 → 241 ms |
| Inicio pintado del todo | 1,83 → 1,35 s | 2,05 → 1,56 s |
| Tocar un vídeo relacionado → primer fotograma | 554 → 497 ms | 1033 → 528 ms |
| Tocar un enlace compartido → primer fotograma | 952 → 664 ms | 8,96 s → ~0,84 s |
| Volver a un vídeo a medias → imagen | hasta 1,4 s → ~0,37 s | |

- **Los vídeos arrancan antes.** Los decodificadores siguen abiertos entre un
  vídeo y otro, las listas de códecs se leen al abrir la app y la respuesta de
  YouTube se procesa unas cuatro veces más rápido. La reproducción automática
  pide el siguiente vídeo 20 segundos antes del final.
- **Volver a un vídeo a medias es inmediato.** Se reanuda al principio del
  fragmento de vídeo más cercano, y el audio ya no descodifica los segundos que
  se salta.
- **La app abre antes y el Inicio se llena antes**, sin el parpadeo en blanco
  al cambiar el feed guardado por el nuevo. El Inicio carga sus primeras
  páginas de golpe y el resto a medida que bajas. El APK lleva su propio perfil
  de arranque, así que Android lo optimiza al instalar y no la noche siguiente.
- **Volver al vídeo que acabas de dejar** muestra sus relacionados al momento.

### Más estable con mala red

- **Servidores de vídeo atascados.** Algunas redes móviles se atascan con
  algunos servidores de vídeo de YouTube. La app cambia de conexión dentro de
  la misma petición, recuerda el problema por operador (también tras reiniciar)
  y los vídeos siguientes van directos por el camino que funciona en vez de
  esperar otra vez. Lo olvida cuando dos servidores distintos vuelven a
  responder.
- **Sin conexión:** el reproductor espera a que vuelva la red y lo intenta una
  vez, en lugar de reintentar a ráfagas. Una red que Android bloquea para la
  app ya no provoca una tormenta de reintentos. El Inicio espera más entre
  intentos mientras no hay red; las páginas de canal, subidas y listas muestran
  Sin conexión / Reintentar.
- **Bloqueos y comprobaciones anti-bot.** Cuando YouTube pone a prueba a los
  clientes anónimos, un móvil con sesión iniciada reproduce por una ruta de TV
  con la cuenta, y la app recuerda el bloqueo en vez de preguntar a todos los
  clientes en cada vídeo. Los vídeos con restricción de edad se reproducen por
  la misma ruta si la cuenta puede verlos. Un vídeo eliminado se detecta con
  tres respuestas iguales en vez de probar los once clientes. El cliente de
  reproductor insertado, que YouTube rechaza en todas partes (error 152-18), ya
  no se usa.
- **Los datos móviles se gastan en fluidez.** Con datos móviles el reproductor
  mantiene el búfer y la calidad completos; los límites para ahorrar datos solo
  se aplican con el Ahorro de datos de Android activado. El PiP y el
  minirreproductor siguen pidiendo solo lo que cabe en su ventana.

### Aspecto y sensaciones

- **Avisos de la app en lugar de los del sistema**, con Deshacer o Ver cuando
  ayuda (suscribirse, me gusta, no me gusta, descargar), por encima del
  minirreproductor.
- **Me gusta y No me gusta** muestran el pulgar relleno o en contorno en vez de
  ponerse rojos, se confirman con Deshacer, llegan a YouTube en el orden en que
  los tocaste y se deshacen con un aviso si no se pudieron guardar.
- **La página del vídeo no se mueve mientras carga**, muestra las visualizaciones
  y una fecha relativa («hace 4 días»), nombra las pistas de audio dobladas y
  avisa cuando no hay conexión.
- **Cambiar entre modo claro y oscuro no para el vídeo**, ni la pestaña Tú ni
  Ajustes; el tema oscuro se mantiene con el móvil en modo claro.
- **La búsqueda** distingue sin conexión, error y sin resultados, mantiene
  Reintentar y sugiere el historial que coincide en vez del historial entero.
- **Atrás desde cualquier pestaña vuelve al Inicio; los menús caben en
  horizontal;** las tarjetas del Historial local tienen miniatura; zonas táctiles
  más grandes y etiquetas para TalkBack; el menú de la tarjeta empieza por las
  acciones de todos los días (solo si nadie lo había personalizado).
- **Frecuencia de fotogramas automática y Control remoto salen de Ajustes**
  (son funciones de TV; se apagan una vez).
- Minimizar tras abrir un enlace compartido ya no enseña el escritorio un
  instante; un segundo enlace compartido ya no acaba en imagen en imagen; volver
  a descargar un vídeo cuya descarga borraste ya funciona.

### Sigue limitado

- La primera vez que un operador se atasca, un vídeo aún espera unos 7-8 s
  mientras la app lo aprende.
- Elegir la misma descarga dos veces mientras baja la pone en cola dos veces;
  con el móvil sin espacio, una descarga puede quedarse en «Terminando…».
- YouTube aún puede rechazar algunos vídeos y cuentas, y SABR sigue apagado.

## 1.10.0 — 26-09-2026

Etiquetada (`v1.10.0`) e instalada en el Pixel de pruebas, nunca enviada al
grupo de testers. Añadía un «aparcado» del minirreproductor: la X pausaba el
vídeo y dejaba una notificación para seguir durante 10 minutos. En el Android 17
del móvil de pruebas el sistema quitaba esa notificación en pausa al instante,
así que no quedaba nada que tocar, y se retiró en la 1.10.1 (la X vuelve a
cerrar el vídeo, como en la 1.9.0). Todo lo demás de la 1.10.0 sale en la 1.10.1.

## 1.9.0 — 11-09-2026 — Edición Chiquito

«¡Te das cuen! Ya se descargan.» Homenaje ficticio a Chiquito de la Calzada,
con vídeos que ahora viajan en el bolsillo. Esta versión reúne todo lo hecho
desde la 1.8.0 (8 de septiembre): el trabajo de SABR de la 1.8.1, el repaso de
interfaz del 8 de septiembre y la nueva función de Descargas.

### Descargas

- **Descarga cualquier vídeo** desde el menú de la tarjeta, la página de
  reproducción (nuevo botón Descargar) o el engranaje → Más. Elige la calidad
  (todos los escalones H.264 hasta 1080p, con el tamaño real) o solo el audio.
  Los directos no se pueden descargar.
- **Una pestaña Descargas** en la barra inferior muestra lo que hay en el móvil
  como tarjetas normales: progreso y porcentaje mientras se descarga, y después
  la duración y "144p · 67,1 MB". Toca para reproducir; mantén pulsado (o ⋮)
  para Compartir / Eliminar / Reintentar. Una tarjeta que aún no está lista se
  ve atenuada y lo indica.
- **Los vídeos descargados se reproducen como cualquier otro**, en el mismo
  reproductor y con la misma página de reproducción, también sin conexión. Si
  un vídeo no carga en línea y existe una copia descargada, se reproduce la
  copia.
- Los archivos van a `Movies/NewTube` (vídeo, MP4) y `Music/NewTube` (audio,
  M4A), visibles para la galería y cualquier gestor de archivos. Las descargas
  siguen en segundo plano con una notificación de progreso desde la que se
  pueden cancelar.

### Aspecto, paneles y ajustes

- **La app ya no es rosa donde nadie eligió rosa.** El color de acento seguía
  siendo el `#FF4081` de fábrica de Material, y pintaba todas las cabeceras de
  sección de Ajustes, cada casilla y cada punto de opción marcados, el cursor
  del buscador y tres ruedas de carga. Los controles de selección son blancos,
  las ruedas usan el rojo de la app y el icono de Cast conserva su azul propio
  mientras hay sesión.
- **Los paneles inferiores llegan al final de la pantalla.** Todos (engranaje,
  calidad, subtítulos, velocidad, comentarios, cuentas) se quedaban a la altura
  de la barra de gestos con una franja de otro color debajo. El marco se estila
  desde el tema y la superficie pasa por debajo de la barra de gestos. Los
  paneles que se miden respecto a la pantalla ya no heredan la altura de la
  orientación con la que se abrió la app.
- **Los menús contextuales atenúan toda la pantalla.** El fondo de los menús de
  tarjeta y de los selectores del reproductor empezaba debajo de la barra de
  estado y su panel flotaba por encima del borde inferior; ahora ambos llegan a
  los bordes.
- **El Aleatorio de una lista se queda en esa lista.** Un toque en Aleatorio
  dejaba en modo aleatorio todo lo que se reprodujera después. Ahora se limita
  a la cola que lo activó y termina con ella; la fila Aleatorio del reproductor
  muestra el estado real.
- **Fuera los ajustes que no hacían nada.** Esquema de color (nueve opciones
  tras un aviso de «reinicia la app»), velocidad del texto de la tarjeta, vista
  previa de la tarjeta y las casillas de estilo de tarjeta no tenían lector en
  el móvil. El título de la sección «En vivo» deja de gritar en mayúsculas.

### Sigue limitado

- Las descargas no tienen pausa: cancelar y reintentar, y el reintento reanuda
  las partes ya bajadas. No se descargan listas enteras, no se guardan los
  subtítulos y no se ofrecen escalones VP9/AV1 por encima de 1080p (no hay un
  muxer WebM de confianza en la plataforma). Unir un vídeo de 300 MB con su
  audio tarda alrededor de minuto y medio en un Pixel 9; la tarjeta dice
  «Terminando…» y todavía no se puede reproducir.
- Todo lo indicado en 1.8.1 y 1.8.0 sigue vigente: YouTube aún puede rechazar
  algunos vídeos y cuentas, y SABR sigue apagado.

## 1.8.1 — 08-09-2026

### SABR llega como fuente opcional, desactivada por defecto

- NewTube ya puede reproducir por SABR una respuesta de YouTube que llega **sin
  enlaces directos**: pistas de vídeo y audio con solo un punto de streaming.
  Los dos interruptores de SABR están en Ajustes y los dos vienen
  **desactivados**: "Reproducir vídeos sin enlaces directos" y "Preferir SABR
  aunque haya enlaces".
- **Por qué está desactivado.** Se hizo activado por defecto y se apagó antes de
  publicar, porque no llegó a rescatar ni un solo vídeo que no se reprodujera ya
  de otra forma. En siete aperturas normales, el cliente que usa NewTube siempre
  devolvió enlaces que funcionaban, así que la reserva nunca llegó a entrar. Y
  al forzarla, el servidor respondió a cada petición pidiendo que se recargara
  la página del vídeo, sin reproducir nada. Activarla tampoco sale gratis:
  NewTube deja de preguntar a más clientes por ese vídeo y gasta en SABR sus
  reintentos antes de probar otra cosa. Seguirá apagada hasta que consiga
  completar una reproducción.
- **Nada de lo que ves hoy cambia.** Los vídeos se abren igual que en la 1.8.0 y
  a la misma velocidad, y el error de reproducción que sí aparece de vez en
  cuando lo sigue resolviendo el reintento de cliente de siempre, no SABR.
- Para quien quiera probarlo: SABR gasta alrededor de un 11% menos de datos y
  tarda unos 66 ms más hasta el primer fotograma, medido en seis aperturas por
  fuente en Wi-Fi.
- **Un cuarto fallo: SABR descargaba el audio dos veces.** Cada petición de vídeo
  debía avisar al servidor de que el audio ya estaba descargado, pero ese aviso
  se ignoraba, así que el audio volvía a llegar junto a cada trozo de vídeo.
  Corregido. Hoy no cambia nada para nadie (SABR está desactivado), pero hacía
  que la vía experimental gastara alrededor de un 50% más de datos de lo
  necesario. Medido con datos móviles en cuatro vídeos, SABR ya gasta más o menos
  lo mismo que la vía normal en vez de bastante más.
- Por el camino se corrigieron otros tres fallos del propio SABR. La versión anterior
  no llegaba a recibir vídeo nunca, porque la fuente solo se ofrecía para
  respuestas de TV con sesión iniciada, justo el cliente cuyo servidor responde
  a todo con un HTTP 403 vacío. Además, una petición de vídeo tiene que nombrar
  su pista de audio acompañante y declararla ya descargada, y una respuesta sin
  vídeo a propósito (el servidor frenando a un cliente que va sobrado) es una
  espera, no un fallo.

### Sigue limitado

- Activada, la reserva no puede completar una reproducción, y ya sabemos por qué:
  para los clientes que usa, YouTube solo sirve el **primer minuto** del vídeo
  sin una atestación de dispositivo que NewTube no puede generar. Pasados unos
  60 segundos el servidor deja de enviar vídeo. Retomar un vídeo a medias
  empieza más allá de esa línea, y por eso fallaba de inmediato en las pruebas.
- La velocidad y el gasto de datos de SABR se midieron en un vídeo, una red y un
  móvil; todavía no hay datos sobre reproducciones largas, datos móviles ni
  batería.
- Todo lo indicado en la 1.8.0 sigue vigente, salvo que SABR ya no es solo
  código de prueba.

## 1.8.0 — 08-09-2026 — Edición Eugenio

*Parodia y homenaje no oficial. Novedades narradas con el humor seco de Eugenio.*

Saben aquell que diu que abre un vídeo… y le da tiempo a hacerse mayor.
Pues hemos estado trabajando en eso. En el vídeo. Lo de hacerse mayor sigue igual.

Esta versión reúne los diez commits y los cambios de las dependencias desde la
1.7.0 del 4 de agosto, más los últimos arreglos revisados para esta entrega.

### El vídeo, antes que las fotos

- Las miniaturas descargan una imagen del tamaño que necesitan, no un cartel de
  cine para luego encogerlo. Las recomendaciones se cargan por tandas, con menos
  descargas de imágenes simultáneas y un tiempo de espera adecuado para el móvil.
- La imagen de espera aprovecha la caché y evita otra descarga a máxima resolución.
  La interfaz y las tareas secundarias se coordinan con el arranque del vídeo.
  Si cambias de vídeo, lo abandonado ya no puede volver a colarse en pantalla.
- La calidad automática arranca con una estimación reciente de la conexión,
  descarta las antiguas y sube al comprobar que hay ancho de banda. Guarda las
  mediciones mientras reproduces y se adapta al cambiar de red. Si eliges una
  calidad manual, la respeta. No discute. Eso también es una mejora.
- La bajada automática de calidad tiene en cuenta el búfer elegido, para actuar
  antes de vaciarlo. Y la preparación en segundo plano ya no empieza a competir
  con un vídeo que acabas de abrir.
- Las conexiones se preparan con límites y pueden volver a intentarlo tras un
  fallo o un cambio de red. Los metadatos reutilizan el JSON ya leído: en una
  prueba densa pasamos de 76 análisis de texto a uno. No significa que el vídeo
  vaya 76 veces más rápido. Si fuera así, terminaría antes de pulsar Play.
- En las pruebas del Pixel con conexión limitada, la última mejora de calidad
  inicial recortó aproximadamente **0,4–0,7 segundos hasta empezar a reproducir**.
  Son muestras pequeñas y se empieza con menos resolución; no es una promesa
  para todos los vídeos, móviles o conexiones LTE.

### El túnel ya no es una residencia habitual

- Si Cronet se atasca al arrancar, la alternativa de transporte usa OkHttp. Las
  llamadas tienen límites adecuados y se aprovechan las conexiones abiertas.
- Ahora se distingue entre «YouTube ha respondido que no» y «no llega nada».
  Android podía decir que había Internet dentro del túnel. Muy optimista, Android.
  Los fallos de conexión siguen teniendo recuperación automática limitada.
- El cambio a otra red válida despierta la recuperación aunque Android no avise
  de que perdió la anterior. Un reintento cancelado no puede revivir un vídeo viejo.
  Se cancelan las peticiones y preparaciones abandonadas al cambiar de vídeo.
- Se recuerdan temporalmente los caminos que ya han fallado, también al reiniciar
  la app. Un vídeo indisponible no basta para dar por rota la ruta de la cuenta.
  La búsqueda del manifiesto de los directos evita viajes innecesarios.
- Si falla antes de empezar, aparece el motivo y Play permite reintentar. Se
  limpian las recomendaciones de una apertura denegada; una pantalla de reproducción
  abierta sin vídeo vuelve a Inicio en vez de quedarse mirando el 00:00.
- Los fallos de los metadatos no escupen errores técnicos encima del vídeo, el
  búfer no desactiva para siempre tus subtítulos y el Inicio tiene estado sin
  conexión y reintento. También se conservan las posiciones solicitadas válidas.
- La notificación y la pantalla de bloqueo comparten la descarga de la portada,
  descartan la del vídeo anterior y se actualizan cuando llega la correcta.
  Menos trabajo repetido. Las pausas, si puede ser, las pongo yo.

### Lo de la tele

- La selección recomendada prefiere **SmartTube emparejado**, después **Cast
  directo**, luego las apps guardadas sin identificar y, al final, **YouTube**.
  Si hay que pasar al YouTube de la tele, avisa de que puede haber anuncios.
  Si escoges una opción concreta, no te la cambia a escondidas.
- Una tele puede conservar sus distintas apps en una sola fila sin perder el
  emparejamiento con SmartTube. Los nombres ambiguos no mezclan dispositivos,
  y a una vinculación antigua no se le atribuye SmartTube por intuición.
- Hay una espera breve para encontrar opciones mejores y límites para conectar
  y comprobar que la reproducción arrancó. Pausar, desconectar o cambiar de tele
  cancela lo pendiente.
- Un solo botón **Vincular TV**, indicaciones más claras en español y un diálogo
  oscuro que explica cada app por separado. En SmartTube: **Ajustes → Control
  remoto**. Eliges la app e introduces los doce dígitos. Con once no. Es un
  código, no una aproximación.

### También hemos mirado al vecino SmartTube

- Incorporados sus arreglos del cómputo del búfer, del JSON de las peticiones y
  de los números demasiado grandes en los metadatos. El tiempo de espera ya no
  puede acabar siendo negativo por contar dos veces la misma pausa.
- Al adelantar o retroceder y quedarse cargando, vuelve a activarse la vigilancia
  del atasco. Si está pausado o ya puede reproducir, no inventa un problema.
- Esta revisión añade la actualización de títulos sin pisar el horario de los
  próximos estrenos, la limpieza de la caché del historial tras borrar una
  entrada para permitir añadirla al volver a verla, y los títulos de listas
  remotas aunque todavía no exista una lista guardada localmente.
- La caché interna de preparación guarda juntos el contenido, la clave y sus
  datos. Si se interrumpe una escritura, conserva la entrada válida anterior.
  No hace falta borrar datos ni volver a configurar la cuenta para actualizar.
- Revisados SmartTube `f23438b`, MediaServiceCore `0b01a017` y SharedModules
  `86f0327`. Los cambios exclusivos de TV, los parches retirados y las opciones
  incompatibles no se han copiado sin más. El detalle está en el
  [registro de la versión](docs/releases/1.8.0.md).

### Debajo del capó, y la letra pequeña

- Nuevas pruebas de arranque, recuperación, portadas, listas, casting, cachés y
  cancelaciones; herramientas que limitan toda la red de la app y simulan cortes;
  pruebas locales de vídeo y mediciones con compilaciones de distribución.
- Se incluye un perfil de arranque, pero su comparación no demostró una mejora
  de velocidad. La precarga del siguiente vídeo sigue **desactivada**: la prueba
  real no pasó. **SABR no estaba implementado en el reproductor de
  producción** en esta versión; se corrige y se puede activar en la 1.8.1.
- No prometemos acabar con todos los 403 ni con las restricciones de YouTube.
  La reproducción pública puede acabar sin la cuenta, aunque tus listas y
  suscripciones sigan conectadas; los vídeos restringidos y el historial del
  servidor pueden seguir fallando.
- Cast directo sigue necesitando el móvil conectado y no reproduce directos ni
  subtítulos. La nueva interfaz se comprobó en el Pixel; falta comprobar de punta
  a punta la nueva prioridad de SmartTube con un código real de la tele.

El [mensaje para WhatsApp](docs/releases/whatsapp-1.8.0.txt) y el
[cartel de Eugenio](images/release_1.8.0.png) acompañan al APK.
Se instala encima de la anterior. Sin desinstalar. Que las cuentas ya estaban sentadas.

## 1.7.0 — 04-08-2026

Las listas de reproducción por fin se comportan como tales: página de lista
de verdad, tarjeta de cola "Reproduciendo desde…" que solo aparece cuando
has elegido una cola, y Guardar a un toque. La reproducción ahora sobrevive
al metro: un corte tipo túnel se recupera solo y el reproductor te dice por
qué se ha parado. Además, el primer vídeo de cada sesión carga mucho antes y
la app en español está por fin en español.

### Listas, cola y guardar
- **Nueva tarjeta "Reproduciendo desde …"** encima de A continuación, con tu
  posición en la cola (i / N) y una lista desplegable para saltar a
  cualquier vídeo: el que suena lleva distintivo y la lista se desplaza
  hasta él al abrirla.
- **La tarjeta solo sale cuando has elegido cola de verdad.** Abrir un vídeo
  desde Inicio, Suscripciones, la búsqueda o el historial convertía esa fila
  en una lista ("Reproduciendo desde Recomendados — 2 / 5"); ya no. De paso,
  A continuación deja de llenarse con vídeos del feed y la reproducción
  automática pasa a un vídeo relacionado, como en YouTube.
- **Página de lista de verdad**: portada ancha, nombre de la lista, autor,
  línea "N vídeos · Privada" y un botón ancho **Reproducir todo** con
  **Aleatorio** al lado (el modo aleatorio se mantiene para el resto de la
  cola).
- **Guardar es ya una acción de la página del vídeo**, junto a Me gusta / No
  me gusta / Compartir, y pasa a un check con "Guardado" mientras el vídeo
  está en alguna lista. Antes estaba enterrado en engranaje → Más → Guardar
  en lista.
- **"Ver más tarde" en el menú de todas las tarjetas**, también en
  instalaciones ya existentes.
- La hoja de guardar adopta la palabra de YouTube ("Guardar en lista"),
  ofrece **Nueva lista** como primera fila y, si no has iniciado sesión, te
  dice qué hacer en vez de abrirse vacía.
- Correcciones: abrir una segunda lista ya no conserva el título anterior
  (ni pone "Recomendados"); **Reproducir todo** ya no se esconde en listas
  abiertas desde una tarjeta de vídeo; y el contador cuenta la lista entera
  y no la primera página ("1 / 30", no "1 / 15").

### Reproducción que aguanta un túnel
- **Los cortes se recuperan solos.** Los cortes reales de móvil (un túnel,
  un ascensor, el metro, un salto de Wi-Fi a datos) nunca dan una
  desconexión limpia, así que el reproductor se rendía en segundos y se
  quedaba muerto hasta que reabrías el vídeo. Ahora reintenta con una pauta
  creciente (5 s, 15 s, 45 s, 2 min, 5 min) y retoma en el punto exacto en
  el que murió; un cambio de red real reintenta al instante.
- **El reproductor dice por qué se ha parado**, en una línea fija sobre el
  vídeo: "reintentando…" mientras lo sigue intentando y "toca reproducir
  para reintentar" cuando se ha rendido. Se queda mientras dure el corte, en
  vez de parpadear una vez por intento.
- **Se acabaron los volcados de error en crudo** sobre el vídeo: fuera los
  avisos con el 403 y las trazas (el siguiente reintento ya lo estaba
  arreglando), y los mensajes que quedan están traducidos.
- **Los botones de reproducir de la notificación, la pantalla de bloqueo y
  los auriculares ahora reintentan.** Estaban muertos en estado de error, lo
  que dejaba sin salida a una sesión de audio en segundo plano.

### Más rápido y más estable
- **El primer vídeo de la sesión carga su página ~2,6 s antes** (medido en
  un Pixel 9 con LTE): la carga anticipada de datos cubre por fin la primera
  apertura — un enlace, una notificación o simplemente la primera tarjeta
  que tocas.
- Al abrir desde un enlace o una notificación, **el título y el canal se
  rellenan de inmediato** cuando el servidor los manda, en vez de dejar la
  cabecera en blanco hasta que llega el resto.
- **La reproducción con sesión iniciada se queda en la ruta de tu cuenta.**
  Ya no arranca por un cliente cuyas URLs dan 403 en cada trozo a partir del
  minuto, y un solo 403 (o una petición lenta con la conexión fría) ya no
  destierra toda la sesión a la ruta anónima — donde la IP compartida del
  operador se lleva un control antibot cuyo texto acababa en el título del
  vídeo.
- La ruta anónima de respaldo empieza ahora por un cliente que no necesita
  negociar ningún token, así que el camino más lento ya no arranca con el
  paso más lento.

### Correcciones
- **Imagen dentro de imagen**: minimizar el reproductor justo a la vez que
  pulsabas inicio podía dibujar **la app entera** — feed, pestañas y todo —
  dentro de la ventanita de PiP; ahora se acopla dentro de la app. Además,
  el reproductor ya no vuelve solo a la ventanita desde segundo plano y se
  suelta el bloqueo horizontal mientras dura el PiP.
- **La interfaz en español está terminada**: unas 130 cadenas de la página
  del vídeo y del reproductor (Comentarios, A continuación, Reproduciendo
  desde, Compartir, Suscribirse…) seguían en inglés en un móvil en español.
- El campo de nueva lista ya no avisa de que tu lista "no se verá en la app
  de YouTube" — falso con la sesión iniciada, y ocupaba justo el sitio donde
  debería poner qué escribir.

## 1.6.1 — 24-07-2026

Ronda de fiabilidad: los errores de reproducción se recuperan antes y se
repiten menos, los vídeos arrancan con la calidad adecuada para tu conexión,
y caen varios detalles molestos (búsqueda con teclado físico, fechas
localizadas, un fallo de PiP).

### Fiabilidad de reproducción
- **Los errores de stream se recuperan antes y dejan de repetirse.** Cuando
  YouTube rechaza una URL de vídeo (el clásico "403" a mitad de vídeo), la
  app ahora recuerda qué ruta de entrega falló en la red actual y aparta de
  ella el reintento — y los siguientes vídeos que abras — durante una
  ventana corta de autocuración, pidiendo URLs nuevas al momento.
- **Se acabaron los spinners silenciosos de un minuto en streams muertos.**
  Los streams rotos sin remedio (enlaces caducados, rangos inválidos) y los
  arranques que se atascan antes del primer byte ahora fallan rápido hacia
  una recarga automática limpia, en vez de reintentar en silencio la misma
  petición condenada hasta un minuto.
- **Los arranques atascados cambian de transporte.** Si la vía rápida QUIC
  se cuelga mientras arranca un vídeo, la recarga automática pasa
  temporalmente a HTTP normal para que el vídeo se reproduzca; la vía rápida
  vuelve sola a los pocos minutos o al cambiar de red.

### Calidad de arranque más lista
- El reproductor ahora recuerda tu ancho de banda medido **por tipo de red**
  (Wi-Fi, 5G, 4G, …) y arranca los vídeos con una calidad acorde a la
  conexión que tienes en ese momento — ni primeros segundos "de Wi-Fi" con
  datos móviles ni arranques en baja calidad sin motivo en Wi-Fi rápidas.
- **Cambiar de vídeo rápido es "gana el último"**: tocar un vídeo nuevo
  mientras el anterior aún se prepara cancela el trabajo obsoleto, y el
  vídeo que has elegido arranca sin hacer cola detrás del otro.

### Correcciones
- La búsqueda ahora se envía con Enter en teclados físicos y Bluetooth
  (algunos solo mandan eventos de tecla en bruto, que se ignoraban), y los
  teclados que notifican el envío dos veces ya no lanzan la búsqueda doble.
- La fecha de publicación bajo el reproductor ya no se corta en idiomas
  distintos del inglés (p. ej. "Data de publicació:"), y salta de línea
  correctamente con la descripción desplegada.
- La imagen dentro de imagen (PiP) ya no puede capturar un fotograma de la
  página del vídeo cuando la superficie se soltó durante un cambio de tarea
  o del minirreproductor.

## 1.6.0 — 21-07-2026

Tres frentes en esta ronda: iniciar sesión pasa de ser un trámite de tele a
un flujo guiado y automático; la app por fin se disfruta **sin** cuenta; y
los subtítulos, la velocidad de reproducción y el envío a la tele estrenan
hojas nativas. Además, icono nuevo.

### Inicio de sesión, rehecho
- **Inicio de sesión guiado**: la pantalla del código ahora te lleva por 3
  pasos numerados (Continuar con Google → aprobar → volver), con el código
  de emparejamiento reducido a una fila de "comprueba que coincide" y un
  enlace manual de respaldo.
- **Vuelta automática**: tras tocar Permitir en la página de Google vuelves
  a la app en segundos — con estados de espera y de éxito (check) — sin
  cambiar de app a mano. Una notificación de "Iniciando sesión…" mantiene
  vivo el proceso mientras el navegador está delante.
- **Hoja de cuentas nativa** (pestaña Tú → fila de la cuenta): toca una
  cuenta para cambiar, "Usar sin cuenta", Añadir cuenta, Cerrar sesión (con
  su diálogo de confirmación) y Ajustes de cuenta para las opciones
  avanzadas. También accesible navegando sin sesión con cuentas guardadas —
  antes ese estado moría en la pantalla de inicio de sesión.

### Mejor sin cuenta
- **El Inicio sin sesión ya no está vacío**: se llena con tendencias y
  feeds temáticos desde el primer arranque, y en cuanto ves unos vídeos se
  personaliza de forma anónima según tu historial — sin necesidad de
  cuenta.
- Corregido el aviso de "inicia sesión" de Suscripciones que se quedaba
  pegado sobre Inicio al cambiar de pestaña sin sesión.

### Envío a la TV: directos y controles
- El selector de Cast y sus opciones ahora dejan claros los pros y contras:
  el envío directo es sin anuncios y con la calidad controlada desde el
  móvil (sin subtítulos); el modo app de la tele tiene subtítulos y calidad
  con el mando, y es el que necesitan los directos. El cambio automático en
  directos es más rápido y con mensajes más claros.
- **Nueva hoja "Opciones de reproducción en la TV"** durante el envío:
  limita la calidad desde el móvil en el envío directo ("Auto (hasta
  1080p)", "Hasta 720p", …), y en sesiones con la app de la tele envía tu
  elección de subtítulos a la TV. Pasar una sesión directa a la app de la
  tele por los subtítulos muestra antes una comparación clara.
- **Vincular con código de TV ahora funciona con SmartTube en la tele**
  (Ajustes → Control remoto), no solo con la app de YouTube — y con
  SmartTube el envío sigue sin anuncios. El diálogo indica dónde encontrar
  el código en cada app y acepta códigos con guiones o espacios.

### Pulido del reproductor
- Entrar en pantalla en pantalla desde el engranaje ya no muestra un
  destello con toda la página comprimida dentro de la ventana que encoge —
  la animación muestra solo el vídeo, como la app oficial.

### Icono nuevo
- El icono del launcher se rediseñó alrededor de la marca del arco-"n".

### Subtítulos y velocidad, bien hechos
- El botón CC ahora activa/desactiva los subtítulos como la app oficial,
  con snackbar de confirmación ("Subtítulos activados (español)" /
  "Subtítulos desactivados") y el icono relleno o con contorno según el
  estado.
- Nuevo selector de subtítulos nativo (mantén pulsado CC, o engranaje →
  Subtítulos): una lista plana con check en la opción activa y un acceso a
  "Estilo y tamaño de subtítulos". Sustituye al viejo diálogo de tele.
- Los subtítulos por fin se ven como los de YouTube: texto blanco normal
  sobre fondo semitransparente por línea, con tamaño relativo al vídeo
  (pequeño bajo la página de vídeo en vertical, mayor en pantalla
  completa). Las instalaciones existentes migran una vez desde el viejo
  amarillo/negrita de tele; un estilo elegido por ti tras la actualización
  se conserva.
- Las filas de los selectores de calidad y audio usan la misma anatomía de
  check inicial que la app oficial.
- Nuevo selector nativo de velocidad en el engranaje: presets de 0,25x a 2x
  con "Normal" para 1x, al estilo de la app oficial y con el mismo snackbar
  de confirmación; la lista extendida completa vive tras "Más velocidades".
  La fila del engranaje muestra la velocidad actual ("Normal"/"1,5x").
