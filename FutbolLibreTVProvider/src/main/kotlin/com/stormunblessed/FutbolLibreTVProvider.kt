package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.stormunblessed.StreamedInfo
import org.mozilla.javascript.Context
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Calendar


class FutbolLibreTVProvider : MainAPI() {
    override var mainUrl = "https://futbollibre-tv.su"
    override var name = "FutbolLibreTV"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Live
    )
    val streamedInfo: StreamedInfo = StreamedInfo()
    val defaultPoster = "https://ww.futbollibre-tv.su/favicon/android-icon-192x192.png"

    override val mainPage = mainPageOf(
        "es/agenda/" to "Agenda",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        streamedInfo.init()
        val document = app.get(
            "$mainUrl/${request.data}",
            headers = mapOf("X-Timezone" to "America/Mexico_City")
        ).document
        val home = document.select(".menu > li")
            .mapNotNull { it.toSearchResult() }
        val live = home.filter { isEventLive(it.name.take(5)) }
        val items = ArrayList<HomePageList>()
        items.add(
            HomePageList(
                name = "En Vivo",
                list = live,
                isHorizontalImages = true
            )
        )
        items.add(
            HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            )
        )
        return newHomePageResponse(
            list = items,
            hasNext = false
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a")
        val matchTitle = titleElement?.ownText() ?: ""
        if (matchTitle.startsWith("Zapping Sports"))
            return null
        val hour = titleElement?.selectFirst("span")?.text() ?: "00:00"
        val hourLocal = transformHourToLocal(hour)
        val title = "$hourLocal $matchTitle"
        val urls = this.select("ul li").mapNotNull {
            it.selectFirst("a")?.attr("href")?.replaceFirst("^/".toRegex(), "$mainUrl/")
        }
        val posterUrl = streamedInfo.searchPosterByTitle(matchTitle) ?: defaultPoster
        return newLiveSearchResponse(
            title,
            EventData(title, urls, posterUrl).toJson(),
            TvType.Live
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=$query").document
        val results =
            document.select("div.container div.card__cover").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(data: String): LoadResponse? {
        val eventData = AppUtils.tryParseJson<EventData>(data)
        if (eventData == null)
            return null
        return newLiveStreamLoadResponse(eventData.title, data, data) {
            this.posterUrl = eventData.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val eventData = AppUtils.tryParseJson<EventData>(data)
        if (eventData == null)
            return false
        eventData.urls.amap {
            val frame = base64Decode(
                it.substringAfter("?r=")
            )
            if (frame.contains("?stream=")) {
                val name = frame.substringAfter("?stream=")
                val url =
                    if (name.startsWith("evento"))
                        "https://futbollibrelibre.com/tv/canal.php?stream=$name"
                    else "https://futbollibrelibre.com/canales.php?stream=$name"
                val doc = app.get(url, referer = url).document
                val link =
                    doc.select("script").firstOrNull { it.data().contains("var playbackURL = ") }
                        ?.data()
                        ?.substringAfter("var playbackURL = \"")?.substringBefore("\";")
                if (link != null)
                    callback(
                        newExtractorLink(
                            "${this.name}[$name]",
                            "${this.name}[$name]",
                            link,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
            } else if (frame.contains(".php?channel=")) {
                val source = URL(frame).host
                val name = frame.substringAfter(".php?channel=")
                val doc = app.get(frame).document
                var result =
                    doc.select("script").firstOrNull { it.html().contains("playbackURL=") }?.let {
                        var result = ""
                        val scriptContent = it.data().substringBefore("var p2pConfig=")
                        val rhino = Context.enter()
                        rhino.setInterpretedMode(true)
                        val scope = rhino.initStandardObjects()
                        try {
                            scope.put(
                                "atob",
                                scope,
                                object : org.mozilla.javascript.BaseFunction() {
                                    override fun call(
                                        cx: org.mozilla.javascript.Context,
                                        scope: org.mozilla.javascript.Scriptable,
                                        thisObj: org.mozilla.javascript.Scriptable,
                                        args: Array<out Any>
                                    ): Any {
                                        val str = args[0] as String
                                        val decoded =
                                            android.util.Base64.decode(str, Base64.DEFAULT)
                                        return String(decoded, Charsets.UTF_8)
                                    }
                                })
                            rhino.evaluateString(scope, scriptContent, "playbackURL", 1, null)
                            result = scope.get("playbackURL", scope).toString()
                        } finally {
                            rhino.close()
                        }
                        result
                    }
                if (!result.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(
                            "${source}[$name]",
                            "${source}[$name]",
                            result,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
        return true
    }
}

data class EventData(
    val title: String,
    val urls: List<String>,
    val poster: String,
)

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}

fun transformHourToLocal(hourString: String): String {
    // Step 1: Parse the input "16:00" as GMT+1
    val inputFormat = SimpleDateFormat("HH:mm", Locale.US)
    inputFormat.timeZone = TimeZone.getTimeZone("GMT+1")
    val date = inputFormat.parse(hourString)

    // Step 2: Format it into the phone's current timezone
    val outputFormat = SimpleDateFormat("HH:mm", Locale.US)
    outputFormat.timeZone = TimeZone.getDefault() // current mobile timezone
    return outputFormat.format(date)
}

fun isEventLive(startHour: String): Boolean {
    val sdf = SimpleDateFormat("HH:mm", Locale.US)
    sdf.timeZone = TimeZone.getDefault() // interpret in local phone timezone
    val parsedDate = sdf.parse(startHour)
    val now = Calendar.getInstance()
    val startCal = Calendar.getInstance()
    startCal.time = parsedDate
    startCal.set(Calendar.YEAR, now.get(Calendar.YEAR))
    startCal.set(Calendar.MONTH, now.get(Calendar.MONTH))
    startCal.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
    val endCal = startCal.clone() as Calendar
    endCal.add(Calendar.HOUR_OF_DAY, 2)
    return now.timeInMillis in startCal.timeInMillis..endCal.timeInMillis
}

