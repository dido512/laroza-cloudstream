package com.xtream

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CloudstreamPlugin
class XtreamPlugin : Plugin() {

    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        XtreamSession.update(
            prefs.getString(KEY_SERVER, "") ?: "",
            prefs.getString(KEY_USER, "") ?: "",
            prefs.getString(KEY_PASS, "") ?: ""
        )
        for (kind in listOf("live", "movie", "series")) {
            XtreamSession.selected[kind] = prefs.getString("cat_" + kind, "") ?: ""
        }
        registerMainAPI(XtreamProvider())

        // Adds the gear icon next to the provider in Settings > Extensions.
        // Credentials are entered here and stored only in the app's private
        // SharedPreferences on the user's device, never in this repository.
        openSettings = { ctx -> showSettings(ctx) }
    }

    private fun showSettings(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dp = ctx.resources.displayMetrics.density

        val serverInput = EditText(ctx).apply {
            hint = "رابط السيرفر (يفضّل https:// لو الاشتراك بيدعمه)"
            setText(prefs.getString(KEY_SERVER, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val userInput = EditText(ctx).apply {
            hint = "اسم المستخدم"
            setText(prefs.getString(KEY_USER, ""))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passInput = EditText(ctx).apply {
            hint = "كلمة المرور"
            setText(prefs.getString(KEY_PASS, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p / 2, p, 0)
            addView(serverInput, rowLp())
            addView(userInput, rowLp())
            addView(passInput, rowLp())
        }

        layout.addView(TextView(ctx).apply {
            text = "اختر الأقسام هنا. الصفحة تحمل القسم المختار فقط. البحث الشامل حسب دعم السيرفر، وإلا فهو جزئي في الأقسام المفتوحة فقط. احفظ بيانات الدخول أولاً قبل اختيار الأقسام."
        }, rowLp())
        for ((kind, label) in listOf("live" to "القنوات", "movie" to "الأفلام", "series" to "المسلسلات")) {
            layout.addView(Button(ctx).apply {
                text = "اختيار قسم " + label
                setOnClickListener {
                    if (!XtreamSession.hasCredentials) {
                        Toast.makeText(ctx, "احفظ بيانات الدخول أولاً", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, "جاري جلب أسماء الأقسام فقط", Toast.LENGTH_SHORT).show()
                        Thread {
                            val result = runCatching {
                                val action = when (kind) { "live" -> "get_live_categories"; "movie" -> "get_vod_categories"; else -> "get_series_categories" }
                                val conn = URL(XtreamSession.apiBase() + "&action=" + action).openConnection() as HttpURLConnection
                                conn.connectTimeout = 15000; conn.readTimeout = 30000
                                val body = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
                                val json = JSONArray(body)
                                (0 until json.length()).map { json.getJSONObject(it) }
                            }
                            (ctx as? android.app.Activity)?.runOnUiThread {
                                val rows = result.getOrNull()
                                if (rows == null) Toast.makeText(ctx, "تعذر جلب الأقسام. راجع اختبار الاتصال", Toast.LENGTH_LONG).show()
                                else AlertDialog.Builder(ctx).setTitle("قسم " + label)
                                    .setItems((listOf("بدون قسم") + rows.map { it.optString("category_name") }).toTypedArray()) { _, index ->
                                        val id = if (index == 0) "" else rows[index - 1].optString("category_id")
                                        prefs.edit().putString("cat_" + kind, id).apply()
                                        XtreamSession.selected[kind] = id
                                        Toast.makeText(ctx, "تم اختيار القسم. حدّث صفحة الإضافة", Toast.LENGTH_LONG).show()
                                    }.show()
                            }
                        }.start()
                    }
                }
            }, rowLp())
        }

        fun readInputs(): Triple<String, String, String> = Triple(
            XtreamSession.normalizeServer(serverInput.text.toString()),
            userInput.text.toString().trim(),
            passInput.text.toString()
        )

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("بيانات اشتراك Xtream")
            .setView(android.widget.ScrollView(ctx).apply { addView(layout) })
            .setPositiveButton("حفظ", null)
            .setNeutralButton("اختبار الاتصال", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val (server, user, pass) = readInputs()
                if (server.isBlank() || user.isBlank() || pass.isBlank()) {
                    Toast.makeText(ctx, "أكمل البيانات الثلاثة أولاً", Toast.LENGTH_LONG).show()
                } else {
                    if (server != XtreamSession.server || user != XtreamSession.username || pass != XtreamSession.password) {
                        XtreamSession.selected.clear()
                        prefs.edit().remove("cat_live").remove("cat_movie").remove("cat_series").apply()
                    }
                    prefs.edit()
                        .putString(KEY_SERVER, server)
                        .putString(KEY_USER, user)
                        .putString(KEY_PASS, pass)
                        .apply()
                    XtreamSession.update(server, user, pass)
                    Toast.makeText(
                        ctx,
                        "تم الحفظ. ارجع للرئيسية وحدّث صفحة Xtream IPTV",
                        Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val (server, user, pass) = readInputs()
                if (server.isBlank() || user.isBlank() || pass.isBlank()) {
                    Toast.makeText(ctx, "أكمل البيانات الثلاثة أولاً", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "جاري اختبار الاتصال...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val result = testConnection(server, user, pass)
                        (ctx as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
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

    /** Calls player_api.php without an action: returns account status/expiry as a user message. */
    private fun testConnection(server: String, user: String, pass: String): String {
        return try {
            val url = "$server/player_api.php?username=" +
                XtreamSession.encode(user) + "&password=" + XtreamSession.encode(pass)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
            }
            val body = try {
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
            val json = JSONObject(body)
            val info = json.optJSONObject("user_info")
                ?: return "السيرفر رد لكن بدون بيانات حساب. تأكد أنه سيرفر Xtream Codes."
            when {
                info.optInt("auth", 0) == 0 ->
                    "فشل الدخول: تأكد من اسم المستخدم وكلمة المرور"
                info.optString("status") != "Active" ->
                    "الحساب غير فعّال: " + info.optString("status")
                else -> {
                    val exp = info.optString("exp_date").toLongOrNull()?.let {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000))
                    } ?: "غير محدد"
                    "الاتصال ناجح. الحالة: Active - الانتهاء: $exp - الأجهزة المسموحة: " +
                        info.optString("max_connections")
                }
            }
        } catch (e: Exception) {
            "فشل الاتصال بالسيرفر. تأكد من الرابط وأن السيرفر شغال."
        }
    }

    companion object {
        private const val PREFS = "xtream_prefs"
        private const val KEY_SERVER = "server"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
    }
}
