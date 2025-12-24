package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*
import kotlin.collections.ArrayList


class MonoschinosProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Película")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://wvv.monoschinos2.net"
    override var name = "Monoschinos"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.AnimeMovie,
            TvType.OVA,
            TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val isHorizontal = true
        
        val doc = app.get(mainUrl).document
        
        // Capítulos actualizados - based on browser inspection
        val latestEpisodes = doc.select("#carrusel ul li article").mapNotNull {
            val title = it.selectFirst("h2")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src") ?: ""
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epNum = it.selectFirst("span.episode")?.text()?.toIntOrNull()
            
            newAnimeSearchResponse(title, fixUrl(link)) {
                this.posterUrl = fixUrl(poster)
                addDubStatus(getDubStatus(title), epNum)
            }
        }
        
        if (latestEpisodes.isNotEmpty()) {
            items.add(HomePageList("Capítulos actualizados", latestEpisodes, isHorizontal))
        }

        // Series recientes
        val recentSeries = doc.select("ul.row.row-cols-xl-5 li.col").mapNotNull {
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src") ?: ""
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            
            newAnimeSearchResponse(title, fixUrl(link)) {
                this.posterUrl = fixUrl(poster)
                addDubStatus(getDubStatus(title))
            }
        }
        if (recentSeries.isNotEmpty()) {
            items.add(HomePageList("Series recientes", recentSeries))
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/animes?buscar=$query").document.select("ul.row li.col article").mapNotNull {
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.attr("data-src") ?: ""
            
            newAnimeSearchResponse(title, fixUrl(link)) {
                this.posterUrl = fixUrl(image)
                addDubStatus(getDubStatus(title))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.fs-2")?.text() ?: ""
        val poster = doc.selectFirst("img.w-100")?.attr("data-src") ?: ""
        val banner = doc.selectFirst("img.rounded-3")?.attr("data-src") ?: ""
        val synopsis = doc.selectFirst("div.mb-3 p")?.text() ?: ""
        val genres = doc.select("div.my-4 > div a span").map { it.text() }
        val statusText = doc.selectFirst("div.col:nth-child(1) > div:nth-child(1) > div")?.text()
        val status = when {
            statusText?.contains("Estreno", true) == true -> ShowStatus.Ongoing
            statusText?.contains("Finalizado", true) == true -> ShowStatus.Completed
            else -> null
        }
        
        val episodes = doc.select("ul.list-group li.list-group-item a").mapNotNull {
            val href = it.attr("href")
            val name = it.text()
            val epNum = Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            
            newEpisode(fixUrl(href)) {
                this.episode = epNum
                this.name = name
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(banner)
            this.plot = synopsis
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Direct iframe
        doc.select("iframe.embed-responsive-item").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        // Check for other servers if available (e.g. buttons)
        // Based on inspection, sometimes servers are loaded dynamically or just one is present.
        // If there are buttons for servers, they might load content into an iframe.
        // For now, we extract what's visible.
        
        return true
    }
}