package com.stormunblessed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RetroTVEProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RetroTVEProvider())
    }
}
