package id.berdua.privatechat

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.location.Location

private const val PORT = 8787
private const val REQ_PICK_MEDIA = 2101
private const val REQ_SAVE_MEDIA = 2102
private const val REQ_MIC = 2103
private const val REQ_LOCATION = 2104
private const val REQ_NOTIFICATIONS = 2105
private const val MAX_ATTACHMENT_BYTES = 900_000

private object Palette {
    const val BG = 0xFF050505.toInt()
    const val PANEL = 0xFF111113.toInt()
    const val PANEL_2 = 0xFF1A1A1D.toInt()
    const val BORDER = 0xFF303035.toInt()
    const val WHITE = 0xFFF5F5F5.toInt()
    const val MUTED = 0xFF9A9AA1.toInt()
    const val ACCENT = 0xFFD4D4D8.toInt()
}

data class ChatMessage(
    val id: Long,
    val senderId: String,
    val type: String,
    val text: String,
    val mime: String = "",
    val data: String = "",
    val time: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("senderId", senderId).put("type", type)
        .put("text", text).put("mime", mime).put("data", data).put("time", time)

    companion object {
        fun fromJson(j: JSONObject) = ChatMessage(
            id = j.optLong("id"), senderId = j.optString("senderId"),
            type = j.optString("type", "text"), text = j.optString("text"),
            mime = j.optString("mime"), data = j.optString("data"), time = j.optLong("time")
        )
    }
}

/** Stores messages only in this app's private internal files directory. */
class MessageStore(context: Context, filename: String) {
    private val file = File(context.filesDir, filename)
    private val lock = Any()
    private val messages = mutableListOf<ChatMessage>()

    init {
        synchronized(lock) {
            try {
                if (file.exists()) {
                    val array = JSONArray(file.readText())
                    for (i in 0 until array.length()) messages.add(ChatMessage.fromJson(array.getJSONObject(i)))
                }
            } catch (_: Exception) {
                messages.clear()
            }
        }
    }

    fun all(): List<ChatMessage> = synchronized(lock) { messages.sortedBy { it.id }.toList() }

    fun upsert(message: ChatMessage) {
        synchronized(lock) {
            if (messages.any { it.id == message.id }) return
            messages.add(message)
            messages.sortBy { it.id }
            // Keep both item count and total private storage bounded.
            var array = JSONArray()
            fun rebuild() {
                array = JSONArray()
                messages.forEach { array.put(it.toJson()) }
            }
            rebuild()
            while (messages.size > 120 || array.toString().length > 8_000_000) {
                if (messages.isEmpty()) break
                messages.removeAt(0)
                rebuild()
            }
            try { file.writeText(array.toString()) } catch (_: Exception) { }
        }
    }
}

