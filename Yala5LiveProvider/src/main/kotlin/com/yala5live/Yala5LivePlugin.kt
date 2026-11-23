package com.yala5live

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Yala5LivePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Yala5LiveProvider())
    }
}
