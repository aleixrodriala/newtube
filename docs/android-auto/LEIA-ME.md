# NewTube Auto — guia de instalação (português)

YouTube no ecrã do carro com Android Auto. **Vídeo só com o carro parado**; em andamento continua o som e a imagem volta sozinha quando o carro para. Se quiseres, podes ligar o **vídeo sempre** (toca duas vezes no botão Vídeo no ecrã de reprodução — por tua conta e risco; toca outra vez para voltar ao normal). É uma app à parte do NewTube normal (podes ter as duas instaladas).

## Porque é preciso o instalador
O Android Auto só mostra apps que acha que vieram da Google Play. Se instalares o APK da forma normal (tocar no ficheiro no telemóvel), o carro recusa a app ou mostra só o som. O instalador em [`scripts/android-auto`](../../scripts/android-auto) usa o instalador do próprio telemóvel com a Play Store como origem, que é o que o Android Auto aceita.

## O que precisas
- Telemóvel Android com Android Auto
- Um computador (Windows, Mac ou Linux)
- Um cabo USB

## Passo a passo
1. **Depuração USB** (só da primeira vez): Definições > Acerca do telemóvel > toca 7 vezes em "Número de compilação". Depois Definições > Sistema > Opções de programador > liga **Depuração USB**.
2. **Junta numa pasta** o APK do NewTube e os ficheiros `Install.bat`, `install.ps1` e `install.sh`. Se não estiver lá nenhum APK, o instalador descarrega sozinho o desta versão para a pasta (podes mudar a origem com a variável `NEWTUBE_AUTO_APK_URL`). Usa sempre o APK mais recente (o nome traz a versão, p.ex. `NewTube_1.17.1_arm64-v8a.apk`); o instalador recusa APKs de outra versão para não instalares uma atualização antiga por engano.
3. **Liga o telemóvel** ao computador com o cabo.
4. **Corre o instalador:** Windows — duplo clique em `Install.bat`; Mac/Linux — no Terminal, nessa pasta, `./install.sh`. Da primeira vez descarrega sozinho as ferramentas da Google (adb).
5. **No telemóvel:** "Permitir depuração USB?" → Permitir. Depois **Instalar / Atualizar**. Se o Google Play Protect avisar → Mais detalhes → **Instalar mesmo assim**. O computador diz "Done" no fim.
6. **Prepara a app** (só da primeira vez): abre "NewTube Auto" e inicia sessão se quiseres as tuas subscrições, histórico e playlists. Localização: Definições > Apps > NewTube Auto > Permissões > Localização > **Permitir sempre** (só serve para saber se o carro está parado, caso o carro não informe a velocidade; nunca sai do telemóvel).
7. **No carro:** grelha de apps do Android Auto → **NewTube Auto**. Se não aparecer: no telemóvel, Definições do Android Auto → Personalizar iniciador → ativa o NewTube Auto. Os botões do volante funcionam.

## Atualizações
Usa **sempre** o instalador (passos 3 a 5) com o APK novo. Se atualizares da forma normal, o Android Auto volta a esconder a app.

## Problemas
- **Só há som no carro, mesmo parado:** volta a instalar com o instalador e liga o carro de novo.
- **"No phone found":** confirma o cabo (alguns só carregam), a depuração USB e o "Permitir" no telemóvel.
- **Registo da app** (para pedir ajuda): `Android/data/io.github.aleixrodriala.arc.auto/files/newtube-auto.log` no telemóvel.
