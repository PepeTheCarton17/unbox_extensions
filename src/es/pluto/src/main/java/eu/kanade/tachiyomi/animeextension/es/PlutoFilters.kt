package eu.kanade.tachiyomi.animeextension.es.pluto

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object PlutoFilters {
    class Header(name: String) : AnimeFilter.Header(name)

    class Genre(val name: String, val id: String)
    class GenreFilter(name: String, val genres: List<Genre>) :
        AnimeFilter.Select<String>(name, genres.map { it.name }.toTypedArray())

    class AudioFilter : AnimeFilter.Select<String>(
        "Audio / Doblaje",
        arrayOf("Todos", "Subtitulado (SUB)", "Doblado (DUB)")
    ) {
        val vals = arrayOf(
            Pair("all", ""),
            Pair("sub", "sub"),
            Pair("dub", "dub")
        )
    }

    val DEFAULT_GENRES = listOf(
        Genre("Acción", "accion"),
        Genre("Aventura", "aventura"),
        Genre("Ciencia Ficción", "ciencia-ficcion"),
        Genre("Comedia", "comedia"),
        Genre("Deportes", "deportes"),
        Genre("Drama", "drama"),
        Genre("Ecchi", "ecchi"),
        Genre("Fantasía", "fantasia"),
        Genre("Gore", "gore"),
        Genre("Harem", "harem"),
        Genre("Isekai", "isekai"),
        Genre("Mecha", "mecha"),
        Genre("Romance", "romance"),
        Genre("Sci-Fi", "sci-fi"),
        Genre("Shounen", "shounen"),
        Genre("Sobrenatural", "sobrenatural"),
        Genre("Terror", "terror")
    )

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Header("Filtros de búsqueda y catálogo"),
        AudioFilter(),
        GenreFilter("Género (#)", DEFAULT_GENRES)
    )
}
