# RackLab AR

- `public/` — site publicado pelo Netlify (`racklab-ar.netlify.app`). O Netlify só publica quando algo em `public/` muda (economiza créditos do plano gratuito).
  - `index.html` — visualizador web (abre `?m=CODIGO`)
  - `modelos/CODIGO.glb` — modelo 3D de cada QR
  - `alvos.json` + `alvos/CODIGO.png` — etiquetas que o app reconhece (imprimir com 15 cm de largura)
  - `app/` — página de download do APK
  - `.well-known/assetlinks.json` — faz o QR abrir o app direto
- `android/` — app Android (Kotlin + ARCore + SceneView). Compilado pelo GitHub Actions a cada mudança.

## Publicação automática

No RackLab, aba **Realidade aumentada**, o botão **Publicar no AR** gera o modelo e a etiqueta, guarda como pedido (coleção `ar_publicar` do RackLab) e dispara a tarefa agendada **Publicar RackLab AR**. A tarefa copia `modelos/CODIGO.glb`, `alvos/CODIGO.png` e a entrada de `alvos.json` para este repositório e faz push; o Netlify publica em 1 a 2 minutos. O visualizador tenta de novo sozinho enquanto o modelo não aparece.

Publicação manual (alternativa): coloque o `.glb` em `public/modelos/`, a etiqueta em `public/alvos/` e adicione a entrada em `public/alvos.json`.

## Economia de publicações (Netlify)

- O Netlify só publica quando algo em `public/` muda (`ignore` no `netlify.toml`).
- Mudanças no app Android **não** vão para o site: o APK é compilado e fica como artefato do GitHub Actions. Para atualizar a página de download, rode o workflow "App Android (APK)" com **publicar_no_site** marcado, ou ponha `[site]` na mensagem do commit.
- A tarefa "Publicar RackLab AR" junta todos os pedidos pendentes num único commit.
