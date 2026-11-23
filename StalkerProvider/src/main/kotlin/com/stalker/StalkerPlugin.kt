package com.stalker

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

@CloudstreamPlugin
class StalkerPlugin : Plugin() {

    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        StalkerSession.update(
            prefs.getString(KEY_PORTAL, "") ?: "",
            prefs.getString(KEY_MAC, "") ?: ""
        )
        StalkerSession.restoreEndpoint(
            prefs.getString(KEY_ENDPOINT, "") ?: "",
            prefs.getBoolean(KEY_FULL_PORTAL, true)
        )
        registerMainAPI(StalkerProvider())

        // Adds the gear icon next to the provider in Settings > Extensions.
        // Portal URL and MAC are entered here and stored only in the app's
        // private SharedPreferences on the user's device, never in this
        // repository. That storage is NOT encrypted.
        openSettings = { ctx -> showSettings(ctx) }
    }

    private fun showSettings(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dp = ctx.resources.displayMetrics.density

        val portalInput = EditText(ctx).apply {
            hint = "رابط البوابة (يفضّل https:// لو البوابة بتدعمه)"
            setText(prefs.getString(KEY_PORTAL, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val macInput = EditText(ctx).apply {
            hint = "عنوان MAC المرتبط بالاشتراك مثل 00:1A:79:XX:XX:XX"
            setText(prefs.getString(KEY_MAC, ""))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p / 2, p, 0)
            addView(portalInput, rowLp())
            addView(macInput, rowLp())
        }

        fun readInputs(): Pair<String, String> = Pair(
            portalInput.text.toString().trim(),
            macInput.text.toString().trim().uppercase()
        )

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("إعداد بوابة Stalker")
            .setView(layout)
            .setPositiveButton("حفظ", null)
            .setNeutralButton("اختبار الاتصال", null)
            .setNegativeButton("إلغاء", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val (portal, mac) = readInputs()
                if (portal.isBlank() || mac.isBlank()) {
                    Toast.makeText(ctx, "أدخل رابط البوابة وعنوان MAC أولاً", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit()
                        .putString(KEY_PORTAL, portal)
                        .putString(KEY_MAC, mac)
                        // Endpoint/mode are resolved lazily on next request.
                        .remove(KEY_ENDPOINT)
                        .remove(KEY_FULL_PORTAL)
                        .apply()
                    StalkerSession.update(portal, mac)
                    Toast.makeText(
                        ctx,
                        "تم الحفظ. ارجع للرئيسية وحدّث صفحة Stalker Portal",
                        Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val (portal, mac) = readInputs()
                if (portal.isBlank() || mac.isBlank()) {
                    Toast.makeText(ctx, "أدخل رابط البوابة وعنوان MAC أولاً", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "جاري اختبار الاتصال...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val result = testConnection(portal, mac)
                        (ctx as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                            if (result.startsWith("الاتصال ناجح")) {
                                prefs.edit()
                                    .putString(KEY_PORTAL, portal)
                                    .putString(KEY_MAC, mac)
                                    .remove(KEY_ENDPOINT)
                                    .remove(KEY_FULL_PORTAL)
                                    .apply()
                                StalkerSession.update(portal, mac)
                            }
                        }
                    }.start()
                }
            }
        }
        dialog.show()
    }

    private fun rowLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 }

    /**
     * Probes the portal over plain JDK HTTP (the plugin compile classpath has
     * no coroutines). Never echoes the MAC, token, or URLs into the message.
     */
    private fun httpGet(url: String, mac: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
                    "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 234 Safari/533.3"
            )
            setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi")
            setRequestProperty(
                "Cookie",
                "mac=" + URLEncoder.encode(mac, "UTF-8") + "; stb_lang=en"
            )
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.readText() ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun testConnection(portal: String, mac: String): String {
        val rejected = "البوابة رفضت الجهاز. تأكد أن عنوان MAC مسجل ومفعّل في اشتراكك."
        val base = portal.trim().trimEnd('/')
        val candidates = if (base.substringAfterLast('/').endsWith(".php")) {
            listOf(base)
        } else {
            listOf("$base/portal.php", "$base/server/load.php", "$base/stalker_portal/server/load.php")
        }
        for (candidate in candidates) {
            val hs = try {
                httpGet("$candidate?type=stb&action=handshake&token=&JsHttpRequest=1-xml", mac)
            } catch (e: Exception) {
                continue
            }
            if (hs.first == 401 || hs.first == 403) return rejected
            if (hs.first !in 200..299) continue
            val body = hs.second.trim()
            if (body.contains("\"token\"") && !body.contains("\"token\":\"\"")) {
                return "الاتصال ناجح (بوابة كاملة)."
            }
            if (body == "Authorization failed." || body == "Access denied." ||
                body == "Unauthorized request."
            ) {
                return rejected
            }
            val probe = try {
                httpGet("$candidate?type=itv&action=get_genres&JsHttpRequest=1-xml", mac)
            } catch (e: Exception) {
                continue
            }
            if (probe.first == 401 || probe.first == 403) return rejected
            if (probe.first in 200..299 && probe.second.trimStart().startsWith("{\"js\":[")) {
                return "الاتصال ناجح (لوحة مبسطة)."
            }
        }
        return "تعذر الوصول للبوابة. تأكد من الرابط وأن البوابة شغالة."
    }

    companion object {
        private const val PREFS = "stalker_prefs"
        private const val KEY_PORTAL = "portal_url"
        private const val KEY_MAC = "mac"
        private const val KEY_ENDPOINT = "resolved_endpoint"
        private const val KEY_FULL_PORTAL = "full_portal"
    }
}
