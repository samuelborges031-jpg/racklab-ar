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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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
 * - Leitura do QR Code em si (ML Kit): o QR da tela do RackLab, de uma folha ou da etiqueta abre o modelo
 *   no ponto onde o QR está. Projetos publicados depois de o app abrir são baixados na hora.
 *   Com a etiqueta impressa inteira, o modelo passa a acompanhar a etiqueta (rastreamento de imagem).
 * - Cada objeto na cena vira um botão na lista de baixo: tocar seleciona; "Fechar" reduz o objeto a uma
 *   miniatura de ~15 cm (sem balões) e "Abrir" volta ao tamanho atual; "Excluir" tira da cena
 *   (etiquetas excluídas são ignoradas até tocar em "Restaurar").
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
                           var carregando: Boolean = false, var seguindo: Boolean = true,
                           var fechado: Boolean = false, var tamMax: Float? = null, val viaQr: Boolean = false) {
        fun escala(real: Boolean): Float = if (real) 1f else 1f / (ficha?.escala ?: 5)
        /** Escala aplicada: normal ou, se fechado, miniatura com ~15 cm no maior lado (nunca maior que a normal). */
        fun escalaAtual(real: Boolean): Float {
            val n = escala(real)
            if (!fechado) return n
            val t = tamMax ?: ficha?.tamanho?.maxOrNull() ?: 1f
            return minOf(n, MINIATURA / t.coerceAtLeast(0.01f))
        }
        companion object { const val MINIATURA = 0.15f }
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
    private lateinit var chips: LinearLayout
    private lateinit var rolagemChips: HorizontalScrollView
    private lateinit var acoes: LinearLayout
    private lateinit var btFechar: Button
    private lateinit var btExcluir: Button
    private lateinit var painelBaixo: LinearLayout
    /** Chave (em [colocados]) do objeto escolhido na lista; null = nenhum. */
    private var selecionado: String? = null
    /** Etiquetas tiradas da cena: não voltam a aparecer até "Restaurar". */
    private val excluidos = mutableSetOf<String>()
    private var assinaturaChips = ""
    // ---- leitura de QR Code ----
    private val leitorQr by lazy { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    @Volatile private var qrOcupado = false
    private var qrUltimo = 0L
    /** QR lido na imagem da câmera (código, centro em pixels da imagem), à espera do próximo quadro para posicionar. */
    @Volatile private var qrPendente: Triple<String, Float, Float>? = null
    /** Lista de etiquetas mudou: refaz o banco de imagens do ARCore no próximo quadro. */
    @Volatile private var dbPendente = false
    private var recarregando = false
    private var ultimoAvisoQr = 0L
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
        // lista de objetos na cena (rolagem lateral) + ações do objeto escolhido, acima da barra principal
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rolagemChips = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = android.view.View.GONE; addView(chips) }
        btFechar = botao("Fechar") { alternarFechado() }
        btExcluir = botao("Excluir") { excluirSelecionado() }.apply {
            setTextColor(Color.WHITE); background = GradientDrawable().apply { cornerRadius = 60f; setColor(Color.parseColor("#B8332A")) }
        }
        acoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = android.view.View.GONE
            addView(btFechar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) })
            addView(btExcluir, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        painelBaixo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(acoes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) })
            addView(rolagemChips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) })
            addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(painelBaixo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 64) })
        // a dica sobe para cima do painel de baixo; os balões não invadem os painéis
        painelBaixo.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> posicionarPaineis() }
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
        text = texto; isAllCaps = false; textSize = 13.5f; maxLines = 1
        setTextColor(Color.parseColor("#06171D"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { cornerRadius = 60f; setColor(Color.parseColor("#4BB0CC")) }
        setPadding(20, 22, 20, 22); minHeight = 0; minimumHeight = 0
        setOnClickListener { acao() }
    }

    private fun posicionarPaineis() {
        val lpDica = dica.layoutParams as FrameLayout.LayoutParams
        val margem = if (painelBaixo.height > 0) 64 + painelBaixo.height + 20 else 64
        if (lpDica.bottomMargin != margem) { lpDica.bottomMargin = margem; dica.layoutParams = lpDica }
        etiquetas.reservaTopo = if (status.visibility == android.view.View.VISIBLE) status.bottom.toFloat() else 0f
        etiquetas.reservaBase = if (dica.visibility == android.view.View.VISIBLE && dica.top > 0) dica.top.toFloat() else root.height.toFloat()
    }

    private fun aplicarEscala() {
        colocados.values.forEach { c -> c.modelo?.let { m -> val s = c.escalaAtual(tamanhoReal); m.scale = Scale(s, s, s) } }
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
                config.augmentedImageDatabase = montarDb(session)
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

    private fun montarDb(session: Session): AugmentedImageDatabase {
        val db = AugmentedImageDatabase(session)
        alvos.values.forEach { a ->
            try { db.addImage(a.codigo, a.bitmap, a.largura) }
            catch (e: Exception) { Log.w(TAG, "imagem ${a.codigo} recusada pelo ARCore", e) }
        }
        return db
    }

    /** Baixa de novo a lista de etiquetas (projetos publicados depois de abrir o app) e acrescenta as novas. */
    private fun recarregarAlvos(aoTerminar: (() -> Unit)? = null) {
        if (recarregando) return
        recarregando = true
        lifecycleScope.launch {
            try {
                val novos = withContext(Dispatchers.IO) {
                    val j = JSONObject(String(baixar(BASE + "alvos.json?v=" + System.currentTimeMillis())))
                    val arr = j.getJSONArray("alvos")
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i); val cod = o.getString("codigo")
                        if (alvos.containsKey(cod)) return@mapNotNull null
                        runCatching {
                            val png = baixarComCache("alvos/$cod.png")
                            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@mapNotNull null
                            Alvo(cod, o.optString("nome", cod), o.optString("tipo", ""), o.optDouble("largura", 0.15).toFloat(), bmp)
                        }.getOrNull()
                    }
                }
                if (novos.isNotEmpty()) { novos.forEach { alvos[it.codigo] = it }; dbPendente = true }
            } catch (e: Exception) { Log.w(TAG, "recarregar etiquetas", e) }
            finally { recarregando = false; atualizarTextos(); aoTerminar?.invoke() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (sceneView != null) recarregarAlvos()
    }

    // ---------- QR Code ----------
    /** A cada ~0,35 s manda uma imagem da câmera para o leitor de QR (uma por vez, sem travar o AR). */
    private fun lerQr(frame: Frame) {
        val agora = android.os.SystemClock.uptimeMillis()
        if (qrOcupado || agora - qrUltimo < 350) return
        qrUltimo = agora
        val img = try { frame.acquireCameraImage() } catch (e: Exception) { return }
        qrOcupado = true
        val entrada = try { InputImage.fromMediaImage(img, 0) } catch (e: Exception) { img.close(); qrOcupado = false; return }
        leitorQr.process(entrada)
            .addOnSuccessListener { lista -> tratarQr(lista) }
            .addOnCompleteListener { runCatching { img.close() }; qrOcupado = false }
    }

    private fun codigoDoQr(texto: String?): String? {
        val u = runCatching { Uri.parse(texto ?: return null) }.getOrNull() ?: return null
        if (u.host?.contains("racklab-ar") != true) return null
        return u.getQueryParameter("m")?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
    }

    private fun tratarQr(lista: List<Barcode>) {
        for (b in lista) {
            val cod = codigoDoQr(b.rawValue) ?: continue
            val caixa = b.boundingBox ?: continue
            destaque = cod
            if (colocados.containsKey(cod) || colocados.containsKey("qr:$cod") || cod in excluidos) return
            val agora = android.os.SystemClock.uptimeMillis()
            if (!alvos.containsKey(cod)) {
                if (agora - ultimoAvisoQr > 2500) { ultimoAvisoQr = agora; mostrarStatus("QR $cod lido. Baixando o projeto…") }
                recarregarAlvos()
            }
            qrPendente = Triple(cod, caixa.exactCenterX(), caixa.exactCenterY())
            return
        }
    }

    /** Coloca o modelo onde o QR aparece na tela: na superfície encontrada ali ou, sem superfície, a ~0,8 m da câmera. */
    private fun colocarPorQr(frame: Frame, q: Triple<String, Float, Float>) {
        val (cod, ix, iy) = q
        val sv = sceneView ?: return
        val session = sv.session ?: return
        if (colocados.containsKey(cod) || colocados.containsKey("qr:$cod")) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val v = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, floatArrayOf(ix, iy), Coordinates2d.VIEW, v)
        val hit = frame.hitTest(v[0], v[1]).firstOrNull()
        val cam = frame.camera.pose
        val alvo = hit?.hitPose?.let { floatArrayOf(it.tx(), it.ty(), it.tz()) }
            ?: cam.transformPoint(floatArrayOf(0f, -0.15f, -0.8f))
        val ang = kotlin.math.atan2(cam.tx() - alvo[0], cam.tz() - alvo[2])
        val pose = Pose(alvo, floatArrayOf(0f, kotlin.math.sin(ang / 2f), 0f, kotlin.math.cos(ang / 2f)))
        val anchor = runCatching { hit?.trackable?.createAnchor(pose) }.getOrNull() ?: session.createAnchor(pose)
        val node = AnchorNode(engine = sv.engine, anchor = anchor)
        sv.addChildNode(node)
        val c = Colocado(cod, node, { anchor.pose }, ancora = anchor, viaQr = true)
        colocados["qr:$cod"] = c
        carregarModelo(cod, c)
        runOnUiThread { mostrarStatus("QR de ${alvos[cod]?.nome ?: cod} lido: modelo aberto onde o QR está."); atualizarTextos(manterStatus = true) }
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
        if (dbPendente) { dbPendente = false; sv.session?.let { s -> val cfg = s.config; cfg.augmentedImageDatabase = montarDb(s); s.configure(cfg) } }
        qrPendente?.let { qrPendente = null; colocarPorQr(frame, it) }
        lerQr(frame)
        for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (img.trackingState != TrackingState.TRACKING) continue
            val cod = img.name
            if (colocados.containsKey(cod)) continue // já está preso: o próprio nó acompanha a etiqueta
            if (cod in excluidos) continue // a pessoa tirou este objeto da cena
            colocados.remove("qr:$cod")?.let { velho -> runCatching { sv.removeChildNode(velho.node) }; runCatching { velho.ancora?.detach() } }
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
        val prontos = colocados.values.filter { it.modelo != null && it.ficha != null && !it.fechado }
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
                c.tamMax = runCatching { inst.asset.boundingBox.halfExtent.maxOrNull()?.times(2f) }.getOrNull()
                val s = c.escalaAtual(tamanhoReal)
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

    // ---------- Lista de objetos: selecionar, fechar (miniatura), excluir ----------
    private fun nomeDe(c: Colocado) = (alvos[c.codigo]?.nome ?: c.codigo) + if (c.livre) " (chão)" else if (c.viaQr) " (QR)" else ""

    private fun chip(texto: String, ativo: Boolean, acao: () -> Unit) = TextView(this).apply {
        text = texto; textSize = 13.5f; maxLines = 1
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (ativo) Color.parseColor("#06171D") else Color.parseColor("#E2E8ED"))
        setPadding(30, 18, 30, 18)
        background = GradientDrawable().apply {
            cornerRadius = 60f
            setColor(if (ativo) Color.parseColor("#4BB0CC") else Color.parseColor("#D9151C22"))
            setStroke(2, Color.parseColor(if (ativo) "#4BB0CC" else "#5A6873"))
        }
        isClickable = true; setOnClickListener { acao() }
    }

    private fun montarChips() {
        val assinatura = colocados.entries.joinToString("|") { (k, c) -> "$k:${c.fechado}:${c.modelo != null}" } + "#$selecionado#${excluidos.size}"
        if (assinatura == assinaturaChips) return
        assinaturaChips = assinatura
        chips.removeAllViews()
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 12, 0) } }
        colocados.forEach { (k, c) ->
            val t = nomeDe(c) + if (c.fechado) " · fechado" else ""
            chips.addView(chip(t, k == selecionado) { selecionado = if (selecionado == k) null else k; atualizarTextos() }, lp())
        }
        if (excluidos.isNotEmpty()) chips.addView(chip("Restaurar (${excluidos.size})", false) {
            excluidos.clear(); mostrarStatus("Aponte de novo para as etiquetas para trazer os objetos de volta."); atualizarTextos()
        }, lp())
        rolagemChips.visibility = if (chips.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun alternarFechado() {
        val c = selecionado?.let { colocados[it] } ?: return
        c.fechado = !c.fechado
        c.modelo?.let { m -> val s = c.escalaAtual(tamanhoReal); m.scale = Scale(s, s, s) }
        if (c.fechado) etiquetas.atualizar(emptyList(), emptyList())
        atualizarTextos()
    }

    private fun excluirSelecionado() {
        val k = selecionado ?: return
        val c = colocados.remove(k) ?: return
        val sv = sceneView
        runCatching { sv?.removeChildNode(c.node) }
        runCatching { c.ancora?.detach() }
        runCatching { c.node.destroy() }
        if (!c.livre) excluidos += c.codigo // vale para a etiqueta e para o QR
        selecionado = null
        etiquetas.atualizar(emptyList(), emptyList())
        mostrarStatus("${nomeDe(c)} saiu da cena." + if (!c.livre) " Toque em Restaurar para trazer de volta." else "")
        atualizarTextos(manterStatus = true)
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
    private fun atualizarTextos(manterStatus: Boolean = false) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { runOnUiThread { atualizarTextos(manterStatus) }; return }
        if (sceneView == null) return
        if (selecionado != null && !colocados.containsKey(selecionado)) selecionado = null
        montarChips()
        val sel = selecionado?.let { colocados[it] }
        acoes.visibility = if (sel != null) android.view.View.VISIBLE else android.view.View.GONE
        if (sel != null) btFechar.text = if (sel.fechado) "Abrir ${nomeDe(sel)}" else "Fechar (miniatura)"
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
        if (!manterStatus) status.text = when {
            livreNome != null && colocados.values.none { it.livre && it.codigo == modoLivre } ->
                "Área livre · $livreNome\nAponte para o chão e mova o celular devagar até aparecer a malha. Depois toque onde quer colocar."
            colocados.isEmpty() && alvoDestaque != null -> "Aponte para a etiqueta ${alvoDestaque.codigo} · ${alvoDestaque.nome}"
            colocados.isEmpty() && destaque != null -> "A etiqueta $destaque ainda não foi publicada. Mostrando as demais."
            colocados.isEmpty() -> "Aponte a câmera para um QR Code RackLab"
            colocados.values.any { it.carregando } -> "Carregando modelo… (${vistos.joinToString(", ")})"
            else -> colocados.values.joinToString("\n") { c ->
                (alvos[c.codigo]?.nome ?: c.codigo) + " · " + (if (c.fechado) "fechado (miniatura)" else if (tamanhoReal) "tamanho real" else "maquete 1:${c.ficha?.escala ?: 5}") +
                    (if (c.livre) " · no chão" else if (c.viaQr) " · aberto pelo QR" else "") + if (c.seguindo) "" else " · última posição (aponte para a etiqueta)"
            }
        }
        if (avisoErro) return
        dica.text = if (sel != null)
            "${nomeDe(sel)} selecionado. Fechar reduz a uma miniatura; Excluir tira da cena. Toque no nome de novo para desmarcar."
        else if (modoLivre != null)
            "Toque no chão para colocar; toque em outro ponto para mudar de lugar. Toque em Concluir para esconder a malha."
        else if (colocados.isEmpty())
            "${alvos.size} projeto(s) publicados. Aponte para o QR Code (na etiqueta, numa folha ou na tela do RackLab) a cerca de 50 cm. Sem QR? Toque em Área livre."
        else if (colocados.values.any { it.modelo != null } && !temFicha)
            "Este modelo foi publicado sem a ficha dos equipamentos. No RackLab, toque em Publicar no AR de novo para ver as informações."
        else if (colocados.size > 1) "Toque no nome de um objeto, na lista de baixo, para fechar ou excluir."
        else if (mostrarInfo && temFicha) "Toque num balão para ver a ficha completa do equipamento. Toque no nome do objeto para fechar ou excluir."
        else "O objeto acompanha a etiqueta: mova ou gire o papel e ele vai junto. Mantenha a etiqueta visível e bem iluminada."
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
