package com.lagradost.cloudstream3.animeproviders

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*


class GnulaHDProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://ww3.GnulaHD.nu"
    override var name = "GnulaHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.Movie,
            TvType.TvSeries,
            TvType.Anime,
    )

    private val cloudflareKiller = CloudflareKiller()
    suspend fun appGetChildMainUrl(url: String): NiceResponse {
//        return app.get(url, interceptor = cloudflareKiller )
        return app.get(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/ver/?type=Pelicula", "Peliculas"),
            Pair("$mainUrl/ver/?type=Serie", "Series"),
            Pair("$mainUrl/ver/?type=Anime", "Anime"),
            Pair("$mainUrl/ver/?type=Pelicula&order=latest", "Novedades Peliculas"),
            Pair("$mainUrl-ver/?status=&type=Serie&order=latest", "Novedades Series"),
            Pair("$mainUrl-ver/?status=&type=Anime&order=latest", "Novedades Anime"),
        )

        val items = ArrayList<HomePageList>()
        val isHorizontal = true
//        items.add(
//                HomePageList(
//                        "Capítulos actualizados",
//                        appGetChildMainUrl(mainUrl).document.select(".col-6").map {
//                            val title = it.selectFirst("p.animetitles")?.text()
//                                    ?: it.selectFirst(".animetitles")?.text() ?: ""
//                            val poster =
//                                    it.selectFirst("img")?.attr("data-src") ?: ""
//
//                            val epRegex = Regex("episodio-(\\d+)")
//                            val url = it.selectFirst("a")?.attr("href")!!.replace("ver/", "anime/")
//                                    .replace(epRegex, "sub-espanol")
//                            val epNum = (it.selectFirst(".positioning h5")?.text()
//                                    ?: it.selectFirst("div.positioning p")?.text())?.toIntOrNull()
//                            newAnimeSearchResponse(title, url) {
//                                this.posterUrl = fixUrl(poster)
//                                addDubStatus(getDubStatus(title), epNum)
//                                this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
//                            }
//                        }, isHorizontal)
//        )

        urls.amap { (url, name) ->
            val home = appGetChildMainUrl(url).document.select("article.bs").map {
                val title = it.selectFirst("a")!!.attr("title")
                val imgElement = it.selectFirst("a div.limit img")
                val poster = imgElement?.attr("src") ?: ""

                newAnimeSearchResponse(title, fixUrl(it.selectFirst("a")!!.attr("href").replace("/ver/", "/"))) {
                    this.posterUrl = fixUrl(poster)
                    addDubStatus(getDubStatus(title))
                    this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
                }
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return appGetChildMainUrl("$mainUrl/?s=$query").document.select("article.bs").map {
            val title = it.selectFirst("a")!!.attr("title")
            val href = fixUrl(it.selectFirst("a")!!.attr("href").replace("/ver/", "/"))
            val image = it.selectFirst("a div.limit img")!!.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime){
                this.posterUrl = fixUrl(image)
                this.dubStatus = if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                            DubStatus.Dubbed
                    ) else EnumSet.of(DubStatus.Subbed)
//                this.posterHeaders = if (image.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = appGetChildMainUrl(url).document
        val poster = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
        val backimage = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
        val title = doc.selectFirst("div.col-lg-9.col-md-8 h2")!!.text()
        val type = doc.selectFirst("div.chapterdetls2")?.text() ?: ""
        val description = doc.selectFirst("div.col-lg-9.col-md-8 p.my-2.opacity-75")!!.text().replace("Ver menos", "")
        val genres = doc.select("div.col-lg-9.col-md-8 a div.btn").map { it.text() }
        val status = when (doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha div.my-2")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select("div.container div.row div.row div a").map {
            val name = it.selectFirst("div.cap-layout")!!.text()
            val link = it!!.attr("href")
            val epThumb = it.selectFirst(".animeimghv")?.attr("data-src")
                    ?: it.selectFirst("div.animeimgdiv img.animeimghv")?.attr("src")
            newEpisode(link){
                this.name = name
            }
        }
        return newAnimeLoadResponse(title, url, getType(title)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
            posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
        }
    }

    suspend fun customLoadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit)
    {
        loadExtractor(url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com","https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            .replaceFirst("https://cybervynx.com", "https://streamwish.to")
            .replaceFirst("https://dumbalag.com", "https://streamwish.to")
            .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
            .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://uqload.io", "https://uqload.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
            , referer, subtitleCallback, callback)
            
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        appGetChildMainUrl(data).document.select("li#play-video").amap {
            val encodedurl = it.select("a").attr("data-player")
            val urlDecoded = base64Decode(encodedurl)
            Log.d("depurando", "urlDecoded: $urlDecoded")
            val url = (urlDecoded)
                .replace("https://monoschinos2.com/reproductor?url=", "")
                .replace("https://mojon.latanime.org/aqua/fn?url=", "")
            customLoadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}