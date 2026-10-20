package app.racklab.ar

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ficha do modelo AR (modelos/CODIGO.json), gerada pelo RackLab junto com o .glb.
 * As âncoras estão em metros, no mesmo sistema do modelo (origem no chão, no centro; frente = +Z).
 */
data class ItemFicha(
    val k: String,
    val nome: String,
    val categoria: String,
    val posicao: String,
    val tag: String,
    val qtd: Int,
    val linhas: List<Pair<String, String>>,
    val ancora: FloatArray,
)

data class Ficha(
    val escala: Int,
    val tamanho: FloatArray?,
    val titulo: String,
    val resumo: List<Pair<String, String>>,
    val itens: List<ItemFicha>,
) {
    companion object {
        private fun pares(a: JSONArray?): List<Pair<String, String>> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONArray(i) ?: return@mapNotNull null
                val l = p.optString(0, ""); val v = p.optString(1, "")
                if (l.isEmpty() && v.isEmpty()) null else l to v
            }
        }

        private fun floats(a: JSONArray?, n: Int): FloatArray? {
            if (a == null || a.length() < n) return null
            return FloatArray(n) { a.optDouble(it, 0.0).toFloat() }
        }

        fun ler(texto: String): Ficha {
            val j = JSONObject(texto)
            val tam = floats(j.optJSONArray("tamanho"), 3)
            val res = j.optJSONObject("resumo")
            val arr = j.optJSONArray("itens") ?: JSONArray()
            val itens = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val anc = floats(o.optJSONArray("ancora"), 3) ?: return@mapNotNull null
                ItemFicha(
                    k = o.optString("k"),
                    nome = o.optString("nome", "Equipamento"),
                    categoria = o.optString("cat", ""),
                    posicao = o.optString("pos", ""),
                    tag = o.optString("tag", ""),
                    qtd = o.optInt("qtd", 1).coerceAtLeast(1),
                    linhas = pares(o.optJSONArray("linhas")),
                    ancora = anc,
                )
            }
            return Ficha(
                escala = j.optInt("escala", 0).takeIf { it >= 1 } ?: escalaPara(tam),
                tamanho = tam,
                titulo = res?.optString("titulo", "") ?: "",
                resumo = pares(res?.optJSONArray("linhas")),
                itens = itens,
            )
        }

        /** Maquete: a maior medida fica com até 40 cm. */
        fun escalaPara(tam: FloatArray?): Int {
            val m = tam?.maxOrNull() ?: return 5
            for (n in intArrayOf(2, 5, 10, 20, 50)) if (m / n <= 0.4f) return n
            return 100
        }
    }
}
