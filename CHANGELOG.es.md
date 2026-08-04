# NewTube — Novedades

Cambios visibles para el usuario, en español. El historial completo de
versiones anteriores está en [CHANGELOG.md](CHANGELOG.md) (en inglés).

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
