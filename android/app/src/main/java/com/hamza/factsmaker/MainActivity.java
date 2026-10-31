package com.hamza.factsmaker;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Meer Facts Maker v1.0 — WebView shell.
 * Bridges:
 *  - window.HamzaNeuralTTS (NeuralTts.kt, MEER v4.70 wala hi Kokoro bridge)
 *  - window.FactsBridge (download / saveAudio / probe / render / share)
 * Saara facts logic assets/www/index.html ke FACTS MODULE me hai.
 */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new NeuralTtsBridge(this, web), "HamzaNeuralTTS");
        web.addJavascriptInterface(new FactsBridge(), "FactsBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void js(String code) {
        runOnUiThread(() -> {
            try { web.evaluateJavascript(code, null); } catch (Exception ignored) {}
        });
    }

    private String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    public class FactsBridge {
        private final java.util.concurrent.ExecutorService pool = Executors.newCachedThreadPool();

        // Clip download (async, progress ke saath) -> cacheDir/facts_clips/<fileName>
        @JavascriptInterface
        public void downloadAsync(final String url, final String fileName, final String cbId) {
            pool.execute(() -> {
                try {
                    File dir = new File(getCacheDir(), "facts_clips");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, fileName);
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(20000);
                    con.setReadTimeout(60000);
                    con.setRequestProperty("User-Agent", "MeerFactsMaker/1.0 (Android)");
                    con.setInstanceFollowRedirects(true);
                    int code = con.getResponseCode();
                    if (code < 200 || code >= 300) {
                        js("onFactsFailed(" + q(cbId) + ",'download','server " + code + "')");
                        return;
                    }
                    long total = con.getContentLengthLong();
                    InputStream is = con.getInputStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[131072];
                    long got = 0;
                    int lastPct = -1;
                    int r;
                    while ((r = is.read(buf)) > 0) {
                        fos.write(buf, 0, r);
                        got += r;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != lastPct && pct % 2 == 0) {
                                lastPct = pct;
                                js("onFactsProgress(" + q(cbId) + "," + pct + ",'download')");
                            }
                        }
                    }
                    fos.flush(); fos.close(); is.close(); con.disconnect();
                    if (out.length() < 50000) {
                        js("onFactsFailed(" + q(cbId) + ",'download','file bahut chhoti hai')");
                        return;
                    }
                    js("onFactsDownloadDone(" + q(cbId) + "," + q(out.getAbsolutePath()) + ")");
                } catch (Exception e) {
                    js("onFactsFailed(" + q(cbId) + ",'download'," + q(e.getClass().getSimpleName() + ": " + e.getMessage()) + ")");
                }
            });
        }

        // MEER wala hi tareeqa: TTS data-URI (base64 WAV) -> file, path return
        @JavascriptInterface
        public String saveAudioBase64(String base64Data, String fileName) {
            try {
                if (base64Data == null || base64Data.isEmpty()) return "";
                if (base64Data.contains(",")) {
                    base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
                }
                byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                if (data.length < 100) return "";
                File outDir = new File(getCacheDir(), "compose_audio");
                if (!outDir.exists()) outDir.mkdirs();
                File out = new File(outDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(data);
                    fos.flush();
                }
                return out.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public long probeDurationMs(String path) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(path);
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                return d == null ? 0 : Long.parseLong(d);
            } catch (Exception e) {
                return 0;
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        }

        // Render (async) — TranscodeEngine; segmentsJson: [{clip, audio, caption}]
        @JavascriptInterface
        public void renderAsync(final String segmentsJson, final String fileName, final String cbId) {
            pool.execute(() -> {
                try {
                    JSONArray segs = new JSONArray(segmentsJson);
                    TranscodeEngine engine = new TranscodeEngine(MainActivity.this);
                    String result = engine.render(segs, fileName, (pct, msg) ->
                            js("onFactsProgress(" + q(cbId) + "," + pct + "," + q(msg) + ")"));
                    if (result != null && result.startsWith("ok:")) {
                        js("onFactsRenderDone(" + q(cbId) + "," + q(result.substring(3)) + ")");
                    } else {
                        js("onFactsFailed(" + q(cbId) + ",'render'," + q(result) + ")");
                    }
                } catch (Exception e) {
                    js("onFactsFailed(" + q(cbId) + ",'render'," + q(e.getClass().getSimpleName() + ": " + e.getMessage()) + ")");
                }
            });
        }

        // Text file (masalan .srt) videos wale folder me save karo
        @JavascriptInterface
        public String saveTextFile(String name, String content) {
            try {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MeerFacts");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(content.getBytes("UTF-8"));
                    fos.flush();
                }
                return out.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void shareVideo(String path) {
            runOnUiThread(() -> {
                try {
                    File f = new File(path);
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("video/mp4");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Video bhejo"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Share nahi ho saka", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
