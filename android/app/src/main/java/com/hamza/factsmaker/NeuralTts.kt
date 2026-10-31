package com.hamza.factsmaker

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MEER v4.70 — Voicebox Kokoro neural voice, APP KE ANDAR.
 *
 * Hamza ka hukm (2026-10-31): "Khud joro app ke sath pc ke sath nai" —
 * is liye Voicebox ki tez engine (Kokoro, sherpa-onnx AAR ke zariye)
 * ab MEER app ke andar hai. Ye maujooda HamzaTTS (device TTS) aur
 * ElevenLabs ke UPAR aik nayi layer hai — un dono ko haath nahi lagaya.
 *
 * JS contract (window.HamzaNeuralTTS):
 *  - isModelReady(): Boolean
 *  - getModelSizeMB(): String
 *  - downloadModel()  -> onNeuralDownloadProgress(pct) /
 *                        onNeuralDownloadDone() / onNeuralDownloadFailed(reason)
 *  - synthesize(id, text, lang, sid, speed)
 *                     -> onNeuralTTSDone(id, dataUri) / onNeuralTTSFailed(id, reason)
 * Bilkul wahi callback shakal jo HamzaTTS ki hai (onTTSDone/onTTSFailed).
 *
 * Model pack: kokoro-int8-multi-lang-v1_0 (v1_1 me Hindi voices nahi
 * hain; v1_0 me English 28 + Hindi hf_alpha=31 / hm_omega=33 hain).
 * Download Range-resume ke saath, SHA-256 verify lazmi, phir extract.
 * Saari native calls aik hi background thread par (mutex ki jagah) —
 * engine thread-safe nahi hota. Har Throwable (UnsatisfiedLinkError
 * samet) catch hota hai: JS ko saaf failure, crash kabhi nahi.
 */
class NeuralTtsBridge(private val ctx: Context, private val web: WebView) {

    companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
        const val MODEL_BYTES = 132353410L
        const val MODEL_SHA256 =
            "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"
        const val PACK_DIR = "kokoro-int8-multi-lang-v1_0"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val downloading = AtomicBoolean(false)

    private var engine: OfflineTts? = null
    private var engineLang: String = ""
    @Volatile private var engineBroken: String? = null

