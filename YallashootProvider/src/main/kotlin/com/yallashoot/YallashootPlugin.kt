package com.yallashoot

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YallashootPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YallashootProvider())
    }
}
