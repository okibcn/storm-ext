package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class RetroTVEProvider : MainAPI() {
    override var mainUrl = "https://retrotve.com"
    override var name = "RetroTVE"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        
        val doc = app.get(mainUrl).document
        val items = ArrayList<HomePageList>()

        // "Últimas Series" Section
        // Structure: div.Top > div.Title:contains(Últimas Series) ... sibling ul.MovieList
        val seriesSection = doc.select("div.Top:has(div.Title:contains(Últimas Series)) + ul.MovieList").select("article.TPost")
        val seriesItems = seriesSection.mapNotNull { toSearchResponse(it) }
        if (seriesItems.isNotEmpty()) {
            items.add(HomePageList("Últimas Series", seriesItems))
        }

        // "Últimas Películas" Section
        // Structure: div.Top > h1.Title:contains(Últimas Películas) ... sibling ul.MovieList
        val moviesSection = doc.select("div.Top:has(h1.Title:contains(Últimas Películas)) + ul.MovieList").select("article.TPost")
        val movieItems = moviesSection.mapNotNull { toSearchResponse(it) }
        if (movieItems.isNotEmpty()) {
            items.add(HomePageList("Últimas Películas", movieItems))
        }

        // "Últimos Episodios" Section
        val episodesSection = doc.select("section:has(div.Title:contains(Últimas Episodios)) ul.MovieList article.TPost")
        val episodeItems = episodesSection.mapNotNull { toSearchResponse(it, true) }
        if (episodeItems.isNotEmpty()) {
            items.add(HomePageList("Últimos Episodios", episodeItems))
        }

        // "Recientes" (Widget Peliculas)
        val recentMoviesWidget = doc.select("div.Wdgt:has(div.Title:contains(Peliculas)) ul.MovieList li")
        val recentItems = recentMoviesWidget.mapNotNull { 
            val tPost = it.selectFirst("div.TPost") ?: return@mapNotNull null
            toSearchResponse(tPost) 
        }
        if (recentItems.isNotEmpty()) {
            items.add(HomePageList("Recientes", recentItems))
        }

        return newHomePageResponse(items)
    }

    private fun toSearchResponse(element: Element, isEpisode: Boolean = false): SearchResponse? {
        val title = element.selectFirst(".Title")?.text()?.trim() ?: return null
        val href = element.selectFirst("a")?.attr("href") ?: return null
        var poster = element.selectFirst("img")?.attr("src")
            ?: element.selectFirst("img")?.attr("data-src") // Fallback

        if (isEpisode) {
             // For episodes in "Últimas Episodios", we want them to behave like a movie (direct play)
             // We can use TvType.Movie and the load function will handle it.
             val episodeTitle = element.selectFirst("figcaption")?.text()?.trim() ?: title
             
             // Convert vertical poster to larger horizontal thumbnail
             // Change w185 (small vertical) to w780 (large, better for horizontal display)
             poster = poster?.replace("/w185/", "/w780/")
             
             // Ensure https protocol
             if (poster?.startsWith("//") == true) {
                 poster = "https:$poster"
             }
             
             return newMovieSearchResponse(title + " - " + episodeTitle, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        val type = if (href.contains("/serie/") || href.contains("/seriestv/")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        
        return doc.select("article.TPost").mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.Title")?.text() ?: "Unknown"
        val poster = doc.selectFirst("div.Image figure img")?.attr("src") 
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = doc.selectFirst("div.Description")?.text()
        val year = doc.selectFirst("span.Year")?.text()?.toIntOrNull()
        
        // Check if it's a movie OR an episode treated as a movie (direct play)
        val isMovie = url.contains("/pelicula/") || url.contains("episodio")
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            var episodes = doc.select("td.MvTbTtl a").mapNotNull {
                val epTitle = it.text()
                val epHref = it.attr("href")
                val regex = Regex("temporada-(\\d+)-episodio-(\\d+)")
                val match = regex.find(epHref) 
                    ?: Regex("temporada(\\d+)-episodio(\\d+)").find(epHref)
                
                val season = match?.groupValues?.get(1)?.toIntOrNull()
                val episode = match?.groupValues?.get(2)?.toIntOrNull()
                
                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = season
                    this.episode = episode
                }
            }

            if (episodes.isEmpty()) {
                episodes = doc.select("a[href*='/seriestv/'][href*='temporada']").mapNotNull {
                    val epTitle = it.text()
                    val epHref = it.attr("href")
                    val regex = Regex("temporada-(\\d+)-episodio-(\\d+)")
                    val match = regex.find(epHref)
                        ?: Regex("temporada(\\d+)-episodio(\\d+)").find(epHref)

                    val season = match?.groupValues?.get(1)?.toIntOrNull()
                    val episode = match?.groupValues?.get(2)?.toIntOrNull()

                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = season
                        this.episode = episode
                    }
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // 1. Process div.TPlayerTb elements (handles multiple video options)
        doc.select("div.TPlayerTb").forEach { div ->
            val content = div.html()
            
            // Check for direct iframe first
            val directIframe = div.selectFirst("iframe")
            if (directIframe != null) {
                var src = directIframe.attr("src")
                // Sanitize URL
                src = src.replace("&#038;", "&").replace("&amp;", "&")
                
                if (src.contains("youtube")) return@forEach
                
                if (src.startsWith("https://retrotve.com/?trembed")) {
                    val embedDoc = app.get(src).document
                    embedDoc.select("iframe").forEach { internalIframe ->
                        var internalSrc = internalIframe.attr("src")
                        if (internalSrc.contains("youtube")) return@forEach
                        if (internalSrc.contains("vkvideo.ru")) {
                            internalSrc = internalSrc.replace("vkvideo.ru", "vk.com")
                        }
                        loadExtractor(internalSrc, data, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            // Check for encoded iframe
            else if (content.contains("&lt;iframe")) {
                val decoded = content.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                val srcMatch = Regex("src=\"(.*?)\"").find(decoded)
                var src = srcMatch?.groupValues?.get(1)
                if (src != null) {
                    // Sanitize URL
                    src = src.replace("&#038;", "&").replace("&amp;", "&")
                    
                    if (src.contains("youtube")) return@forEach
                    if (src.startsWith("https://retrotve.com/?trembed")) {
                        val embedDoc = app.get(src).document
                        embedDoc.select("iframe").forEach { internalIframe ->
                            var internalSrc = internalIframe.attr("src")
                            if (internalSrc.contains("youtube")) return@forEach
                            if (internalSrc.contains("vkvideo.ru")) {
                                internalSrc = internalSrc.replace("vkvideo.ru", "vk.com")
                            }
                            loadExtractor(internalSrc, data, subtitleCallback, callback)
                        }
                    } else {
                        loadExtractor(src, data, subtitleCallback, callback)
                    }
                }
            }
        }
        
        // 2. Fallback: Global iframe check (for pages without TPlayerTb)
        doc.select("iframe").forEach { iframe ->
            // Skip if already processed in TPlayerTb
            if (iframe.parent()?.hasClass("TPlayerTb") == true) return@forEach
            
            var src = iframe.attr("src")
            // Sanitize URL
            src = src.replace("&#038;", "&").replace("&amp;", "&")
            
            if (src.contains("youtube")) return@forEach
            
            if (src.startsWith("https://retrotve.com/?trembed")) {
                val embedDoc = app.get(src).document
                embedDoc.select("iframe").forEach { internalIframe ->
                    var internalSrc = internalIframe.attr("src")
                    if (internalSrc.contains("youtube")) return@forEach
                    if (internalSrc.contains("vkvideo.ru")) {
                        internalSrc = internalSrc.replace("vkvideo.ru", "vk.com")
                    }
                    loadExtractor(internalSrc, data, subtitleCallback, callback)
                }
            } else {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        // 3. Data-iframe attribute (legacy support)
        doc.select("[data-iframe]").forEach { element ->
            val iframeHtml = element.attr("data-iframe")
            val srcMatch = Regex("src=\"(.*?)\"").find(iframeHtml)
            var src = srcMatch?.groupValues?.get(1)
            if (src != null) {
                // Sanitize URL
                src = src.replace("&#038;", "&").replace("&amp;", "&")
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
