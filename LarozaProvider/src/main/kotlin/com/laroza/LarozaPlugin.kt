package com.laroza

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class LarozaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(LarozaProvider())
    }
}
