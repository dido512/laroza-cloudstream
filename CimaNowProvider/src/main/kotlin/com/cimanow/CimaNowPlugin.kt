package com.cimanow

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CimaNowPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CimaNowProvider())
    }
}
