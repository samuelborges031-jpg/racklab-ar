# RackLab AR

- `public/` — site publicado pelo Netlify (`racklab-ar.netlify.app`)
  - `index.html` — visualizador web (abre `?m=CODIGO`)
  - `modelos/CODIGO.glb` — modelo 3D de cada QR
  - `alvos.json` + `alvos/CODIGO.png` — etiquetas que o app reconhece (imprimir com 15 cm de largura)
  - `app/` — página de download do APK
  - `.well-known/assetlinks.json` — faz o QR abrir o app direto
- `android/` — app Android (Kotlin + ARCore + SceneView). Compilado pelo GitHub Actions a cada mudança.

Para publicar um QR novo: coloque o `.glb` em `public/modelos/`, a etiqueta em `public/alvos/` e adicione a entrada em `public/alvos.json`.