    // Parrot wala sabak: plain lambda callback native JNI signature
    // lookup par crash kar sakta hai — explicit object lazmi hai.
    private val genCallback = object : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = 1
    }

    private fun js(code: String) {
        web.post {
            try {
                web.evaluateJavascript(code, null)
            } catch (_: Throwable) {
            }
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", " ").replace("'", " ").replace("\n", " ").take(120)

    private fun packBase(): File = File(ctx.filesDir, PACK_DIR)

    private fun modelFile(): File? {
        val base = packBase()
        val int8 = File(base, "model.int8.onnx")
        if (int8.exists()) return int8
        val fp = File(base, "model.onnx")
        if (fp.exists()) return fp
        return null
    }

    // Pack ke andar voices file ka naam variant par hai (voices.bin ya
    // voices-v1.0.bin) — jo maujood ho wahi istemal karo.
    private fun voicesFile(): File? {
        val base = packBase()
        for (n in listOf("voices.bin", "voices-v1.0.bin")) {
            val f = File(base, n)
            if (f.exists()) return f
        }
        return base.listFiles()?.firstOrNull {
            it.name.startsWith("voices") && it.name.endsWith(".bin")
        }
    }

    @JavascriptInterface
    fun isModelReady(): Boolean {
        return try {
            val base = packBase()
            File(base, ".meer-pack-ok").exists() &&
                modelFile() != null &&
                voicesFile() != null &&
                File(base, "tokens.txt").exists() &&
                File(base, "espeak-ng-data").isDirectory
        } catch (_: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun getModelSizeMB(): String = (MODEL_BYTES / 1048576L).toString()

    // ---------------- download ----------------

    @JavascriptInterface
    fun downloadModel() {
        if (!downloading.compareAndSet(false, true)) return
        io.execute {
            try {
                doDownload()
            } catch (t: Throwable) {
                js("onNeuralDownloadFailed('${esc(t.message ?: "download error")}')")
            } finally {
                downloading.set(false)
            }
        }
    }

    private fun doDownload() {
        if (isModelReady()) {
            js("onNeuralDownloadDone()")
            return
        }
        val part = File(ctx.filesDir, "kokoro-pack.part")
        var written = if (part.exists()) part.length() else 0L
        if (written < MODEL_BYTES) {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000
            conn.readTimeout = 90000
            if (written > 0) conn.setRequestProperty("Range", "bytes=$written-")
            val code = conn.responseCode
            val append: Boolean
            when (code) {
                206 -> append = true
                200 -> {
                    append = false
                    written = 0
                }
                else -> {
                    js("onNeuralDownloadFailed('server $code')")
                    return
                }
            }
            conn.inputStream.use { ins ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastPct = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val pct = (written * 100 / MODEL_BYTES).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            js("onNeuralDownloadProgress($pct)")
                        }
                    }
                    out.flush()
                }
            }
        }
        if (written != MODEL_BYTES || !part.exists() || part.length() != MODEL_BYTES) {
            js("onNeuralDownloadFailed('aadha download (${part.length()} bytes) — dobara dabao, resume hoga')")
            return
        }
        val digest = sha256(part)
        if (!digest.equals(MODEL_SHA256, ignoreCase = true)) {
            part.delete()
            js("onNeuralDownloadFailed('file kharab (check fail) — dobara download karo')")
            return
        }
        extract(part)
        part.delete()
        if (isModelReady()) {
            js("onNeuralDownloadDone()")
        } else {
            js("onNeuralDownloadFailed('pack khulne ke baad files adhoori hain')")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(archive: File) {
        val dest = ctx.filesDir
        val destCanon = dest.canonicalPath
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive))).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(destCanon)) {
                    throw IllegalStateException("pack me ghalat path: ${e.name}")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { o -> tar.copyTo(o) }
                }
                e = tar.nextEntry
            }
        }
        val base = packBase()
        if (modelFile() == null || voicesFile() == null ||
            !File(base, "tokens.txt").exists() || !File(base, "espeak-ng-data").isDirectory
        ) {
            throw IllegalStateException("pack files adhoori hain")
        }
        File(base, ".meer-pack-ok").writeText(MODEL_SHA256)
    }

    // ---------------- synth ----------------

    private fun ensureEngine(lang: String): OfflineTts {
        engineBroken?.let { throw IllegalStateException(it) }
        val cur = engine
        if (cur != null && engineLang == lang) return cur
        try {
            cur?.release()
        } catch (_: Throwable) {
        }
        engine = null
        val base = packBase()
        val model = modelFile() ?: throw IllegalStateException("model file nahi mila")
        val voices = voicesFile() ?: throw IllegalStateException("voices file nahi mila")
        // Sirf wahi lexicon files do jo pack me asal maujood hon.
        val lex = listOf("lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt")
            .map { File(base, it) }
            .filter { it.exists() }
            .joinToString(",") { it.absolutePath }
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = voices.absolutePath,
                    tokens = File(base, "tokens.txt").absolutePath,
                    dataDir = File(base, "espeak-ng-data").absolutePath,
                    lexicon = lex,
                    lang = if (lang == "hi") "hi" else ""
                ),
                numThreads = 2,
                debug = false
            )
        )
        val tts = OfflineTts(config = cfg)
        engine = tts
        engineLang = lang
        return tts
    }

    @JavascriptInterface
    fun synthesize(id: String, text: String, lang: String, sid: Int, speed: Float) {
        if (!isModelReady()) {
            js("onNeuralTTSFailed('$id','model not downloaded')")
            return
        }
        io.execute {
            try {
                val tts = ensureEngine(lang)
                val genConfig = GenerationConfig(
                    sid = sid,
                    speed = speed.coerceIn(0.5f, 2.0f),
                    silenceScale = 0.2f
                )
                val audio = tts.generateWithConfigAndCallback(
                    text = text,
                    config = genConfig,
                    callback = genCallback
                )
                if (audio.samples.isEmpty()) {
                    js("onNeuralTTSFailed('$id','empty audio')")
                    return@execute
                }
                val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "")
                val f = File(ctx.cacheDir, "neural_$safeId.wav")
                writeWav(f, audio.samples, audio.sampleRate)
                val bytes = f.readBytes()
                f.delete()
                if (bytes.size < 2000) {
                    js("onNeuralTTSFailed('$id','empty audio (${bytes.size} bytes)')")
                    return@execute
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                js("onNeuralTTSDone('$id','data:audio/wav;base64,$b64')")
            } catch (t: Throwable) {
                if (t is Error) {
                    // UnsatisfiedLinkError waghera: engine hamesha ke liye
                    // fail-fast — JS har line par device TTS par gir jayegi.
                    engineBroken = "neural engine load fail"
                }
                js("onNeuralTTSFailed('$id','${esc(t.message ?: "neural error")}')")
            }
        }
    }

    private fun writeWav(f: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val header = ByteArray(44)
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        putInt(4, 36 + dataSize)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        putInt(16, 16)              // fmt chunk size
        putShort(20, 1)             // PCM
        putShort(22, 1)             // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)  // byte rate
        putShort(32, 2)             // block align
        putShort(34, 16)            // bits per sample
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        putInt(40, dataSize)
        FileOutputStream(f).use { out ->
            out.write(header)
            val data = ByteArray(dataSize)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                data[i * 2] = (v and 0xff).toByte()
                data[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            out.write(data)
        }
    }
}