/** Small embedded HTTP API. It accepts exactly one paired remote device. */
class LocalChatServer(
    private val context: Context,
    private val pairCode: String,
    private val store: MessageStore
) : NanoHTTPD("0.0.0.0", PORT) {
    private val prefs = context.getSharedPreferences("berdua_pairing", Context.MODE_PRIVATE)

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val method = session.method
        val suppliedCode = session.headers["x-pair-code"].orEmpty()
        if (suppliedCode != pairCode) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Kode pasangan salah\"}")

        if (path == "/api/pair" && method == Method.POST) {
            val body = readBody(session)
            val remoteId = try { JSONObject(body).optString("deviceId") } catch (_: Exception) { "" }
            if (remoteId.length < 12) return json(Response.Status.BAD_REQUEST, "{\"error\":\"deviceId tidak valid\"}")
            synchronized(prefs) {
                val existing = prefs.getString("paired_device_id", "").orEmpty()
                if (existing.isNotEmpty() && existing != remoteId) {
                    return json(Response.Status.FORBIDDEN, "{\"error\":\"Sudah ada satu perangkat kedua yang dipasangkan\"}")
                }
                prefs.edit().putString("paired_device_id", remoteId).apply()
            }
            return json(Response.Status.OK, "{\"ok\":true,\"message\":\"Perangkat dipasangkan\"}")
        }

        val remoteId = session.headers["x-device-id"].orEmpty()
        val pairedId = prefs.getString("paired_device_id", "").orEmpty()
        if (pairedId.isEmpty() || remoteId != pairedId) {
            return json(Response.Status.FORBIDDEN, "{\"error\":\"Perangkat belum dipasangkan\"}")
        }

        return when {
            path == "/api/health" && method == Method.GET -> json(Response.Status.OK, "{\"active\":true}")
            path == "/api/messages" && method == Method.GET -> {
                val array = JSONArray()
                store.all().forEach { array.put(it.toJson()) }
                json(Response.Status.OK, array.toString())
            }
            path == "/api/messages" && method == Method.POST -> {
                val body = readBody(session)
                try {
                    val message = ChatMessage.fromJson(JSONObject(body))
                    if (message.id <= 0 || message.senderId != remoteId) {
                        return json(Response.Status.BAD_REQUEST, "{\"error\":\"Pesan tidak valid\"}")
                    }
                    if (message.data.length > 1_300_000) {
                        return json(Response.Status.PAYLOAD_TOO_LARGE, "{\"error\":\"Lampiran terlalu besar\"}")
                    }
                    store.upsert(message)
                    json(Response.Status.OK, "{\"ok\":true}")
                } catch (_: Exception) {
                    json(Response.Status.BAD_REQUEST, "{\"error\":\"JSON tidak valid\"}")
                }
            }
            else -> json(Response.Status.NOT_FOUND, "{\"error\":\"Tidak ditemukan\"}")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).build()
    private val prefs by lazy { getSharedPreferences("berdua_app", MODE_PRIVATE) }
    private val deviceId by lazy {
        prefs.getString("device_id", null) ?: (java.util.UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("device_id", it).apply()
        })
    }

    private var mode = "home" // home, host, client
    private var server: LocalChatServer? = null
    private var store: MessageStore? = null
    private var pairCode = ""
    private var serverUrl = ""
    private var composer: EditText? = null
    private var messageList: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var statusLine: TextView? = null
    private var urlInput: EditText? = null
    private var codeInput: EditText? = null
    private var pendingMediaType = "image"
    private var pendingSave: ChatMessage? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var firstPoll = true
    private var lastIncomingId = 0L
    private var pollRunning = false
    private var externalPickerPending = false
    private var recordButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        window.decorView.systemUiVisibility = 0
        createNotificationChannel()
        showHome()
    }

    override fun onStart() {
        super.onStart()
        if (mode == "host" && server == null && store != null) restartHostServer()
        if (mode == "host" || mode == "client") schedulePoll()
    }

    override fun onStop() {
        pollRunning = false
        handler.removeCallbacksAndMessages(null)
        // Keep the host available only while its app is on screen. A system picker is a user-initiated exception.
        if (mode == "host" && !externalPickerPending) stopServer()
        super.onStop()
    }

    override fun onDestroy() {
        pollRunning = false
        handler.removeCallbacksAndMessages(null)
        stopServer()
        stopRecorderSilently()
        super.onDestroy()
    }

    private fun restartHostServer() {
        try {
            server = LocalChatServer(this, pairCode, store ?: MessageStore(this, "host-messages.json").also { store = it })
            server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (_: Exception) {
            stopServer()
            toast("Server lokal tidak bisa aktif kembali")
        }
    }

    private fun showHome() {
        mode = "home"
        stopServer()
        handler.removeCallbacksAndMessages(null)
        val root = column()
        root.setPadding(dp(24), dp(38), dp(24), dp(24))
        root.addView(text("berdua.", 34, Palette.WHITE, true).apply { letterSpacing = -0.04f })
        root.addView(text("RUANG KECIL UNTUK DUA PERANGKAT", 10, Palette.MUTED, true).apply {
            letterSpacing = 0.12f; setPadding(0, dp(3), 0, dp(26))
        })

        val card = column().apply {
            background = rounded(Palette.PANEL, 20, Palette.BORDER)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(text("Pribadi, bukan tersembunyi dari izin", 17, Palette.WHITE, true))
        card.addView(text(
            "Pesan dan lampiran disimpan di penyimpanan internal aplikasi. Lokasi dan mikrofon hanya diminta saat tombol fiturnya dipakai. Kedua orang perlu setuju sebelum berbagi apa pun.",
            13, Palette.MUTED, false
        ).apply { setPadding(0, dp(9), 0, dp(12)) })
        card.addView(text("Tidak ada akun atau username. Kode pasangan membatasi akses ke satu perangkat kedua.", 12, Palette.ACCENT, false))
        root.addView(card, matchWrap().apply { bottomMargin = dp(18) })
        root.addView(actionButton("JADIKAN HP PERTAMA  ·  SERVER", "Mulai server di HP ini") { startHost() }, matchWrap().apply { bottomMargin = dp(12) })
        root.addView(actionButton("SAMBUNGKAN HP KEDUA", "Masukkan URL tunnel dan kode pasangan") { showJoinSetup() }, matchWrap().apply { bottomMargin = dp(22) })

        val note = column().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        note.addView(text("CATATAN KONEKSI", 10, Palette.MUTED, true).apply { letterSpacing = .1f })
        note.addView(text(
            "Build awal ini menanam server ke APK, jadi tidak perlu Termux untuk server lokal. URL publik ngrok belum dibuat otomatis pada versi ini. GitHub hanya membangun APK; ngrok tetap menjadi perantara jaringan, bukan penyimpanan chat.",
            12, Palette.MUTED, false
        ).apply { setPadding(0, dp(7), 0, 0) })
        root.addView(note)
        setContentView(root)
        maybeAskNotificationPermission()
    }

    private fun showJoinSetup() {
        mode = "setup"
        val root = column().apply { setPadding(dp(22), dp(28), dp(22), dp(24)) }
        root.addView(actionText("‹  KEMBALI") { showHome() }, matchWrap().apply { bottomMargin = dp(20) })
        root.addView(text("Sambungkan perangkat", 25, Palette.WHITE, true))
        root.addView(text("URL ngrok HTTPS dan kode pasangan dari HP pertama.", 13, Palette.MUTED).apply {
            setPadding(0, dp(6), 0, dp(22))
        })
        root.addView(field("https://alamat-anda.ngrok-free.app").also { urlInput = it }, matchWrap().apply { bottomMargin = dp(12) })
        root.addView(field("Kode pasangan").also { codeInput = it }, matchWrap().apply { bottomMargin = dp(16) })
        root.addView(actionButton("HUBUNGKAN", "Pasangkan hanya satu perangkat kedua") { connectClient() }, matchWrap())
        root.addView(text(
            "Kode pasangan bukan akun. Jangan bagikan URL dan kode ke orang lain. Tunnel ngrok harus meneruskan koneksi ke port 8787 di HP pertama.",
            12, Palette.MUTED
        ).apply { setPadding(dp(2), dp(18), dp(2), 0) })
        setContentView(root)
    }

    private fun startHost() {
        stopServer()
        pairCode = prefs.getString("pair_code", null) ?: generateCode().also {
            prefs.edit().putString("pair_code", it).apply()
        }
        store = MessageStore(this, "host-messages.json")
        server = LocalChatServer(this, pairCode, store!!)
        try {
            server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            mode = "host"
            showChat()
        } catch (e: IOException) {
            stopServer()
            toast("Server tidak bisa dimulai: ${e.message ?: "coba lagi"}")
        }
    }

    private fun stopServer() {
        try { server?.stop() } catch (_: Exception) { }
        server = null
    }

    private fun connectClient() {
        val url = urlInput?.text?.toString()?.trim().orEmpty().trimEnd('/')
        val code = codeInput?.text?.toString()?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (!url.startsWith("https://") || !url.contains('.')) {
            toast("Masukkan URL publik HTTPS yang valid")
            return
        }
        if (code.length < 10) {
            toast("Kode pasangan belum lengkap")
            return
        }
        serverUrl = url
        pairCode = code
        val json = JSONObject().put("deviceId", deviceId).put("label", "Perangkat kedua")
        Thread {
            try {
                val request = Request.Builder().url("$serverUrl/api/pair")
                    .header("x-pair-code", pairCode).header("x-device-id", deviceId)
                    .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException(response.body?.string() ?: "Pasangan ditolak (${response.code})")
                    runOnUiThread {
                        mode = "client"
                        store = MessageStore(this, "client-messages.json")
                        prefs.edit().putString("server_url", serverUrl).putString("pair_code_client", pairCode).apply()
                        showChat()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal menyambung: ${e.message?.take(120) ?: "periksa URL dan kode"}") }
            }
        }.start()
    }

    private fun showChat() {
        firstPoll = true
        pollRunning = true
        val root = column()
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = rounded(Palette.BG, 0, Palette.BORDER)
        }
        val titleBox = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titleBox.addView(text("berdua.", 22, Palette.WHITE, true))
        titleBox.addView(text(if (mode == "host") "HP pertama · server aktif" else "HP kedua · sesi chat", 11, Palette.MUTED))
        header.addView(titleBox)
        header.addView(actionText("KELUAR") { showHome() })
        root.addView(header, matchWrap())

        if (mode == "host") {
            val codeCard = column().apply {
                background = rounded(Palette.PANEL, 16, Palette.BORDER)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            codeCard.addView(text("KODE PASANGAN · BAGIKAN HANYA KE SATU ORANG", 9, Palette.MUTED, true))
            codeCard.addView(text(pairCode.chunked(4).joinToString(" "), 21, Palette.WHITE, true).apply {
                setPadding(0, dp(5), 0, dp(2)); letterSpacing = .12f
            })
            codeCard.addView(text("Server lokal: port $PORT · aktif selama proses aplikasi berjalan", 11, Palette.MUTED))
            root.addView(codeCard, matchWrap().apply { setMargins(dp(14), dp(10), dp(14), dp(6)) })
            root.addView(text("Tunnel ngrok belum dihidupkan otomatis oleh build ini.", 11, Palette.MUTED).apply {
                setPadding(dp(18), dp(3), dp(18), dp(5))
            })
        } else {
            val state = text("TERHUBUNG · MENYINKRONKAN PESAN", 10, Palette.MUTED, true)
            statusLine = state
            root.addView(state, matchWrap().apply { setMargins(dp(18), dp(11), dp(18), dp(6)) })
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        messageList = column()
        scrollView!!.addView(messageList, ScrollView.LayoutParams(-1, -2))
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        val composerArea = column().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Palette.BG, 0, Palette.BORDER)
        }
        composer = EditText(this).apply {
            hint = "Tulis pesan…"
            setHintTextColor(Palette.MUTED)
            setTextColor(Palette.WHITE)
            textSize = 14f
            minLines = 1
            maxLines = 4
            background = rounded(Palette.PANEL_2, 18, Palette.BORDER)
            setPadding(dp(15), dp(11), dp(15), dp(11))
        }
        composerArea.addView(composer, matchWrap().apply { bottomMargin = dp(8) })
        val actions = row().apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(smallButton("＋ Foto") { pickMedia("image/*", "image") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(smallButton("▣ Video") { pickMedia("video/*", "video") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        val micButton = smallButton(if (isRecording) "■ Stop" else "◉ Suara") { toggleRecording() }
        recordButton = micButton
        actions.addView(micButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(smallButton("⌖ Lokasi") { shareLocation() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(smallButton("Kirim  ↗", true) { sendText() }, LinearLayout.LayoutParams(0, dp(42), 1.15f))
        composerArea.addView(actions)
        root.addView(composerArea, matchWrap())
        setContentView(root)
        renderMessages()
        maybeAskNotificationPermission()
        schedulePoll()
    }

    private fun schedulePoll() {
        handler.removeCallbacksAndMessages(null)
        pollRunning = true
        val task = object : Runnable {
            override fun run() {
                if (!pollRunning || (mode != "host" && mode != "client")) return
                if (mode == "host") {
                    onMessagesUpdated(store?.all().orEmpty())
                    statusLine?.text = "Server aktif"
                } else {
                    Thread {
                        try {
                            val req = Request.Builder().url("$serverUrl/api/messages")
                                .header("x-pair-code", pairCode).header("x-device-id", deviceId).get().build()
                            http.newCall(req).execute().use { res ->
                                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                                val arr = JSONArray(res.body?.string().orEmpty())
                                val remoteMessages = mutableListOf<ChatMessage>()
                                for (i in 0 until arr.length()) {
                                    val msg = ChatMessage.fromJson(arr.getJSONObject(i))
                                    store?.upsert(msg)
                                    remoteMessages.add(msg)
                                }
                                runOnUiThread {
                                    onMessagesUpdated((store?.all().orEmpty() + remoteMessages).distinctBy { it.id }.sortedBy { it.id })
                                }
                            }
                        } catch (_: Exception) {
                            runOnUiThread { statusLine?.text = "Menunggu HP pertama / tunnel…" }
                        }
                    }.start()
                }
                handler.postDelayed(this, 3500L)
            }
        }
        handler.post(task)
    }

    private fun onMessagesUpdated(messages: List<ChatMessage>) {
        val newIncoming = messages.filter { it.senderId != deviceId && it.id > lastIncomingId }
        if (!firstPoll && newIncoming.isNotEmpty()) {
            val newest = newIncoming.maxByOrNull { it.id }!!
            notifyIncoming(if (newest.type == "text") newest.text else "Mengirim ${newest.type}")
        }
        if (newIncoming.isNotEmpty()) lastIncomingId = maxOf(lastIncomingId, newIncoming.maxOf { it.id })
        if (firstPoll) {
            lastIncomingId = messages.filter { it.senderId != deviceId }.maxOfOrNull { it.id } ?: 0L
            firstPoll = false
        }
        renderMessages(messages)
    }

    private fun renderMessages(supplied: List<ChatMessage>? = null) {
        val list = messageList ?: return
        val messages = supplied ?: store?.all().orEmpty()
        list.removeAllViews()
        if (messages.isEmpty()) {
            list.addView(text("Belum ada pesan.\nKirim sapaan pertama kalau sudah siap.", 13, Palette.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(dp(20), dp(54), dp(20), dp(54))
            }, matchWrap())
        } else {
            messages.sortedBy { it.id }.forEach { msg ->
                val mine = msg.senderId == deviceId
                val row = row().apply { gravity = if (mine) Gravity.END else Gravity.START; setPadding(0, dp(3), 0, dp(3)) }
                val bubble = column().apply {
                    background = rounded(if (mine) 0xFF242427.toInt() else Palette.PANEL, 17, Palette.BORDER)
                    setPadding(dp(12), dp(9), dp(12), dp(8))
                }
                val bubbleParams = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.76f).toInt(), -2)
                val label = if (mine) "KAMU" else "PERANGKAT KEDUA"
                bubble.addView(text(label, 9, Palette.MUTED, true).apply { letterSpacing = .07f })
                val payload = when (msg.type) {
                    "image" -> "▧  Foto · ketuk untuk menyimpan"
                    "video" -> "▣  Video · ketuk untuk menyimpan"
                    "audio" -> "◉  Pesan suara · ketuk untuk menyimpan"
                    "location" -> "⌖  Lokasi bersama\n${msg.text}"
                    else -> msg.text
                }
                bubble.addView(text(payload, 14, Palette.WHITE, false).apply {
                    setPadding(0, dp(5), 0, dp(5))
                    if (msg.type in listOf("image", "video", "audio") && msg.data.isNotBlank()) {
                        setOnClickListener { askSaveAttachment(msg) }
                    }
                })
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.time))
                bubble.addView(text(time, 9, Palette.MUTED).apply { gravity = Gravity.END })
                row.addView(bubble, bubbleParams)
                list.addView(row, matchWrap())
            }
        }
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendText() {
        val body = composer?.text?.toString()?.trim().orEmpty()
        if (body.isEmpty()) return
        sendMessage(ChatMessage(System.currentTimeMillis(), deviceId, "text", body))
        composer?.setText("")
        try { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(composer?.windowToken, 0) } catch (_: Exception) { }
    }

    private fun sendMessage(message: ChatMessage) {
        if (mode == "host") {
            store?.upsert(message)
            renderMessages()
            return
        }
        if (mode != "client") { toast("Belum tersambung"); return }
        Thread {
            try {
                val req = Request.Builder().url("$serverUrl/api/messages")
                    .header("x-pair-code", pairCode).header("x-device-id", deviceId)
                    .post(message.toJson().toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IOException(response.body?.string() ?: "HTTP ${response.code}")
                    store?.upsert(message)
                    runOnUiThread { renderMessages() }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Belum terkirim: ${e.message?.take(100) ?: "cek koneksi"}") }
            }
        }.start()
    }

    private fun pickMedia(mimeType: String, type: String) {
        pendingMediaType = type
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { this.type = mimeType; addCategory(Intent.CATEGORY_OPENABLE) }
        externalPickerPending = true
        startActivityForResult(Intent.createChooser(intent, if (type == "image") "Pilih foto" else "Pilih video"), REQ_PICK_MEDIA)
    }

    private fun sendPickedFile(uri: Uri) {
        Thread {
            try {
                val mime = contentResolver.getType(uri) ?: if (pendingMediaType == "video") "video/mp4" else "image/jpeg"
                val bytes = contentResolver.openInputStream(uri)?.use { input -> readLimited(input, MAX_ATTACHMENT_BYTES + 1) } ?: throw IOException("File tidak terbaca")
                if (bytes.size > MAX_ATTACHMENT_BYTES) throw IOException("Lampiran maksimal ${MAX_ATTACHMENT_BYTES / 1000} KB pada build awal")
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val msg = ChatMessage(System.currentTimeMillis(), deviceId, pendingMediaType, "", mime, b64)
                runOnUiThread { sendMessage(msg) }
            } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Tidak bisa membaca lampiran") } }
        }.start()
    }

    private fun toggleRecording() {
        if (isRecording) {
            try {
                recorder?.stop()
                recorder?.reset()
                recorder?.release()
                recorder = null
                isRecording = false
                updateRecordButton()
                val file = recordingFile
                if (file != null && file.exists()) {
                    val bytes = file.readBytes()
                    if (bytes.size > MAX_ATTACHMENT_BYTES) toast("Audio terlalu besar; coba pesan yang lebih singkat")
                    else sendMessage(ChatMessage(System.currentTimeMillis(), deviceId, "audio", "", "audio/mp4", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                    file.delete()
                }
            } catch (_: Exception) {
                stopRecorderSilently(); toast("Rekaman tidak dapat disimpan")
            }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        try {
            val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setMaxDuration(60_000)
            rec.setMaxFileSize(MAX_ATTACHMENT_BYTES.toLong())
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recordingFile = file
            recorder = rec
            isRecording = true
            updateRecordButton()
            toast("Merekam… tekan tombol suara lagi untuk selesai")
        } catch (e: Exception) { stopRecorderSilently(); toast("Mikrofon tidak bisa dimulai") }
    }

    private fun updateRecordButton() {
        recordButton?.text = if (isRecording) "■ Stop" else "◉ Suara"
    }

    private fun stopRecorderSilently() {
        try { if (isRecording) recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        isRecording = false
        recordingFile?.delete()
        recordingFile = null
    }

    private fun shareLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location: Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstNotNullOfOrNull { provider -> try { if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null } catch (_: SecurityException) { null } }
            if (location == null) {
                toast("Lokasi terakhir belum tersedia. Aktifkan lokasi lalu coba lagi.")
                return
            }
            val mapLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            sendMessage(ChatMessage(System.currentTimeMillis(), deviceId, "location", mapLink))
            toast("Lokasi dibagikan ke percakapan")
        } catch (_: Exception) { toast("Lokasi belum bisa dibaca") }
    }

    private fun askSaveAttachment(msg: ChatMessage) {
        pendingSave = msg
        val ext = when (msg.type) { "image" -> "jpg"; "video" -> "mp4"; else -> "m4a" }
        val mime = msg.mime.ifBlank { when (msg.type) { "image" -> "image/jpeg"; "video" -> "video/mp4"; else -> "audio/mp4" } }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
            putExtra(Intent.EXTRA_TITLE, "berdua-${msg.id}.$ext")
        }
        externalPickerPending = true
        startActivityForResult(intent, REQ_SAVE_MEDIA)
    }

    private fun saveAttachmentToUri(uri: Uri, msg: ChatMessage) {
        Thread {
            try {
                val bytes = android.util.Base64.decode(msg.data, android.util.Base64.DEFAULT)
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Tujuan penyimpanan tidak tersedia")
                runOnUiThread { toast("Lampiran disimpan ke lokasi yang dipilih") }
            } catch (e: Exception) { runOnUiThread { toast("Gagal menyimpan lampiran") } }
        }.start()
    }

    private fun notifyIncoming(body: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "berdua-chat") else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Pesan baru di berdua.").setContentText(body.take(100))
            .setAutoCancel(true).build()
        manager.notify(42, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("berdua-chat", "Pesan Berdua", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifikasi pesan saat aplikasi sedang berjalan"
            })
        }
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MIC -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecording() else toast("Izin mikrofon tidak diberikan")
            REQ_LOCATION -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) shareLocation() else toast("Izin lokasi tidak diberikan")
        }
    }

    @Deprecated("Uses the standard activity-result callback for this starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        externalPickerPending = false
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_PICK_MEDIA -> sendPickedFile(data.data!!)
            REQ_SAVE_MEDIA -> pendingSave?.let { saveAttachmentToUri(data.data!!, it) }
        }
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString { repeat(12) { append(chars[random.nextInt(chars.length)]) } }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Lampiran terlalu besar; batas build awal ${MAX_ATTACHMENT_BYTES / 1000} KB")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = true
    }

    private fun rounded(color: Int, radius: Int, border: Int = color): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border != color) setStroke(dp(1), border)
    }

    private fun actionButton(title: String, subtitle: String, click: () -> Unit): LinearLayout {
        return column().apply {
            background = rounded(Palette.PANEL, 16, Palette.BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(title, 13, Palette.WHITE, true).apply { letterSpacing = .02f })
            addView(text(subtitle, 11, Palette.MUTED).apply { setPadding(0, dp(5), 0, 0) })
            isClickable = true; isFocusable = true; setOnClickListener { click() }
        }
    }

    private fun actionText(value: String, click: () -> Unit): TextView = text(value, 10, Palette.MUTED, true).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { click() }
    }

    private fun field(hintText: String): EditText = EditText(this).apply {
        hint = hintText; setHintTextColor(Palette.MUTED); setTextColor(Palette.WHITE); textSize = 14f
        background = rounded(Palette.PANEL, 14, Palette.BORDER); setPadding(dp(14), dp(12), dp(14), dp(12))
        singleLine = true
    }

    private fun smallButton(label: String, accent: Boolean = false, click: () -> Unit): TextView = text(label, 10, if (accent) Palette.BG else Palette.WHITE, true).apply {
        gravity = Gravity.CENTER; background = rounded(if (accent) Palette.ACCENT else Palette.PANEL_2, 13, Palette.BORDER)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
}
