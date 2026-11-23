// Use an integer for version numbers
version = 2

cloudstream {
    description = "تشغيل اشتراك Xtream Codes (IPTV) داخل Cloudstream: قنوات مباشرة وأفلام ومسلسلات من سيرفرك"
    authors = listOf("Mahmoud")
    status = 1
    tvTypes = listOf("Live", "Movie", "TvSeries")
    // Uses Android UI (Context/SharedPreferences) for the in-app settings dialog,
    // so this provider is Android-only.
    isCrossPlatform = false
}

dependencies {
    // Downloads the app's classes.jar (pre-release) as compileOnly so the plugin
    // can extend com.lagradost.cloudstream3.plugins.Plugin and offer openSettings.
    "cloudstream"("com.lagradost:cloudstream3:pre-release")
}
