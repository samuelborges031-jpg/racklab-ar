package app.racklab.ar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Camada desenhada por cima da câmera: um balão com as informações de cada equipamento,
 * ligado por uma linha ao ponto do equipamento no AR. Os balões ficam numa coluna ao lado
 * do objeto e são afastados entre si para não se sobreporem. Tocar num balão abre a ficha completa.
 */
class Etiquetas(context: Context) : View(context) {

    /** Um ponto já projetado na tela (px). */
    class Ponto(val x: Float, val y: Float, val item: ItemFicha)

    /** Resumo do objeto (rack, quadro ou área), preso ao topo dele. */
    class Topo(val x: Float, val y: Float, val titulo: String, val linhas: List<Pair<String, String>>, val escala: String)

    @Volatile private var pontos: List<Ponto> = emptyList()
    @Volatile private var topos: List<Topo> = emptyList()
    var selecionado: String? = null
    /** Modo área livre: toque fora dos balões vai para cá (coordenadas da tela). */
    var tocarLivre: ((Float, Float) -> Unit)? = null
    private var toqueX = 0f
    private var toqueY = 0f
    /** Faixas da tela ocupadas pelos painéis do app (px). */
    var reservaTopo = 0f
    var reservaBase = 0f

    private val d = resources.displayMetrics.density
    private val fundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6151C22") }
    private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.parseColor("#4BB0CC") }
    private val bordaSel = Paint(borda).apply { color = Color.parseColor("#E9A23B"); strokeWidth = 2f * d }
    private val linha = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * d; color = Color.parseColor("#CC4BB0CC") }
    private val ponto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4BB0CC") }
    private val pontoAnel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.WHITE }
    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2F5F7"); textSize = 13.5f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9FD3E3"); textSize = 11.5f * d; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val rot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#93A0AB"); textSize = 11.5f * d }
    private val valor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E2E8ED"); textSize = 11.5f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    /** Caixas desenhadas no último quadro, para o toque. */
    private var caixas: List<Pair<RectF, String>> = emptyList()

    fun atualizar(p: List<Ponto>, t: List<Topo>) { pontos = p; topos = t; postInvalidate() }

    private fun corta(txt: String, p: Paint, max: Float): String {
        if (p.measureText(txt) <= max) return txt
        val n = p.breakText(txt, true, max - p.measureText("…"), null)
        return txt.take(n.coerceAtLeast(1)) + "…"
    }

    private fun legenda(it: ItemFicha): String = listOf(
        it.posicao,
        it.tag,
        if (it.qtd > 1) "${it.qtd} un." else "",
        it.categoria,
    ).filter { s -> s.isNotBlank() }.joinToString(" · ")

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val W = width.toFloat(); val H = height.toFloat()
        val novas = mutableListOf<Pair<RectF, String>>()
        val pad = 9f * d; val gap = 6f * d
        val lh = 15.5f * d
        val topoMin = reservaTopo + 8f * d
        val baseMax = (if (reservaBase > 0f) reservaBase else H) - 8f * d

        // resumo do objeto, acima dele
        for (t in topos) {
            val bw = minOf(W - 32f * d, 300f * d)
            val linhas = t.linhas.take(6)
            val bh = pad * 2 + lh * 1.15f + lh * (linhas.size + 1)
            var x = t.x - bw / 2; x = x.coerceIn(16f * d, W - bw - 16f * d)
            var y = t.y - bh - 18f * d; y = y.coerceIn(topoMin, maxOf(topoMin, baseMax - bh))
            val r = RectF(x, y, x + bw, y + bh)
            if (t.y in 0f..H && t.x in 0f..W) c.drawLine(t.x, t.y, t.x.coerceIn(r.left + 12f * d, r.right - 12f * d), r.bottom, linha)
            c.drawRoundRect(r, 12f * d, 12f * d, fundo); c.drawRoundRect(r, 12f * d, 12f * d, borda)
            var ty = r.top + pad + titulo.textSize
            c.drawText(corta(t.titulo, titulo, bw - pad * 2), r.left + pad, ty, titulo)
            ty += lh
            c.drawText(t.escala, r.left + pad, ty, sub)
            for ((l, v) in linhas) {
                ty += lh
                c.drawText(corta(l, rot, bw * 0.42f), r.left + pad, ty, rot)
                c.drawText(corta(v, valor, bw * 0.55f - pad), r.left + bw * 0.44f, ty, valor)
            }
        }

        val ps = pontos.filter { it.x > -W * 0.2f && it.x < W * 1.2f && it.y > -H * 0.2f && it.y < H * 1.2f }.sortedBy { it.y }
        if (ps.isNotEmpty()) {
            val compacto = ps.size > 9
            val bw = minOf(W * 0.5f, 250f * d)
            val maxX = ps.maxOf { it.x }; val minX = ps.minOf { it.x }
            val colX = when {
                maxX + 36f * d + bw <= W - 10f * d -> maxX + 36f * d
                minX - 36f * d - bw >= 10f * d -> minX - 36f * d - bw
                else -> W - bw - 10f * d
            }
            val aDireita = colX > (minX + maxX) / 2
            // altura de cada balão
            val alturas = ps.map { p ->
                val ext = p.item.k == selecionado
                val n = if (ext) minOf(p.item.linhas.size, 9) else 0
                pad * 2 + titulo.textSize + (if (compacto && !ext) 0f else lh) + n * lh + (if (ext) 4f * d else 0f)
            }
            // posição vertical: centrado no ponto, empurrando para baixo e depois para cima
            val tops = FloatArray(ps.size) { i -> ps[i].y - alturas[i] / 2 }
            for (i in tops.indices) {
                if (i == 0) tops[i] = maxOf(tops[i], topoMin)
                else tops[i] = maxOf(tops[i], tops[i - 1] + alturas[i - 1] + gap)
            }
            for (i in tops.indices.reversed()) {
                val lim = if (i == tops.lastIndex) baseMax - alturas[i] else tops[i + 1] - gap - alturas[i]
                if (tops[i] > lim) tops[i] = lim
            }
            for (i in ps.indices) {
                val p = ps[i]; val r = RectF(colX, tops[i], colX + bw, tops[i] + alturas[i])
                val sel = p.item.k == selecionado
                val ex = if (aDireita) r.left else r.right
                c.drawLine(p.x, p.y, ex, r.centerY(), linha)
                c.drawCircle(p.x, p.y, 4.5f * d, ponto); c.drawCircle(p.x, p.y, 4.5f * d, pontoAnel)
                c.drawRoundRect(r, 10f * d, 10f * d, fundo); c.drawRoundRect(r, 10f * d, 10f * d, if (sel) bordaSel else borda)
                var ty = r.top + pad + titulo.textSize
                c.drawText(corta(p.item.nome, titulo, bw - pad * 2), r.left + pad, ty, titulo)
                if (!compacto || sel) { ty += lh; c.drawText(corta(legenda(p.item), sub, bw - pad * 2), r.left + pad, ty, sub) }
                if (sel) {
                    ty += 4f * d
                    for ((l, v) in p.item.linhas.take(9)) {
                        ty += lh
                        c.drawText(corta(l, rot, bw * 0.42f), r.left + pad, ty, rot)
                        c.drawText(corta(v, valor, bw * 0.55f - pad), r.left + bw * 0.44f, ty, valor)
                    }
                }
                novas += r to p.item.k
            }
        }
        caixas = novas
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = caixas.lastOrNull { it.first.contains(e.x, e.y) }
        if (hit == null) {
            val livre = tocarLivre ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { toqueX = e.x; toqueY = e.y }
                MotionEvent.ACTION_UP -> if (kotlin.math.hypot(e.x - toqueX, e.y - toqueY) < 24f * d) livre(e.x, e.y)
            }
            return true
        }
        if (e.action == MotionEvent.ACTION_UP) {
            selecionado = if (selecionado == hit.second) null else hit.second
            invalidate()
        }
        return true
    }
}
