package com.crunchymirror

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
open class CrunchymirrorPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CrunchyrollProvider())
    }
}