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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * RackLab AR
 * - Baixa do site a lista de etiquetas (alvos.json), a imagem de cada etiqueta e o modelo 3D.
 * - O ARCore reconhece as etiquetas (Augmented Images) e o objeto aparece em pé sobre a etiqueta,
 *   com a frente voltada para a borda de baixo dela, e continua no lugar quando a etiqueta sai da tela.
 * - Várias etiquetas na mesma sala funcionam ao mesmo tempo.
 * - O QR da etiqueta abre o app direto (App Links); o código do link vira destaque na tela.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val BASE = "https://racklab-ar.netlify.app/"
        const val TAG = "RackLabAR"
        /** Se a etiqueta se moveu mais que isso (m) desde a última âncora, reposiciona o objeto. */
        const val REANCHOR_DIST = 0.03f
    }

    data class Alvo(val codigo: String, val nome: String, val tipo: String, val largura: Float, val bitmap: Bitmap)

    private class Colocado(val anchorNode: AnchorNode, var pose: Pose, var modelo: ModelNode? = null, var carregando: Boolean = false)

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var dica: TextView
    private var sceneView: ARSceneView? = null

    private val alvos = mutableMapOf<String, Alvo>()
    private val colocados = mutableMapOf<String, Colocado>()
    private var destaque: String? = null

    private val pedirCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) carregarAlvos() else mostrarStatus("O RackLab AR precisa da câmera para mostrar os objetos. Libere nas configurações do Android.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = painel(Gravity.TOP)
        dica = painel(Gravity.BOTTOM)
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(24, 72, 24, 0) })
        root.addView(dica, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 64) })
        setContentView(root)
        lerLink(intent)
        mostrarStatus("Carregando etiquetas…")
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
            }
            onSessionUpdated = { _, frame -> aoAtualizar(frame) }
        }
        sceneView = sv
        root.addView(sv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        atualizarTextos()
    }

    private fun aoAtualizar(frame: Frame) {
        val sv = sceneView ?: return
        for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (img.trackingState != TrackingState.TRACKING || img.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue
            val cod = img.name
            val pose = img.centerPose
            val atual = colocados[cod]
            if (atual == null) {
                val anchor: Anchor = img.createAnchor(pose)
                val node = AnchorNode(sv.engine, anchor)
                sv.addChildNode(node)
                val c = Colocado(node, pose)
                colocados[cod] = c
                carregarModelo(cod, c)
                atualizarTextos()
            } else if (distancia(atual.pose, pose) > REANCHOR_DIST) {
                // etiqueta vista melhor ou movida: reposiciona o objeto em cima dela
                val velha = atual.anchorNode.anchor
                atual.anchorNode.anchor = img.createAnchor(pose)
                velha.detach()
                atual.pose = pose
            }
        }
    }

    private fun distancia(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun carregarModelo(cod: String, c: Colocado) {
        if (c.carregando) return
        c.carregando = true
        atualizarTextos()
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { baixarComCache("modelos/$cod.glb") }
                val sv = sceneView ?: return@launch
                val buf = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                val inst = sv.modelLoader.createModelInstance(buf)
                // modelo em metros, origem no chão no centro do objeto, frente para +Z (borda de baixo da etiqueta)
                val modelo = ModelNode(modelInstance = inst, scaleToUnits = null)
                c.anchorNode.addChildNode(modelo)
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

    private fun atualizarTextos() {
        if (sceneView == null) return
        val vistos = colocados.keys.map { alvos[it]?.nome ?: it }
        val alvoDestaque = destaque?.let { alvos[it] }
        status.text = when {
            colocados.isEmpty() && alvoDestaque != null -> "Aponte para a etiqueta ${alvoDestaque.codigo} · ${alvoDestaque.nome}"
            colocados.isEmpty() && destaque != null -> "A etiqueta $destaque ainda não foi publicada. Mostrando as demais."
            colocados.isEmpty() -> "Aponte a câmera para uma etiqueta RackLab"
            colocados.values.any { it.carregando } -> "Carregando modelo… (${vistos.joinToString(", ")})"
            else -> "Na sala: ${vistos.joinToString(", ")}"
        }
        dica.text = if (colocados.isEmpty())
            "${alvos.size} etiqueta(s) cadastrada(s). Chegue a cerca de 1 m da etiqueta, com boa luz, e mova o celular devagar."
        else "O objeto fica em pé sobre a etiqueta, de frente para a borda de baixo. Aponte para outras etiquetas para ver mais objetos."
    }

    override fun onDestroy() {
        colocados.values.forEach { runCatching { it.anchorNode.anchor.detach() } }
        super.onDestroy()
    }
}
