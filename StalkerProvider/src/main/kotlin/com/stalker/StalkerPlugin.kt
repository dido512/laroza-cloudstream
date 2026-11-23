package com.stalker

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking
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
                        val result = runBlocking { testConnection(portal, mac) }
                        (ctx as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                            if (result.startsWith("الاتصال ناجح")) {
                                prefs.edit()
                                    .putString(KEY_PORTAL, portal)
                                    .putString(KEY_MAC, mac)
                                    .putString(KEY_ENDPOINT, StalkerSession.endpoint)
                                    .putBoolean(KEY_FULL_PORTAL, StalkerSession.fullPortal)
                                    .apply()
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
     * Resolves the portal endpoint, then tries a full handshake + get_profile.
     * Reports only account status/expiry-style fields; never echoes the MAC,
     * token, or request URLs into the message.
     */
    private suspend fun testConnection(portal: String, mac: String): String {
        StalkerSession.update(portal, mac)
        val ok = StalkerSession.ensureEndpoint()
        if (!ok) return "تعذر الوصول للبوابة. تأكد من الرابط وأن البوابة شغالة."
        return try {
            val profile = StalkerSession.request("stb", "get_profile", mapOf("hd" to "1"))
            val js = profile.path("js")
            when {
                js.isMissingNode || js.isNull ->
                    "البوابة ردت لكن بدون بيانات حساب. تأكد أنها بوابة Stalker/Ministra."
                js.path("status").asInt(1) == 0 || js.path("blocked").asInt(0) == 1 ->
                    "الحساب محظور أو غير فعّال في البوابة."
                else -> {
                    val mode = if (StalkerSession.fullPortal) "بوابة كاملة" else "لوحة مبسطة"
                    "الاتصال ناجح ($mode)."
                }
            }
        } catch (e: StalkerSession.AuthException) {
            "البوابة رفضت الجهاز. تأكد أن عنوان MAC مسجل ومفعّل في اشتراكك."
        } catch (e: Exception) {
            "فشل الاتصال بالبوابة. تأكد من الرابط والشبكة."
        }
    }

    companion object {
        private const val PREFS = "stalker_prefs"
        private const val KEY_PORTAL = "portal_url"
        private const val KEY_MAC = "mac"
        private const val KEY_ENDPOINT = "resolved_endpoint"
        private const val KEY_FULL_PORTAL = "full_portal"
    }
}
