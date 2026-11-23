package com.aflam4you

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Aflam4youPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Aflam4youProvider())
    }
}
