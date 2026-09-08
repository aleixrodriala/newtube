# NewTube — Novedades

Cambios visibles para el usuario, en español. El historial completo de
versiones anteriores está en [CHANGELOG.md](CHANGELOG.md) (en inglés).

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
