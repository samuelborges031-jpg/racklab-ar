package app.racklab.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.node.AugmentedImageNode
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * RackLab AR
 * - Baixa do site a lista de etiquetas (alvos.json), a imagem de cada etiqueta e o modelo 3D.
 * - O ARCore reconhece as etiquetas (Augmented Images) e o objeto aparece em pé sobre a etiqueta,
 *   com a frente voltada para a borda de baixo dela.
 * - O objeto fica TRAVADO na etiqueta: a cada quadro da câmera ele segue a posição e o giro dela.
 *   Se a etiqueta for movida, o objeto vai junto; se ela sair da tela, o objeto fica na última posição vista.
 * - Várias etiquetas na mesma sala funcionam ao mesmo tempo.
 * - O QR da etiqueta abre o app direto (App Links); o código do link vira destaque na tela.
 * - O objeto começa como maquete (escala da ficha, ex. 1:5); o botão "Tamanho real" alterna para 1:1.
 * - Com a ficha do modelo (modelos/CODIGO.json), cada equipamento ganha um balão de informações ao lado.
 * - "Área livre": sem etiqueta, o objeto é colocado no chão onde a pessoa tocar (detecção de plano do ARCore).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val BASE = "https://racklab-ar.netlify.app/"
        const val TAG = "RackLabAR"
    }

    data class Alvo(val codigo: String, val nome: String, val tipo: String, val largura: Float, val bitmap: Bitmap)

    /**
     * Objeto colocado na cena: preso a uma etiqueta (AugmentedImageNode, segue a etiqueta a cada quadro)
     * ou numa âncora no chão (área livre). [pose] dá a posição atual, usada para os balões.
     */
    private class Colocado(val codigo: String, val node: io.github.sceneview.node.Node, val pose: () -> Pose, val livre: Boolean = false,
                           val ancora: Anchor? = null, var modelo: ModelNode? = null, var ficha: Ficha? = null,
                           var carregando: Boolean = false, var seguindo: Boolean = true) {
        fun escala(real: Boolean): Float = if (real) 1f else 1f / (ficha?.escala ?: 5)
    }

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var dica: TextView
    private var sceneView: ARSceneView? = null
    private lateinit var etiquetas: Etiquetas
    private lateinit var barra: LinearLayout
    private lateinit var btTamanho: Button
    private lateinit var btInfo: Button
    private lateinit var btLivre: Button
    /** Código do modelo a colocar no chão; null = fora do modo área livre. */
    private var modoLivre: String? = null
    @Volatile private var ultimoFrame: Frame? = null
    private var tamanhoReal = false
    private var mostrarInfo = true
    private val proj = FloatArray(16)
    private val vista = FloatArray(16)
    private val vp = FloatArray(16)

    private val alvos = mutableMapOf<String, Alvo>()
    private val colocados = mutableMapOf<String, Colocado>()
    private var destaque: String? = null

    private val pedirCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) carregarAlvos() else mostrarStatus("O RackLab AR precisa da câmera para mostrar os objetos. Libere nas configurações do Android.")
    }

    private fun arquivoErro() = File(filesDir, "ultimo_erro.txt")
    private fun registrarErro(e: Throwable) {
        runCatching { arquivoErro().writeText("versão ${packageManager.getPackageInfo(packageName, 0).versionName}\n" + Log.getStackTraceString(e).take(4000)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> registrarErro(e); anterior?.uncaughtException(t, e) }
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = painel(Gravity.TOP)
        dica = painel(Gravity.BOTTOM)
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(24, 72, 24, 0) })
        root.addView(dica, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 64) })
        etiquetas = Etiquetas(this)
        root.addView(etiquetas, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        btTamanho = botao("Tamanho real") { tamanhoReal = !tamanhoReal; aplicarEscala(); atualizarTextos() }
        btInfo = botao("Ocultar info") { mostrarInfo = !mostrarInfo; if (!mostrarInfo) etiquetas.atualizar(emptyList(), emptyList()); atualizarTextos() }
        btLivre = botao("Área livre") { if (modoLivre != null) sairAreaLivre() else escolherAreaLivre() }
        barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = android.view.View.GONE
            addView(btLivre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) })
            addView(btTamanho, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) })
            addView(btInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(barra, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 64) })
        // a dica sobe para cima da barra de botões; os balões não invadem os painéis
        barra.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> posicionarPaineis() }
        dica.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> posicionarPaineis() }
        status.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> posicionarPaineis() }
        setContentView(root)
        lerLink(intent)
        mostrarStatus("Carregando etiquetas…")
        val erro = runCatching { arquivoErro().takeIf { it.exists() }?.readText() }.getOrNull()
        if (erro != null) {
            arquivoErro().delete()
            avisoErro = true
            dica.text = "O app fechou por um erro na última vez. Toque aqui para copiar o detalhe e mandar ao suporte."
            dica.setOnClickListener {
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("RackLab AR erro", erro))
                dica.text = "Detalhe do erro copiado. Cole na conversa com o Claude."
                dica.setOnClickListener(null)
                dica.postDelayed({ avisoErro = false; atualizarTextos() }, 4000)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) carregarAlvos()
        else pedirCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lerLink(intent)
        atualizarTextos()
    }

    private fun lerLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        destaque = uri.getQueryParameter("m")?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
    }

    private fun painel(g: Int) = TextView(this).apply {
        setTextColor(Color.parseColor("#E2E8ED"))
        textSize = 15f
        setPadding(36, 24, 36, 24)
        gravity = Gravity.START
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { cornerRadius = 28f; setColor(Color.parseColor("#D9151C22")) }
    }

    private fun mostrarStatus(t: String) { status.text = t }

    private fun botao(texto: String, acao: () -> Unit) = Button(this).apply {
        text = texto; isAllCaps = false; textSize = 14f
        setTextColor(Color.parseColor("#06171D"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { cornerRadius = 60f; setColor(Color.parseColor("#4BB0CC")) }
        setPadding(24, 28, 24, 28)
        setOnClickListener { acao() }
    }

    private fun posicionarPaineis() {
        val lpDica = dica.layoutParams as FrameLayout.LayoutParams
        val margem = if (barra.visibility == android.view.View.VISIBLE) 64 + barra.height + 20 else 64
        if (lpDica.bottomMargin != margem) { lpDica.bottomMargin = margem; dica.layoutParams = lpDica }
        etiquetas.reservaTopo = if (status.visibility == android.view.View.VISIBLE) status.bottom.toFloat() else 0f
        etiquetas.reservaBase = if (dica.visibility == android.view.View.VISIBLE && dica.top > 0) dica.top.toFloat() else root.height.toFloat()
    }

    private fun aplicarEscala() {
        colocados.values.forEach { c -> c.modelo?.let { m -> val s = c.escala(tamanhoReal); m.scale = Scale(s, s, s) } }
    }

    // ---------- Download ----------
    private fun baixar(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode} em $url")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** Baixa e guarda no cache; sem internet, usa a última cópia baixada. */
    private fun baixarComCache(caminho: String): ByteArray {
        val f = File(cacheDir, caminho.replace('/', '_'))
        return try {
            baixar(BASE + caminho + "?v=" + (System.currentTimeMillis() / 60000)).also { f.writeBytes(it) }
        } catch (e: Exception) {
            if (f.exists()) f.readBytes() else throw e
        }
    }

    private fun carregarAlvos() {
        lifecycleScope.launch {
            try {
                val lista = withContext(Dispatchers.IO) {
                    val j = JSONObject(String(baixarComCache("alvos.json")))
                    val arr = j.getJSONArray("alvos")
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        val cod = o.getString("codigo")
                        try {
                            val png = baixarComCache("alvos/$cod.png")
                            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@mapNotNull null
                            Alvo(cod, o.optString("nome", cod), o.optString("tipo", ""), o.optDouble("largura", 0.15).toFloat(), bmp)
                        } catch (e: Exception) { Log.w(TAG, "etiqueta $cod", e); null }
                    }
                }
                if (lista.isEmpty()) { mostrarStatus("Nenhuma etiqueta publicada ainda. Gere um QR no RackLab e envie o modelo."); return@launch }
                lista.forEach { alvos[it.codigo] = it }
                iniciarAR()
            } catch (e: Exception) {
                Log.e(TAG, "alvos", e)
                mostrarStatus("Não consegui baixar as etiquetas. Confira a internet e abra o app de novo.")
            }
        }
    }

    // ---------- AR ----------
    private fun iniciarAR() {
        val sv = ARSceneView(this).apply {
            planeRenderer.isEnabled = false
            configureSession { session, config ->
                val db = AugmentedImageDatabase(session)
                alvos.values.forEach { a ->
                    try { db.addImage(a.codigo, a.bitmap, a.largura) }
                    catch (e: Exception) { Log.w(TAG, "imagem ${a.codigo} recusada pelo ARCore", e) }
                }
                config.augmentedImageDatabase = db
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL // usado pela área livre
            }
            onSessionUpdated = { _, frame -> aoAtualizar(frame) }
        }
        sceneView = sv
        root.addView(sv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        atualizarTextos()
    }

    private fun aoAtualizar(frame: Frame) {
        try { aoAtualizarSeguro(frame) } catch (e: Throwable) {
            Log.e(TAG, "quadro", e); registrarErro(e)
            runOnUiThread { mostrarStatus("Erro ao acompanhar a etiqueta: ${e.javaClass.simpleName}. O detalhe ficou salvo.") }
        }
    }

    private fun aoAtualizarSeguro(frame: Frame) {
        val sv = sceneView ?: return
        ultimoFrame = frame
        for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (img.trackingState != TrackingState.TRACKING) continue
            val cod = img.name
            if (colocados.containsKey(cod)) continue // já está preso: o próprio nó acompanha a etiqueta
            if (img.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue
            // o callback pode disparar já na criação do nó: por isso consulta o mapa em vez de uma variável ainda vazia
            val node = AugmentedImageNode(
                engine = sv.engine,
                augmentedImage = img,
                onTrackingMethodChanged = { m ->
                    colocados[cod]?.let { it.seguindo = m == AugmentedImage.TrackingMethod.FULL_TRACKING }
                    runOnUiThread { atualizarTextos() }
                }
            )
            val c = Colocado(cod, node, { img.centerPose })
            sv.addChildNode(node) // filho direto da cena: a cena atualiza a pose dele a cada quadro
            colocados[cod] = c
            carregarModelo(cod, c)
            atualizarTextos()
        }
        if (mostrarInfo) projetarEtiquetas(frame)
    }

    /** Leva o ponto de cada equipamento (metros, no modelo) para a tela, seguindo a etiqueta e a escala atual. */
    private fun projetarEtiquetas(frame: Frame) {
        val prontos = colocados.values.filter { it.modelo != null && it.ficha != null }
        if (prontos.isEmpty()) { etiquetas.atualizar(emptyList(), emptyList()); return }
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        frame.camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        frame.camera.getViewMatrix(vista, 0)
        android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, vista, 0)
        val w = etiquetas.width.toFloat(); val h = etiquetas.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val v = FloatArray(4); val o = FloatArray(4)
        fun tela(pose: com.google.ar.core.Pose, x: Float, y: Float, z: Float): Pair<Float, Float>? {
            val p = pose.transformPoint(floatArrayOf(x, y, z))
            v[0] = p[0]; v[1] = p[1]; v[2] = p[2]; v[3] = 1f
            android.opengl.Matrix.multiplyMV(o, 0, vp, 0, v, 0)
            if (o[3] <= 0.01f) return null
            return ((o[0] / o[3] + 1f) / 2f * w) to ((1f - o[1] / o[3]) / 2f * h)
        }
        val pontos = mutableListOf<Etiquetas.Ponto>()
        val topos = mutableListOf<Etiquetas.Topo>()
        for (c in prontos) {
            val f = c.ficha ?: continue
            val s = c.escala(tamanhoReal)
            val pose = c.pose()
            for (item in f.itens) {
                val t = tela(pose, item.ancora[0] * s, item.ancora[1] * s, item.ancora[2] * s) ?: continue
                pontos += Etiquetas.Ponto(t.first, t.second, item)
            }
            val alto = (f.tamanho?.get(1) ?: (f.itens.maxOfOrNull { i -> i.ancora[1] } ?: 1f)) * s
            if (f.titulo.isNotBlank() || f.resumo.isNotEmpty()) tela(pose, 0f, alto, 0f)?.let { t ->
                topos += Etiquetas.Topo(t.first, t.second, f.titulo.ifBlank { alvos[c.codigo]?.nome ?: "" }, f.resumo,
                    if (tamanhoReal) "Tamanho real (1:1)" else "Maquete 1:${f.escala}")
            }
        }
        etiquetas.atualizar(pontos, topos)
    }

    private fun carregarModelo(cod: String, c: Colocado) {
        if (c.carregando) return
        c.carregando = true
        atualizarTextos()
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { baixarComCache("modelos/$cod.glb") }
                // ficha com as informações dos equipamentos (modelos publicados antes dela não têm)
                c.ficha = withContext(Dispatchers.IO) {
                    runCatching { Ficha.ler(String(baixarComCache("modelos/$cod.json"))) }
                        .onFailure { Log.i(TAG, "sem ficha para $cod: ${it.message}") }.getOrNull()
                }
                val sv = sceneView ?: return@launch
                val buf = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                val inst = sv.modelLoader.createModelInstance(buf)
                // modelo em metros, origem no chão no centro do objeto, frente para +Z (borda de baixo da etiqueta)
                val modelo = ModelNode(modelInstance = inst, scaleToUnits = null)
                val s = c.escala(tamanhoReal)
                modelo.scale = Scale(s, s, s)
                c.node.addChildNode(modelo)
                c.modelo = modelo
            } catch (e: Exception) {
                Log.e(TAG, "modelo $cod", e)
                mostrarStatus("Etiqueta ${alvos[cod]?.nome ?: cod} encontrada, mas o modelo 3D não está publicado.")
            } finally {
                c.carregando = false
                atualizarTextos()
            }
        }
    }

    // ---------- Área livre (sem etiqueta) ----------
    private fun escolherAreaLivre() {
        val lista = alvos.values.sortedBy { it.nome.lowercase() }
        if (lista.isEmpty()) { mostrarStatus("Nenhum modelo publicado ainda."); return }
        val d = destaque?.let { alvos[it] }
        when {
            d != null -> entrarAreaLivre(d.codigo)
            lista.size == 1 -> entrarAreaLivre(lista[0].codigo)
            else -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Qual modelo colocar no chão?")
                .setItems(lista.map { "${it.nome} · ${it.codigo}" }.toTypedArray()) { _, i -> entrarAreaLivre(lista[i].codigo) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun entrarAreaLivre(cod: String) {
        modoLivre = cod
        sceneView?.planeRenderer?.isEnabled = true
        etiquetas.tocarLivre = { x, y -> colocarNoChao(x, y) }
        atualizarTextos()
    }

    private fun sairAreaLivre() {
        modoLivre = null
        sceneView?.planeRenderer?.isEnabled = false
        etiquetas.tocarLivre = null
        atualizarTextos()
    }

    /** Toque na tela: procura o chão detectado nesse ponto e coloca (ou muda de lugar) o modelo, de frente para a câmera. */
    private fun colocarNoChao(x: Float, y: Float) {
        val cod = modoLivre ?: return
        val sv = sceneView ?: return
        val frame = ultimoFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) { mostrarStatus("Mova o celular devagar para o AR reconhecer o ambiente."); return }
        val hit = frame.hitTest(x, y).firstOrNull { h ->
            val t = h.trackable
            t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(h.hitPose)
        }
        if (hit == null) { mostrarStatus("Ainda não reconheci o chão nesse ponto. Aponte para o piso, mova o celular devagar até aparecer a malha e toque nela."); return }
        val p = hit.hitPose; val cam = frame.camera.pose
        val ang = kotlin.math.atan2(cam.tx() - p.tx(), cam.tz() - p.tz()) // frente do modelo (+Z) voltada para a câmera
        val pose = Pose(floatArrayOf(p.tx(), p.ty(), p.tz()), floatArrayOf(0f, kotlin.math.sin(ang / 2f), 0f, kotlin.math.cos(ang / 2f)))
        val anchor = hit.trackable.createAnchor(pose)
        val chave = "livre:$cod"
        colocados.remove(chave)?.let { velho ->
            runCatching { sv.removeChildNode(velho.node) }
            runCatching { velho.ancora?.detach() }
        }
        val node = AnchorNode(engine = sv.engine, anchor = anchor)
        sv.addChildNode(node)
        val c = Colocado(cod, node, { anchor.pose }, livre = true, ancora = anchor)
        colocados[chave] = c
        carregarModelo(cod, c)
        atualizarTextos()
    }

    private var avisoErro = false
    private fun atualizarTextos() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { runOnUiThread { atualizarTextos() }; return }
        if (sceneView == null) return
        val algum = colocados.values.any { it.modelo != null }
        if (barra.visibility != android.view.View.VISIBLE) { barra.visibility = android.view.View.VISIBLE; barra.post { posicionarPaineis() } }
        val visObj = if (algum) android.view.View.VISIBLE else android.view.View.GONE
        if (btTamanho.visibility != visObj) { btTamanho.visibility = visObj; btInfo.visibility = visObj; barra.post { posicionarPaineis() } }
        btLivre.text = if (modoLivre != null) "Concluir" else "Área livre"
        val escalas = colocados.values.mapNotNull { it.ficha?.escala }.distinct()
        btTamanho.text = if (tamanhoReal) "Ver maquete" + (if (escalas.size == 1) " 1:${escalas[0]}" else "") else "Tamanho real"
        val temFicha = colocados.values.any { it.ficha != null }
        btInfo.text = if (mostrarInfo) "Ocultar info" else "Mostrar info"
        btInfo.isEnabled = temFicha; btInfo.alpha = if (temFicha) 1f else 0.5f
        val vistos = colocados.values.map { alvos[it.codigo]?.nome ?: it.codigo }
        val alvoDestaque = destaque?.let { alvos[it] }
        val livreNome = modoLivre?.let { alvos[it]?.nome ?: it }
        status.text = when {
            livreNome != null && colocados.values.none { it.livre && it.codigo == modoLivre } ->
                "Área livre · $livreNome\nAponte para o chão e mova o celular devagar até aparecer a malha. Depois toque onde quer colocar."
            colocados.isEmpty() && alvoDestaque != null -> "Aponte para a etiqueta ${alvoDestaque.codigo} · ${alvoDestaque.nome}"
            colocados.isEmpty() && destaque != null -> "A etiqueta $destaque ainda não foi publicada. Mostrando as demais."
            colocados.isEmpty() -> "Aponte a câmera para uma etiqueta RackLab"
            colocados.values.any { it.carregando } -> "Carregando modelo… (${vistos.joinToString(", ")})"
            else -> colocados.values.joinToString("\n") { c ->
                (alvos[c.codigo]?.nome ?: c.codigo) + " · " + (if (tamanhoReal) "tamanho real" else "maquete 1:${c.ficha?.escala ?: 5}") +
                    (if (c.livre) " · no chão" else "") + if (c.seguindo) "" else " · última posição (aponte para a etiqueta)"
            }
        }
        if (avisoErro) return
        dica.text = if (modoLivre != null)
            "Toque no chão para colocar; toque em outro ponto para mudar de lugar. Toque em Concluir para esconder a malha."
        else if (colocados.isEmpty())
            "${alvos.size} etiqueta(s) cadastrada(s). Chegue a cerca de 1 m da etiqueta, com boa luz, e mova o celular devagar. Sem etiqueta? Toque em Área livre."
        else if (colocados.values.any { it.modelo != null } && !temFicha)
            "Este modelo foi publicado sem a ficha dos equipamentos. No RackLab, toque em Publicar no AR de novo para ver as informações."
        else if (mostrarInfo && temFicha) "Toque num balão para ver a ficha completa do equipamento. O objeto acompanha a etiqueta."
        else "O objeto acompanha a etiqueta: mova ou gire o papel e ele vai junto. Mantenha a etiqueta visível e bem iluminada."
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
