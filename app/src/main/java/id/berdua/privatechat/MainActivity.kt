package id.NEXORAprivatechat

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.MediaRecorder
import android.media.MediaPlayer
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
import android.widget.ImageView
import android.widget.MediaController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
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
private const val MAX_ATTACHMENT_BYTES = 150L * 1024L * 1024L // 150 MiB per file
private const val TUNNEL_DISCOVERY_URL = "https://raw.githubusercontent.com/imranperatama/berdua-android-terbaru/main/tunnel.json"

private fun copyWithLimit(
    input: java.io.InputStream,
    destination: File
): Long {
    destination.parentFile?.let {
        if (!it.exists() && !it.mkdirs()) {
            throw IOException("Penyimpanan lampiran tidak tersedia")
        }
    }

    var total = 0L
    val buffer = ByteArray(64 * 1024)

    try {
        destination.outputStream().buffered().use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break

                total += count.toLong()
                if (total > MAX_ATTACHMENT_BYTES) {
                    throw IOException("Lampiran maksimal 150 MiB per file")
                }
                output.write(buffer, 0, count)
            }
        }
        return total
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}

private fun copyExactlyWithLimit(
    input: java.io.InputStream,
    destination: File,
    expectedLength: Long
): Long {
    if (expectedLength < 0L || expectedLength > MAX_ATTACHMENT_BYTES) {
        throw IOException("Lampiran maksimal 150 MiB per file")
    }

    destination.parentFile?.let {
        if (!it.exists() && !it.mkdirs()) {
            throw IOException("Penyimpanan lampiran tidak tersedia")
        }
    }

    var remaining = expectedLength
    var total = 0L
    val buffer = ByteArray(64 * 1024)

    try {
        destination.outputStream().buffered().use { output ->
            while (remaining > 0L) {
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                val count = input.read(buffer, 0, wanted)
                if (count < 0) {
                    throw IOException("Unggahan terputus sebelum selesai")
                }
                if (count == 0) continue
                output.write(buffer, 0, count)
                total += count.toLong()
                remaining -= count.toLong()
            }
        }
        return total
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}

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
            // Keep message history until the device's available app storage is exhausted.
            // Large media is stored as separate files; only attachment IDs live in this JSON.
            val array = JSONArray()
            messages.forEach { array.put(it.toJson()) }
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

    private val prefs = context.getSharedPreferences(
        "berdua_pairing", Context.MODE_PRIVATE
    )

    private fun attachmentDirectory(): File {
        return File(context.filesDir, "attachments").apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Tidak bisa membuat folder lampiran")
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val method = session.method

        // Local tunnel helper uses this unauthenticated, data-free status endpoint only
        // to tell whether the phone's local server is alive. No messages are returned.
        if (path == "/api/discovery-health" && method == Method.GET) {
            return json(Response.Status.OK, """{"active":true}""")
        }

        val suppliedCode = session.headers["x-pair-code"].orEmpty()
        if (suppliedCode != pairCode) {
            return json(
                Response.Status.UNAUTHORIZED,
                """{"error":"Kode akses salah"}"""
            )
        }

        if (path == "/api/pair" && method == Method.POST) {
            val body = readBody(session)
            val remoteId = try {
                JSONObject(body).optString("deviceId")
            } catch (_: Exception) {
                ""
            }

            if (remoteId.length < 12) {
                return json(
                    Response.Status.BAD_REQUEST,
                    """{"error":"deviceId tidak valid"}"""
                )
            }

            synchronized(prefs) {
                val existing = prefs.getString(
                    "paired_device_id", ""
                ).orEmpty()
                if (existing.isNotEmpty() && existing != remoteId) {
                    return json(
                        Response.Status.FORBIDDEN,
                        """{"error":"Sudah ada satu perangkat kedua yang dipasangkan"}"""
                    )
                }
                prefs.edit().putString("paired_device_id", remoteId).apply()
            }

            return json(
                Response.Status.OK,
                """{"ok":true,"message":"Perangkat dipasangkan"}"""
            )
        }

        val remoteId = session.headers["x-device-id"].orEmpty()
        val pairedId = prefs.getString(
            "paired_device_id", ""
        ).orEmpty()
        if (pairedId.isEmpty() || remoteId != pairedId) {
            return json(
                Response.Status.FORBIDDEN,
                """{"error":"Perangkat belum dipasangkan"}"""
            )
        }

        return when {
            path.startsWith("/api/attachments/") && method == Method.PUT ->
                receiveAttachment(session, path.substringAfterLast('/'))

            path.startsWith("/api/attachments/") && method == Method.GET ->
                serveAttachment(path.substringAfterLast('/'))

            path == "/api/health" && method == Method.GET ->
                json(Response.Status.OK, """{"active":true}""")

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
                        return json(
                            Response.Status.BAD_REQUEST,
                            """{"error":"Pesan tidak valid"}"""
                        )
                    }
                    if (message.data.length > 1_300_000) {
                        return json(
                            Response.Status.PAYLOAD_TOO_LARGE,
                            """{"error":"Data pesan terlalu besar"}"""
                        )
                    }
                    store.upsert(message)
                    json(Response.Status.OK, """{"ok":true}""")
                } catch (_: Exception) {
                    json(
                        Response.Status.BAD_REQUEST,
                        """{"error":"JSON tidak valid"}"""
                    )
                }
            }

            else -> json(
                Response.Status.NOT_FOUND,
                """{"error":"Tidak ditemukan"}"""
            )
        }
    }

    private fun receiveAttachment(session: IHTTPSession, id: String): Response {
        if (!id.matches(Regex("[a-f0-9]{32}"))) {
            return json(
                Response.Status.BAD_REQUEST,
                """{"error":"ID lampiran tidak valid"}"""
            )
        }

        val declaredLength = session.headers["content-length"]?.toLongOrNull()
        if (declaredLength == null || declaredLength < 0L) {
            return json(
                Response.Status.LENGTH_REQUIRED,
                """{"error":"Content-Length diperlukan untuk unggahan lampiran"}"""
            )
        }
        if (declaredLength > MAX_ATTACHMENT_BYTES) {
            return json(
                Response.Status.PAYLOAD_TOO_LARGE,
                """{"error":"Lampiran maksimal 150 MiB per file"}"""
            )
        }

        val directory = try {
            attachmentDirectory()
        } catch (e: Exception) {
            return json(
                Response.Status.INTERNAL_ERROR,
                """{"error":"Penyimpanan lampiran tidak tersedia"}"""
            )
        }

        val target = File(directory, "$id.bin")
        val staging = File(directory, "$id.uploading")
        return try {
            // Baca tepat sebanyak Content-Length; jangan menunggu EOF pada koneksi keep-alive.
            copyExactlyWithLimit(session.inputStream, staging, declaredLength)

            if (target.exists() && !target.delete()) {
                throw IOException("Tidak bisa mengganti lampiran lama")
            }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }

            json(Response.Status.OK, """{"ok":true}""")
        } catch (e: Exception) {
            staging.delete()
            val tooLarge = (e.message ?: "").contains("150 MiB")
            json(
                if (tooLarge) Response.Status.PAYLOAD_TOO_LARGE
                else Response.Status.BAD_REQUEST,
                JSONObject().put(
                    "error", e.message ?: "Upload lampiran gagal"
                ).toString()
            )
        }
    }

    private fun serveAttachment(id: String): Response {
        if (!id.matches(Regex("[a-f0-9]{32}"))) {
            return json(
                Response.Status.BAD_REQUEST,
                """{"error":"ID lampiran tidak valid"}"""
            )
        }

        val file = File(attachmentDirectory(), "$id.bin")
        if (!file.isFile) {
            return json(
                Response.Status.NOT_FOUND,
                """{"error":"Lampiran tidak ditemukan"}"""
            )
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            file.inputStream().buffered(),
            file.length()
        ).apply { addHeader("Cache-Control", "no-store") }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(
            status, "application/json; charset=utf-8", body
        ).apply { addHeader("Cache-Control", "no-store") }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()
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
    private var audioPlayer: MediaPlayer? = null
    private var activeAudioMessageId: Long? = null
    private var audioPrepared = false
    private val audioButtons = mutableMapOf<Long, TextView>()

    private fun audioButtonLabel(messageId: Long): String {
        if (activeAudioMessageId != messageId) return "▶ Putar suara"
        if (!audioPrepared) return "… Memuat suara"
        return try {
            if (audioPlayer?.isPlaying == true) "Ⅱ Jeda" else "▶ Lanjutkan"
        } catch (_: Exception) {
            "▶ Lanjutkan"
        }
    }

    private fun refreshAudioButtons() {
        audioButtons.forEach { (id, button) -> button.text = audioButtonLabel(id) }
    }

    private fun releaseAudioPlayer() {
        try { audioPlayer?.release() } catch (_: Exception) { }
        audioPlayer = null
        audioPrepared = false
        activeAudioMessageId = null
    }

    private fun toggleAudioPlayback(msg: ChatMessage) {
        if (activeAudioMessageId == msg.id && audioPlayer != null) {
            if (!audioPrepared) {
                toast("Sedang menyiapkan audio…")
                return
            }
            try {
                val player = audioPlayer ?: return
                if (player.isPlaying) player.pause() else player.start()
                refreshAudioButtons()
            } catch (_: Exception) {
                releaseAudioPlayer()
                refreshAudioButtons()
                toast("Audio tidak dapat diputar")
            }
            return
        }

        releaseAudioPlayer()
        activeAudioMessageId = msg.id
        refreshAudioButtons()
        withLocalAttachment(msg) { file ->
            if (activeAudioMessageId != msg.id) return@withLocalAttachment
            try {
                val player = MediaPlayer()
                audioPlayer = player
                audioPrepared = false
                player.setOnPreparedListener { prepared ->
                    if (activeAudioMessageId == msg.id) {
                        audioPrepared = true
                        try { prepared.start() } catch (_: Exception) { toast("Audio gagal diputar") }
                        refreshAudioButtons()
                    } else {
                        try { prepared.release() } catch (_: Exception) { }
                    }
                }
                player.setOnCompletionListener {
                    if (activeAudioMessageId == msg.id) {
                        releaseAudioPlayer()
                        refreshAudioButtons()
                    }
                }
                player.setOnErrorListener { _, _, _ ->
                    if (activeAudioMessageId == msg.id) {
                        releaseAudioPlayer()
                        refreshAudioButtons()
                        toast("Format audio tidak didukung atau file rusak")
                    }
                    true
                }
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            } catch (_: Exception) {
                releaseAudioPlayer()
                refreshAudioButtons()
                toast("Audio tidak dapat dibuka")
            }
        }
    }

    /** Download or decode one attachment to private app storage, without loading the whole file into RAM. */
    private fun withLocalAttachment(msg: ChatMessage, onReady: (File) -> Unit) {
        Thread {
            try {
                val dir = attachmentDirectory()
                val isRemoteId = msg.data.matches(Regex("[a-f0-9]{32}"))
                val file = File(dir, if (isRemoteId) "${msg.data}.bin" else "legacy-${msg.id}.bin")
                if (!file.isFile || file.length() == 0L) {
                    if (!isRemoteId) {
                        val decoded = try {
                            android.util.Base64.decode(msg.data, android.util.Base64.DEFAULT)
                        } catch (_: Exception) {
                            throw IOException("Data lampiran lama tidak valid")
                        }
                        if (decoded.size.toLong() > MAX_ATTACHMENT_BYTES) {
                            throw IOException("Lampiran maksimal 150 MiB per file")
                        }
                        file.writeBytes(decoded)
                    } else {
                        if (mode != "client") {
                            throw IOException("File lampiran belum tersedia di HP ini")
                        }
                        val request = Request.Builder()
                            .url("$serverUrl/api/attachments/${msg.data}")
                            .header("x-pair-code", pairCode)
                            .header("x-device-id", deviceId)
                            .get()
                            .build()
                        http.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Unduh lampiran gagal (HTTP ${response.code})")
                            val body = response.body ?: throw IOException("Isi lampiran kosong")
                            body.byteStream().use { copyWithLimit(it, file) }
                        }
                    }
                }
                if (!file.isFile) throw IOException("Lampiran tidak ditemukan")
                runOnUiThread { onReady(file) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (msg.type == "audio" && activeAudioMessageId == msg.id) {
                        releaseAudioPlayer()
                        refreshAudioButtons()
                    }
                    toast("Lampiran tidak bisa dibuka: ${e.message?.take(100) ?: "coba lagi"}")
                }
            }
        }.start()
    }

    private fun playVideoInChat(msg: ChatMessage) {
        withLocalAttachment(msg) { file ->
            try {
                val dialog = Dialog(this)
                val video = VideoView(this)
                val controller = MediaController(this)
                controller.setAnchorView(video)
                video.setMediaController(controller)
                video.setOnPreparedListener { player ->
                    player.isLooping = false
                    video.start()
                }
                dialog.setContentView(video)
                dialog.setOnDismissListener { try { video.stopPlayback() } catch (_: Exception) { } }
                dialog.show()
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                video.setVideoURI(Uri.fromFile(file))
            } catch (_: Exception) {
                toast("Video tidak dapat diputar di perangkat ini")
            }
        }
    }

    private fun showImageInChat(msg: ChatMessage) {
        withLocalAttachment(msg) { file ->
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val maxWidth = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceAtLeast(800)
                val maxHeight = (resources.displayMetrics.heightPixels * 1.5f).toInt().coerceAtLeast(1200)
                var sample = 1
                while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                    ?: throw IOException("Foto tidak bisa dibuka")
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0xFF050505.toInt())
                    adjustViewBounds = true
                }
                val dialog = Dialog(this)
                dialog.setContentView(image)
                dialog.setOnDismissListener { if (!bitmap.isRecycled) bitmap.recycle() }
                dialog.show()
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } catch (_: Exception) {
                toast("Foto tidak dapat dibuka")
            }
        }
    }

    private fun openSharedLocation(link: String) {
        try {
            val cleanLink = link.trim()
            val mapUri = if (cleanLink.startsWith("https://") || cleanLink.startsWith("http://")) {
                Uri.parse(cleanLink)
            } else {
                Uri.parse("geo:0,0?q=${Uri.encode(cleanLink)}")
            }
            startActivity(Intent(Intent.ACTION_VIEW, mapUri))
        } catch (_: Exception) {
            toast("Tidak ada aplikasi peta yang bisa membuka lokasi ini")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        window.decorView.systemUiVisibility = 0
        createNotificationChannel()
        when (prefs.getString("last_role", "home")) {
            "host" -> startHost()
            "client" -> {
                val savedUrl = prefs.getString("server_url", "").orEmpty()
                val savedCode = prefs.getString("pair_code_client", "").orEmpty()
                if (savedCode.length >= 10) {
                    connectClientWith(savedUrl, savedCode, automatic = true)
                } else showHome()
            }
            else -> showHome()
        }
    }

    override fun onStart() {
        super.onStart()
        if (mode == "host" && server == null && store != null) restartHostServer()
        if (mode == "host" || mode == "client") schedulePoll()
    }

    override fun onStop() {
        pollRunning = false
        handler.removeCallbacksAndMessages(null)
        // Keep the local host server alive while the UI is backgrounded. Android may still
        // stop the process under system pressure; on the next launch the saved host role resumes.
        super.onStop()
    }

    override fun onDestroy() {
        pollRunning = false
        handler.removeCallbacksAndMessages(null)
        stopServer()
        stopRecorderSilently()
        releaseAudioPlayer()
        super.onDestroy()
    }

    private fun restartHostServer() {
        try {
            server = LocalChatServer(this, pairCode, store ?: MessageStore(this, "host-messages.json").also { store = it })
            server!!.start(15 * 60 * 1000, false)
        } catch (_: Exception) {
            stopServer()
            toast("Server lokal tidak bisa aktif kembali")
        }
    }

    private fun showHome() {
        mode = "home"
        prefs.edit().putString("last_role", "home").apply()
        stopServer()
        handler.removeCallbacksAndMessages(null)
        val root = column()
        root.setPadding(dp(24), dp(38), dp(24), dp(24))
        root.addView(text("NEXORA", 34, Palette.WHITE, true).apply { letterSpacing = -0.04f })
        root.addView(text("RUANG KECIL UNTUK DUA PERANGKAT", 10, Palette.MUTED, true).apply {
            letterSpacing = 0.12f; setPadding(0, dp(3), 0, dp(26))
        })

        val card = column().apply {
            background = rounded(Palette.PANEL, 20, Palette.BORDER)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(text("Pribadi, bukan tersembunyi dari izin", 17, Palette.WHITE, true))
        card.addView(text(
            "Riwayat chat disimpan sampai ruang penyimpanan perangkat habis. Foto, video, dan audio dapat dikirim hingga 150 MiB per file. Lokasi dan mikrofon hanya diminta saat tombol fiturnya dipakai.",
            13, Palette.MUTED, false
        ).apply { setPadding(0, dp(9), 0, dp(12)) })
        card.addView(text("Tidak ada akun atau username. Kode akses membatasi akses ke satu perangkat kedua.", 12, Palette.ACCENT, false))
        root.addView(card, matchWrap().apply { bottomMargin = dp(18) })
        root.addView(actionButton("JADIKAN HP PERTAMA  ·  SERVER", "Mulai server di HP ini") { startHost() }, matchWrap().apply { bottomMargin = dp(12) })
        root.addView(actionButton("SAMBUNGKAN HP KEDUA", "URL tunnel bisa diperbarui otomatis · masukkan kode akses") { showJoinSetup() }, matchWrap().apply { bottomMargin = dp(22) })

        val note = column().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        note.addView(text("CATATAN KONEKSI", 10, Palette.MUTED, true).apply { letterSpacing = .1f })
        note.addView(text(
            "Server lokal berjalan di HP pertama. Untuk koneksi internet, paket pendamping dapat memperbarui alamat ngrok HTTPS tunnel melalui tunnel.json di repo GitHub. GitHub hanya menyimpan alamat tunnel, bukan pesan atau lampiran.",
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
        root.addView(text("Alamat tunnel diambil otomatis bila tunnel.json sudah diperbarui. URL boleh dikosongkan. Kode akses tetap diperlukan.", 13, Palette.MUTED).apply {
            setPadding(0, dp(6), 0, dp(22))
        })
        root.addView(field("URL tunnel (opsional; otomatis)").also {
            urlInput = it
        }, matchWrap().apply { bottomMargin = dp(12) })
        root.addView(field("Kode akses").also {
            codeInput = it
            it.setText(prefs.getString("pair_code_client", "").orEmpty())
        }, matchWrap().apply { bottomMargin = dp(16) })
        root.addView(actionButton("HUBUNGKAN", "Pasangkan hanya satu perangkat kedua") { connectClient() }, matchWrap())
        root.addView(text(
            "Kode akses bukan akun. Jangan bagikan kode ke orang lain. URL tunnel otomatis dibaca dari tunnel.json bila helper aktif.",
            12, Palette.MUTED
        ).apply { setPadding(dp(2), dp(18), dp(2), 0) })
        setContentView(root)
    }

    private fun startHost() {
        stopServer()
        prefs.edit().putString("last_role", "host").apply()
        pairCode = prefs.getString("pair_code", null) ?: generateCode().also {
            prefs.edit().putString("pair_code", it).apply()
        }
        store = MessageStore(this, "host-messages.json")
        server = LocalChatServer(this, pairCode, store!!)
        try {
            server!!.start(15 * 60 * 1000, false)
            mode = "host"
            showChat()
        } catch (e: IOException) {
            stopServer()
            prefs.edit().putString("last_role", "host").apply()
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
        connectClientWith(url, code, automatic = false)
    }

    private fun connectClientWith(inputUrl: String, code: String, automatic: Boolean) {
        val url = inputUrl.trim().trimEnd('/')
        if (url.isNotBlank() && (!url.startsWith("https://") || Uri.parse(url).host.isNullOrBlank())) {
            if (!automatic) toast("URL harus berupa alamat HTTPS yang valid")
            return
        }
        if (code.length < 10) {
            if (!automatic) toast("Kode akses belum lengkap")
            else showHome()
            return
        }
        pairCode = code
        Thread {
            try {
                val selectedUrl = if (automatic) {
                    discoverTunnelUrl()
                        ?: url.takeIf { it.startsWith("https://") }
                        ?: prefs.getString("server_url", "").orEmpty().takeIf { it.startsWith("https://") }
                } else {
                    url.takeIf { it.startsWith("https://") }
                        ?: discoverTunnelUrl()
                        ?: prefs.getString("server_url", "").orEmpty().takeIf { it.startsWith("https://") }
                } ?: throw IOException("Alamat tunnel belum tersedia. Hidupkan tunnel di HP pertama lalu coba lagi.")
                serverUrl = selectedUrl.trimEnd('/')
                val json = JSONObject().put("deviceId", deviceId).put("label", "Perangkat kedua")
                val request = Request.Builder().url("$serverUrl/api/pair")
                    .header("x-pair-code", pairCode).header("x-device-id", deviceId)
                    .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException(response.body?.string() ?: "Pasangan ditolak (${response.code})")
                    runOnUiThread {
                        mode = "client"
                        store = MessageStore(this, "client-messages.json")
                        prefs.edit().putString("server_url", serverUrl)
                            .putString("pair_code_client", pairCode)
                            .putString("last_role", "client").apply()
                        showChat()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (automatic) {
                        showHome()
                        // Keep the client's intended role so opening the app again retries discovery.
                        prefs.edit().putString("last_role", "client").apply()
                        toast("Tunnel belum siap. NEXORA akan mencoba menemukan alamatnya lagi.")
                        handler.postDelayed({
                            if (mode == "home" && prefs.getString("last_role", "") == "client") {
                                connectClientWith("", code, automatic = true)
                            }
                        }, 10_000L)
                    } else {
                        toast("Gagal menyambung: ${e.message?.take(120) ?: "periksa koneksi"}")
                    }
                }
            }
        }.start()
    }

    private fun discoverTunnelUrl(): String? {
        return try {
            val request = Request.Builder()
                .url("$TUNNEL_DISCOVERY_URL?cache=${System.currentTimeMillis()}")
                .header("Cache-Control", "no-cache")
                .get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val config = JSONObject(response.body?.string().orEmpty())
                val candidate = config.optString("url", "").trim().trimEnd('/')
                val online = config.optBoolean("online", candidate.isNotBlank())
                if (online && candidate.startsWith("https://") && !Uri.parse(candidate).host.isNullOrBlank()) candidate else null
            }
        } catch (_: Exception) { null }
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
        titleBox.addView(text("NEXORA", 22, Palette.WHITE, true))
        titleBox.addView(text(if (mode == "host") "HP pertama · server aktif" else "HP kedua · sesi chat", 11, Palette.MUTED))
        header.addView(titleBox)
        header.addView(actionText("KELUAR") { showHome() })
        root.addView(header, matchWrap())

        if (mode == "host") {
            val codeCard = column().apply {
                background = rounded(Palette.PANEL, 16, Palette.BORDER)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            codeCard.addView(text("KODE AKSES · BAGIKAN HANYA KE SATU ORANG", 9, Palette.MUTED, true))
            codeCard.addView(text(pairCode.chunked(4).joinToString(" "), 21, Palette.WHITE, true).apply {
                setPadding(0, dp(5), 0, dp(2)); letterSpacing = .12f
            })
            codeCard.addView(text("Server lokal: port $PORT · kode akses diperlukan", 11, Palette.MUTED))
            root.addView(codeCard, matchWrap().apply { setMargins(dp(14), dp(10), dp(14), dp(6)) })
            root.addView(text("Alamat publik dapat diperbarui otomatis jika helper tunnel aktif.", 11, Palette.MUTED).apply {
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
        scrollView!!.addView(messageList, ViewGroup.LayoutParams(-1, -2))
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
        actions.addView(smallButton("＋ Foto") { pickMedia("image/*", "image") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        actions.addView(smallButton("▣ Video") { pickMedia("video/*", "video") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        actions.addView(smallButton("♫ Audio") { pickMedia("audio/*", "audio") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        composerArea.addView(actions, matchWrap().apply { bottomMargin = dp(6) })

        val extraActions = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val micButton = smallButton(if (isRecording) "■ Stop" else "◉ Rekam") { toggleRecording() }
        recordButton = micButton
        extraActions.addView(micButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        extraActions.addView(smallButton("⌖ Lokasi") { shareLocation() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        extraActions.addView(smallButton("Kirim ↗", true) { sendText() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        composerArea.addView(extraActions)
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
                            val refreshedUrl = discoverTunnelUrl()
                            val changed = !refreshedUrl.isNullOrBlank() && refreshedUrl != serverUrl
                            if (changed) {
                                serverUrl = refreshedUrl!!
                                prefs.edit().putString("server_url", serverUrl).apply()
                            }
                            runOnUiThread {
                                statusLine?.text = if (changed) "Alamat tunnel diperbarui · menyambungkan ulang…"
                                    else "Menunggu server / tunnel…"
                            }
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
        audioButtons.clear()
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
                    "image" -> "▧  Foto"
                    "video" -> "▣  Video"
                    "audio" -> if (msg.mime.startsWith("audio/voice")) "◉  Pesan suara" else "♫  Audio"
                    "location" -> "⌖  Lokasi bersama\nKetuk untuk membuka peta"
                    else -> msg.text
                }
                val bodyText = text(payload, 14, if (msg.type == "location") Palette.ACCENT else Palette.WHITE, false).apply {
                    setPadding(0, dp(5), 0, dp(5))
                    if (msg.type == "location") {
                        isClickable = true
                        setOnClickListener { openSharedLocation(msg.text) }
                    }
                }
                bubble.addView(bodyText)

                when (msg.type) {
                    "image" -> {
                        if (msg.data.isNotBlank()) {
                            val controls = row().apply { gravity = Gravity.CENTER_VERTICAL }
                            controls.addView(smallButton("▧ Lihat foto") { showImageInChat(msg) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
                            controls.addView(smallButton("↓ Unduh") { askSaveAttachment(msg) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                            bubble.addView(controls)
                        }
                    }
                    "video" -> {
                        if (msg.data.isNotBlank()) {
                            val controls = row().apply { gravity = Gravity.CENTER_VERTICAL }
                            controls.addView(smallButton("▶ Putar") { playVideoInChat(msg) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
                            controls.addView(smallButton("↓ Unduh") { askSaveAttachment(msg) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                            bubble.addView(controls)
                        }
                    }
                    "audio" -> {
                        if (msg.data.isNotBlank()) {
                            val controls = row().apply { gravity = Gravity.CENTER_VERTICAL }
                            val playButton = smallButton(audioButtonLabel(msg.id)) { toggleAudioPlayback(msg) }
                            audioButtons[msg.id] = playButton
                            controls.addView(playButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
                            controls.addView(smallButton("↓ Unduh") { askSaveAttachment(msg) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                            bubble.addView(controls)
                        }
                    }
                }
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.time))
                bubble.addView(text(time, 9, Palette.MUTED).apply { gravity = Gravity.END; setPadding(0, dp(5), 0, 0) })
                row.addView(bubble, bubbleParams)
                list.addView(row, matchWrap())
            }
        }
        refreshAudioButtons()
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
        startActivityForResult(Intent.createChooser(intent, when (type) { "image" -> "Pilih foto"; "video" -> "Pilih video"; else -> "Pilih audio" }), REQ_PICK_MEDIA)
    }

    private fun sendPickedFile(uri: Uri) {
        val attachmentType = pendingMediaType
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        Thread {
            val destination = try {
                File(attachmentDirectory(), "$id.bin")
            } catch (_: Exception) {
                runOnUiThread { toast("Penyimpanan lampiran tidak tersedia") }
                return@Thread
            }

            try {
                val mime = contentResolver.getType(uri) ?: when (attachmentType) {
                    "video" -> "video/mp4"
                    "audio" -> "audio/mp4"
                    else -> "image/jpeg"
                }
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("File tidak terbaca")
                input.use { copyWithLimit(it, destination) }
                transferAttachment(destination, id, attachmentType, mime)
            } catch (e: Exception) {
                destination.delete()
                runOnUiThread {
                    toast(e.message ?: "Tidak bisa membaca lampiran")
                }
            }
        }.start()
    }

    private fun sendLocalAttachment(
        source: File,
        type: String,
        mime: String,
        deleteSource: Boolean = false
    ) {
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        Thread {
            val destination = try {
                File(attachmentDirectory(), "$id.bin")
            } catch (_: Exception) {
                runOnUiThread { toast("Penyimpanan lampiran tidak tersedia") }
                return@Thread
            }

            try {
                source.inputStream().buffered().use {
                    copyWithLimit(it, destination)
                }
                if (deleteSource) source.delete()
                transferAttachment(destination, id, type, mime)
            } catch (e: Exception) {
                destination.delete()
                runOnUiThread {
                    toast(e.message ?: "Tidak bisa menyiapkan lampiran")
                }
            }
        }.start()
    }

    private fun transferAttachment(
        file: File,
        id: String,
        type: String,
        mime: String
    ) {
        try {
            if (mode != "host" && mode != "client") {
                throw IOException("Sambungkan chat dulu")
            }

            if (mode == "client") {
                val request = Request.Builder()
                    .url("$serverUrl/api/attachments/$id")
                    .header("x-pair-code", pairCode)
                    .header("x-device-id", deviceId)
                    .put(file.asRequestBody(mime.toMediaType()))
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            response.body?.string()
                                ?: "Upload gagal (HTTP ${response.code})"
                        )
                    }
                }
            }

            val message = ChatMessage(
                System.currentTimeMillis(), deviceId, type, "", mime, id
            )
            runOnUiThread { sendMessage(message) }
        } catch (e: Exception) {
            runOnUiThread {
                toast(
                    "Lampiran gagal dikirim: ${e.message?.take(120) ?: "periksa koneksi"}"
                )
            }
        }
    }

    private fun attachmentDirectory(): File {
        return File(filesDir, "attachments").apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Tidak bisa membuat folder lampiran")
            }
        }
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
                recordingFile = null
                if (file != null && file.exists()) {
                    if (file.length() > MAX_ATTACHMENT_BYTES) {
                        file.delete()
                        toast("Audio melebihi batas 150 MiB")
                    } else {
                        sendLocalAttachment(
                            file, "audio", "audio/mp4", deleteSource = true
                        )
                    }
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
            rec.setMaxFileSize(MAX_ATTACHMENT_BYTES)
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
        val mime = msg.mime.ifBlank { when (msg.type) { "image" -> "image/jpeg"; "video" -> "video/mp4"; else -> "audio/mp4" } }
        val ext = when {
            msg.type == "image" && mime.contains("png", true) -> "png"
            msg.type == "image" && mime.contains("webp", true) -> "webp"
            msg.type == "image" -> "jpg"
            msg.type == "video" && mime.contains("webm", true) -> "webm"
            msg.type == "video" -> "mp4"
            mime.contains("mpeg", true) || mime.contains("mp3", true) -> "mp3"
            mime.contains("wav", true) -> "wav"
            mime.contains("ogg", true) -> "ogg"
            else -> "m4a"
        }
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
                val output = contentResolver.openOutputStream(uri)
                    ?: throw IOException("Tujuan penyimpanan tidak tersedia")

                output.use { destination ->
                    if (msg.data.matches(Regex("[a-f0-9]{32}"))) {
                        val localFile = File(
                            attachmentDirectory(), "${msg.data}.bin"
                        )

                        if (localFile.isFile) {
                            if (localFile.length() > MAX_ATTACHMENT_BYTES) throw IOException("Lampiran maksimal 150 MiB per file")
                            localFile.inputStream().buffered().use { it.copyTo(destination) }
                        } else {
                            if (mode != "client") {
                                throw IOException(
                                    "File lampiran tidak ditemukan di HP ini"
                                )
                            }

                            val request = Request.Builder()
                                .url("$serverUrl/api/attachments/${msg.data}")
                                .header("x-pair-code", pairCode)
                                .header("x-device-id", deviceId)
                                .get()
                                .build()

                            http.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    throw IOException(
                                        "Unduh gagal (HTTP ${response.code})"
                                    )
                                }
                                val body = response.body
                                    ?: throw IOException("Isi lampiran kosong")
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count.toLong()
                                        if (total > MAX_ATTACHMENT_BYTES) throw IOException("Lampiran maksimal 150 MiB per file")
                                        destination.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                    } else {
                        // Kompatibilitas untuk pesan Base64 dari versi sebelumnya.
                        destination.write(
                            android.util.Base64.decode(
                                msg.data, android.util.Base64.DEFAULT
                            )
                        )
                    }
                }

                runOnUiThread {
                    toast("Lampiran disimpan ke lokasi yang dipilih")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(
                        "Gagal menyimpan lampiran: ${e.message?.take(100) ?: "coba lagi"}"
                    )
                }
            }
        }.start()
    }

    private fun notifyIncoming(body: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "berdua-chat") else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Pesan baru di NEXORA").setContentText(body.take(100))
            .setAutoCancel(true).build()
        manager.notify(42, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("berdua-chat", "Pesan NEXORA", NotificationManager.IMPORTANCE_DEFAULT).apply {
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
        setSingleLine(true)
    }

    private fun smallButton(label: String, accent: Boolean = false, click: () -> Unit): TextView = text(label, 10, if (accent) Palette.BG else Palette.WHITE, true).apply {
        gravity = Gravity.CENTER; background = rounded(if (accent) Palette.ACCENT else Palette.PANEL_2, 13, Palette.BORDER)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
}
