package eu.kanade.tachiyomi.animeextension.es.animeav1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeAV1Filters {

    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toQueryParam(paramName: String): String {
            val v = vals[state].second
            return if (v.isNotEmpty()) "&$paramName=$v" else ""
        }
    }

    open class CheckBoxFilterList(name: String, values: List<AnimeFilter.CheckBox>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    class CheckBoxVal(name: String, val queryVal: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    class TypesFilter : CheckBoxFilterList("Tipo", TYPES.map { CheckBoxVal(it.first, it.second) })
    class GenresFilter : CheckBoxFilterList("Género (#)", GENRES.map { CheckBoxVal(it.first, it.second) })
    class StateFilter : QueryPartFilter("Estado", STATES)
    class SortFilter : QueryPartFilter("Orden", SORTS)
    class AudioFilter : QueryPartFilter("Audio / Doblaje", AUDIOS)

    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora los filtros"),
        AudioFilter(),
        TypesFilter(),
        GenresFilter(),
        StateFilter(),
        SortFilter(),
    )

    fun buildQuery(page: Int, query: String, filters: AnimeFilterList): String {
        val cleanQuery = query.trim()
        val audioFilter = filters.filterIsInstance<AudioFilter>().firstOrNull()
        val isDubSelected = audioFilter?.let { it.vals[it.state].second == "dub" } ?: false

        val finalQuery = when {
            isDubSelected && cleanQuery.isNotBlank() -> "$cleanQuery latino"
            isDubSelected -> "latino"
            else -> cleanQuery
        }

        if (finalQuery.isNotBlank()) {
            return "/catalogo?search=$finalQuery&page=$page"
        }
        val sb = StringBuilder("/catalogo?page=$page")
        for (filter in filters) {
            when (filter) {
                is TypesFilter -> {
                    val selected = filter.state.filter { it.state }.map { (it as CheckBoxVal).queryVal }
                    if (selected.isNotEmpty()) {
                        sb.append("&category=").append(selected.joinToString(","))
                    }
                }
                is GenresFilter -> {
                    val selected = filter.state.filter { it.state }.map { (it as CheckBoxVal).queryVal }
                    if (selected.isNotEmpty()) {
                        sb.append("&genre=").append(selected.first())
                    }
                }
                is StateFilter -> sb.append(filter.toQueryParam("status"))
                is SortFilter -> sb.append(filter.toQueryParam("order"))
                else -> {}
            }
        }
        return sb.toString()
    }

    val AUDIOS = arrayOf(
        Pair("Todos", ""),
        Pair("Subtitulado (SUB)", "sub"),
        Pair("Doblado / Latino (DUB)", "dub"),
    )

    val TYPES = arrayOf(
        Pair("Anime", "tv-anime"),
        Pair("Película", "pelicula"),
        Pair("OVA", "ova"),
        Pair("Especial", "especial"),
    )

    val GENRES = arrayOf(
        Pair("Acción", "accion"),
        Pair("Aventura", "aventura"),
        Pair("Ciencia Ficción", "ciencia-ficcion"),
        Pair("Comedia", "comedia"),
        Pair("Deportes", "deportes"),
        Pair("Drama", "drama"),
        Pair("Fantasía", "fantasia"),
        Pair("Misterio", "misterio"),
        Pair("Recuentos de la Vida", "recuentos-de-la-vida"),
        Pair("Romance", "romance"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Sobrenatural", "sobrenatural"),
        Pair("Suspenso", "suspenso"),
        Pair("Terror", "terror"),
        Pair("Antropomórfico", "antropomorfico"),
        Pair("Artes Marciales", "artes-marciales"),
        Pair("Carreras", "carreras"),
        Pair("Detectives", "detectives"),
        Pair("Ecchi", "ecchi"),
        Pair("Elenco Adulto", "elenco-adulto"),
        Pair("Escolares", "escolares"),
        Pair("Espacial", "espacial"),
        Pair("Gore", "gore"),
        Pair("Gourmet", "gourmet"),
        Pair("Harem", "harem"),
        Pair("Histórico", "historico"),
        Pair("Idols", "idols"),
        Pair("Infantil", "infantil"),
        Pair("Isekai", "isekai"),
        Pair("Josei", "josei"),
        Pair("Mahou Shoujo", "mahou-shoujo"),
        Pair("Mecha", "mecha"),
        Pair("Militar", "militar"),
        Pair("Mitología", "mitologia"),
        Pair("Música", "musica"),
        Pair("Parodia", "parodia"),
        Pair("Psicológico", "psicologico"),
        Pair("Samurai", "samurai"),
        Pair("Superpoderes", "superpoderes"),
        Pair("Vampiros", "vampiros"),
    )

    val STATES = arrayOf(
        Pair("Todos", ""),
        Pair("En Emisión", "emision"),
        Pair("Finalizado", "finalizado"),
        Pair("Próximamente", "proximamente"),
    )

    val SORTS = arrayOf(
        Pair("Predeterminado", ""),
        Pair("Populares", "popular"),
        Pair("Últimos Estrenos", "latest_released"),
        Pair("Últimos Agregados", "latest_added"),
        Pair("Puntuación", "score"),
        Pair("Título", "title"),
    )
}
