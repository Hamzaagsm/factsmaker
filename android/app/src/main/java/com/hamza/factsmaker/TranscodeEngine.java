package com.hamza.factsmaker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Meer Facts Maker — TRANSCODE engine (naya, is app ke liye likha gaya).
 *
 * Kyun naya? Spike (SPIKE.md) ne sabit kiya: archive clips mukhtalif codec/size
 * me aate hain (Wikimedia = VP9 WebM, NASA = H.264 MP4 360p/720p...). MEER ka
 * purana composeVideo sirf REMUX karta hai (uniform H.264 clips chahiye) —
 * in par nahi chal sakta. Ye engine har clip ko DECODE karke uniform
 * 1080x1920 H.264 me ENCODE karta hai:
 *   - har segment ki lambai = us line ki Kokoro awaz + 0.35s
 *   - clip chhota ho to LOOP, lamba ho to TRIM
 *   - har frame par line ka text caption (neeche, dark box par safed haruf)
 *   - audio: MEER wala hi আজmaya wavToAac tareeqa (neeche splice)
 * Guardrails: kisi segment ki awaz fail -> poori render error + partial file
 * delete (kabhi silent video nahi). Output validate kiye baghair ok nahi.
 */
public class TranscodeEngine {

    public interface Progress { void onProgress(int pct, String msg); }

    static final int W = 1080, H = 1920, FPS = 24;
    static final long FRAME_US = 1000000L / FPS;
    static final long GAP_US = 350000L; // har line ke baad 0.35s

    private final Context ctx;
    private String lastWavError = "";

    public TranscodeEngine(Context ctx) { this.ctx = ctx; }

    // ------------------------------------------------------------------
    // YUV frame holder (decoder se compact copy)
    // ------------------------------------------------------------------
    private static class YuvFrame {
        int w, h;
        byte[] y, u, v; // compact: y = w*h, u/v = (w/2)*(h/2)
    }

    private static void copyPlane(Image.Plane plane, byte[] dst, int dstW, int dstH, int dstOff) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        int pos = dstOff;
        for (int r = 0; r < dstH; r++) {
            int srcBase = r * rowStride;
            if (pixStride == 1) {
                buf.position(srcBase);
                buf.get(dst, pos, Math.min(dstW, buf.remaining()));
            } else {
                for (int c = 0; c < dstW; c++) {
                    dst[pos + c] = buf.get(srcBase + c * pixStride);
                }
            }
            pos += dstW;
        }
    }

    private static YuvFrame imageToYuv(Image img) {
        YuvFrame f = new YuvFrame();
        f.w = img.getWidth(); f.h = img.getHeight();
        int cw = (f.w + 1) / 2, ch = (f.h + 1) / 2;
        f.y = new byte[f.w * f.h];
        f.u = new byte[cw * ch];
        f.v = new byte[cw * ch];
        Image.Plane[] planes = img.getPlanes();
        copyPlane(planes[0], f.y, f.w, f.h, 0);
        copyPlane(planes[1], f.u, cw, ch, 0);
        copyPlane(planes[2], f.v, cw, ch, 0);
        return f;
    }

    // ------------------------------------------------------------------
    // Caption bitmap (aik segment ke liye aik dafa)
    // ------------------------------------------------------------------
    private Bitmap makeCaptionBitmap(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        int maxW = W - 90;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(47f);
        tp.setShadowLayer(6f, 0f, 2f, 0xFF000000);
        StaticLayout layout = StaticLayout.Builder.obtain(text.trim(), 0, text.trim().length(), tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(4f, 1.05f)
                .build();
        int bh = Math.min(layout.getHeight() + 28, 320);
        Bitmap bmp = Bitmap.createBitmap(maxW, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.translate(0, 14);
        layout.draw(c);
        return bmp;
    }

    // Caption ko scaled YUV frame par lagao (neeche band: dark box + safed text)
    private void drawCaption(byte[] yOut, byte[] uOut, byte[] vOut, Bitmap cap) {
        if (cap == null) return;
        int cw = W / 2, ch = H / 2;
        int bw = cap.getWidth(), bh = cap.getHeight();
        int x0 = (W - bw) / 2;
        int yTop = H - bh - 64;
        if (yTop < 0) yTop = 0;
        int[] px = new int[bw];
        for (int r = 0; r < bh; r++) {
            int yy = yTop + r;
            if (yy >= H) break;
            cap.getPixels(px, 0, bw, 0, r, bw, 1);
            int yBase = yy * W;
            int uvBase = (yy / 2) * cw;
            for (int i = 0; i < bw; i++) {
                int a = (px[i] >>> 24) & 0xFF;
                int xx = x0 + i;
                if (xx < 0 || xx >= W) continue;
                // dark box poori band par (halka sa)
                int yi = yBase + xx;
                yOut[yi] = (byte) (((yOut[yi] & 0xFF) * 45) / 100);
                int uvi = uvBase + xx / 2;
                uOut[uvi] = (byte) (((uOut[uvi] & 0xFF) * 45 + 128 * 55) / 100);
                vOut[uvi] = (byte) (((vOut[uvi] & 0xFF) * 45 + 128 * 55) / 100);
                if (a > 110) { // text pixel — safed
                    yOut[yi] = (byte) 235;
                    uOut[uvi] = (byte) 128;
                    vOut[uvi] = (byte) 128;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Watermark (FACTS_WATERMARK_V1) — har frame par neeche-dayen konay me
    // semi-transparent safed text. Pixel-count guard: agar text manga gaya
    // lekin bitmap me koi visible pixel nahi bana to render FAIL hoga —
    // dead toggle kabhi ship nahi hoga.
    // ------------------------------------------------------------------
    private Bitmap makeWatermarkBitmap(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(44f);
        tp.setShadowLayer(5f, 0f, 2f, 0xFF000000);
        float tw = tp.measureText(text.trim());
        int bw = (int) Math.ceil(tw) + 20;
        int bh = 68;
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawText(text.trim(), 10, 48, tp);
        return bmp;
    }

    private long countVisiblePixels(Bitmap bmp) {
        if (bmp == null) return 0;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        int[] row = new int[bw];
        long cnt = 0;
        for (int r = 0; r < bh; r++) {
            bmp.getPixels(row, 0, bw, 0, r, bw, 1);
            for (int i = 0; i < bw; i++) if (((row[i] >>> 24) & 0xFF) > 10) cnt++;
        }
        return cnt;
    }

    private void drawWatermark(byte[] yOut, byte[] uOut, byte[] vOut, Bitmap wm) {
        if (wm == null) return;
        int cw = W / 2;
        int bw = wm.getWidth(), bh = wm.getHeight();
        int x0 = W - bw - 36;
        int y0 = H - bh - 36;
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        int[] px = new int[bw];
        for (int r = 0; r < bh; r++) {
            int yy = y0 + r;
            if (yy >= H) break;
            wm.getPixels(px, 0, bw, 0, r, bw, 1);
            int yBase = yy * W;
            int uvBase = (yy / 2) * cw;
            for (int i = 0; i < bw; i++) {
                int a = (px[i] >>> 24) & 0xFF;
                if (a <= 10) continue;
                int xx = x0 + i;
                if (xx < 0 || xx >= W) continue;
                double k = (a / 255.0) * 0.55; // semi-transparent
                int yi = yBase + xx;
                int uvi = uvBase + xx / 2;
                yOut[yi] = (byte) Math.min(255, (int) ((yOut[yi] & 0xFF) * (1 - k) + 235 * k));
                uOut[uvi] = (byte) ((uOut[uvi] & 0xFF) * (1 - k) + 128 * k);
                vOut[uvi] = (byte) ((vOut[uvi] & 0xFF) * (1 - k) + 128 * k);
            }
        }
    }

    // ------------------------------------------------------------------
    // Bilinear scale (cover-crop) src YUV -> out buffers (W x H)
    // ------------------------------------------------------------------
    private static class Scaler {
        int[] mapX0, mapX1, mapFx, mapY0, mapY1, mapFy;
        int[] cmapX0, cmapX1, cmapFx, cmapY0, cmapY1, cmapFy;
        int srcW, srcH;
        Scaler(YuvFrame s) {
            srcW = s.w; srcH = s.h;
            double scale = Math.max((double) W / s.w, (double) H / s.h);
            double offX = (s.w * scale - W) / 2.0, offY = (s.h * scale - H) / 2.0;
            mapX0 = new int[W]; mapX1 = new int[W]; mapFx = new int[W];
            for (int x = 0; x < W; x++) {
                double sx = (x + offX + 0.5) / scale - 0.5;
                int x0 = (int) Math.floor(sx); double fx = sx - x0;
                if (x0 < 0) { x0 = 0; fx = 0; }
                if (x0 > s.w - 2) { x0 = s.w - 2; fx = 0; }
                if (s.w < 2) { x0 = 0; fx = 0; }
                mapX0[x] = x0; mapX1[x] = Math.min(x0 + 1, s.w - 1); mapFx[x] = (int) (fx * 256);
            }
            mapY0 = new int[H]; mapY1 = new int[H]; mapFy = new int[H];
            for (int y = 0; y < H; y++) {
                double sy = (y + offY + 0.5) / scale - 0.5;
                int y0 = (int) Math.floor(sy); double fy = sy - y0;
                if (y0 < 0) { y0 = 0; fy = 0; }
                if (y0 > s.h - 2) { y0 = s.h - 2; fy = 0; }
                if (s.h < 2) { y0 = 0; fy = 0; }
                mapY0[y] = y0; mapY1[y] = Math.min(y0 + 1, s.h - 1); mapFy[y] = (int) (fy * 256);
            }
            int cw = (s.w + 1) / 2, ch = (s.h + 1) / 2, tw = W / 2, th = H / 2;
            double cscale = Math.max((double) tw / cw, (double) th / ch);
            double coffX = (cw * cscale - tw) / 2.0, coffY = (ch * cscale - th) / 2.0;
            cmapX0 = new int[tw]; cmapX1 = new int[tw]; cmapFx = new int[tw];
            for (int x = 0; x < tw; x++) {
                double sx = (x + coffX + 0.5) / cscale - 0.5;
                int x0 = (int) Math.floor(sx); double fx = sx - x0;
                if (x0 < 0) { x0 = 0; fx = 0; }
                if (x0 > cw - 2) { x0 = Math.max(0, cw - 2); fx = 0; }
                if (cw < 2) { x0 = 0; fx = 0; }
                cmapX0[x] = x0; cmapX1[x] = Math.min(x0 + 1, Math.max(0, cw - 1)); cmapFx[x] = (int) (fx * 256);
            }
            cmapY0 = new int[th]; cmapY1 = new int[th]; cmapFy = new int[th];
            for (int y = 0; y < th; y++) {
                double sy = (y + coffY + 0.5) / cscale - 0.5;
                int y0 = (int) Math.floor(sy); double fy = sy - y0;
                if (y0 < 0) { y0 = 0; fy = 0; }
                if (y0 > ch - 2) { y0 = Math.max(0, ch - 2); fy = 0; }
                if (ch < 2) { y0 = 0; fy = 0; }
                cmapY0[y] = y0; cmapY1[y] = Math.min(y0 + 1, Math.max(0, ch - 1)); cmapFy[y] = (int) (fy * 256);
            }
        }
    }

    private static void scalePlane(byte[] src, int sw, int[] mx0, int[] mx1, int[] mfx,
                                   int[] my0, int[] my1, int[] mfy, byte[] dst, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            int y0 = my0[y], y1 = my1[y], fy = mfy[y], ify = 256 - fy;
            int r0 = y0 * sw, r1 = y1 * sw, dBase = y * dw;
            for (int x = 0; x < dw; x++) {
                int x0 = mx0[x], x1 = mx1[x], fx = mfx[x], ifx = 256 - fx;
                int p00 = src[r0 + x0] & 0xFF, p10 = src[r0 + x1] & 0xFF;
                int p01 = src[r1 + x0] & 0xFF, p11 = src[r1 + x1] & 0xFF;
                int top = (p00 * ifx + p10 * fx) >> 8;
                int bot = (p01 * ifx + p11 * fx) >> 8;
                dst[dBase + x] = (byte) ((top * ify + bot * fy) >> 8);
            }
        }
    }

    private static void writePlaneToImage(Image.Plane plane, byte[] data, int w, int h) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        buf.clear();
        for (int r = 0; r < h; r++) {
            int base = r * rowStride;
            if (pixStride == 1) {
                buf.position(base);
                buf.put(data, r * w, Math.min(w, buf.capacity() - base));
            } else {
                for (int c = 0; c < w; c++) {
                    int idx = base + c * pixStride;
                    if (idx < buf.capacity()) buf.put(idx, data[r * w + c]);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Segment decoder: frames tartib se deta hai, clip khatam ho to LOOP
    // ------------------------------------------------------------------
    private static class SegmentDecoder {
        MediaExtractor ex;
        MediaCodec dec;
        int track;
        String mime;
        long clipDurUs = 0;
        long loopBaseUs = 0;
        int loops = 0;
        boolean inputEos = false;
        boolean started = false;
        YuvFrame pending = null;
        long pendingPts = -1;
        boolean eos = false;

        boolean open(String path) {
            try {
                ex = new MediaExtractor();
                ex.setDataSource(path);
                track = -1;
                MediaFormat fmt = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String m = f.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("video/")) { track = i; fmt = f; mime = m; break; }
                }
                if (track < 0) return false;
                ex.selectTrack(track);
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) clipDurUs = fmt.getLong(MediaFormat.KEY_DURATION);
                dec = MediaCodec.createDecoderByType(mime);
                dec.configure(fmt, null, null, 0);
                dec.start();
                started = true;
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // Agla decoded frame (effective pts ke saath) ya null agar EOS/loop-limit
        private Object[] nextFrame() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                if (!inputEos) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = dec.getInputBuffer(inIdx);
                        int sz = ex.readSampleData(inBuf, 0);
                        if (sz < 0) {
                            if (loops < 12 && clipDurUs > 0) {
                                // LOOP: clip dobara shuru
                                try {
                                    ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                                    dec.flush();
                                    loopBaseUs += clipDurUs;
                                    loops++;
                                } catch (Exception e) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputEos = true;
                                }
                            } else {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                            }
                        } else {
                            long pts = ex.getSampleTime();
                            dec.queueInputBuffer(inIdx, 0, sz, loopBaseUs + Math.max(0, pts), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    YuvFrame frame = null;
                    long pts = info.presentationTimeUs;
                    if (info.size > 0) {
                        Image img = dec.getOutputImage(outIdx);
                        if (img != null) frame = imageToYuv(img);
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if (frame != null) return new Object[]{frame, pts};
                    if (isEos) { eos = true; return null; }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignore — image dims output image se milte hain
                }
            }
            eos = true;
            return null;
        }

        void close() {
            try { if (started) dec.stop(); } catch (Exception ignored) {}
            try { dec.release(); } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    private long mediaDurationMs(String path) {
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

    // AAC (mp4) segment ko muxer me copy karo, pts offset ke saath.
    private long copyAudioSegment(String aacPath, MediaMuxer muxer, int audioTrack, long startPtsUs) {
        MediaExtractor ex = new MediaExtractor();
        long durUs = 0;
        try {
            ex.setDataSource(aacPath);
            int at = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { at = i; break; }
            }
            if (at < 0) return 0;
            ex.selectTrack(at);
            ByteBuffer buf = ByteBuffer.allocate(262144);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long lastPts = 0;
            while (true) {
                int sz = ex.readSampleData(buf, 0);
                if (sz < 0) break;
                long st = ex.getSampleTime();
                bi.offset = 0; bi.size = sz;
                bi.presentationTimeUs = startPtsUs + Math.max(0, st);
                bi.flags = ex.getSampleFlags();
                muxer.writeSampleData(audioTrack, buf, bi);
                lastPts = st;
                ex.advance();
            }
            durUs = lastPts + 30000;
        } catch (Exception ignored) {
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
        return durUs;
    }

    // ------------------------------------------------------------------
    // Encoder/muxer shared state (aik render ke liye)
    // ------------------------------------------------------------------
    private MediaCodec enc;
    private MediaMuxer muxer;
    private int vTrack = -1, aTrack = -1;
    private boolean muxStarted = false;
    private boolean encEosSeen = false;
    private MediaFormat pendingAudioFmt = null;
    private final List<Object[]> heldSamples = new ArrayList<>(); // {byte[], pts, flags}

    private void drainEncoder(boolean wait) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, wait ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxStarted) {
                    vTrack = muxer.addTrack(enc.getOutputFormat());
                    aTrack = muxer.addTrack(pendingAudioFmt);
                    muxer.start();
                    muxStarted = true;
                    for (Object[] hs : heldSamples) {
                        byte[] data = (byte[]) hs[0];
                        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                        bi.offset = 0; bi.size = data.length;
                        bi.presentationTimeUs = (Long) hs[1];
                        bi.flags = (Integer) hs[2];
                        muxer.writeSampleData(vTrack, ByteBuffer.wrap(data), bi);
                    }
                    heldSamples.clear();
                }
                continue;
            }
            if (idx >= 0) {
                ByteBuffer out = enc.getOutputBuffer(idx);
                if (info.size > 0 && out != null) {
                    if (muxStarted) {
                        muxer.writeSampleData(vTrack, out, info);
                    } else {
                        byte[] copy = new byte[info.size];
                        out.position(info.offset);
                        out.get(copy);
                        heldSamples.add(new Object[]{copy, info.presentationTimeUs, info.flags});
                    }
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                enc.releaseOutputBuffer(idx, false);
                if (eos) { encEosSeen = true; break; }
            }
        }
    }

    private void feedFrame(byte[] y, byte[] u, byte[] v, long ptsUs) {
        while (true) {
            int inIdx = enc.dequeueInputBuffer(10000);
            if (inIdx >= 0) {
                Image img = enc.getInputImage(inIdx);
                if (img == null) throw new RuntimeException("encoder_input_image_null");
                writePlaneToImage(img.getPlanes()[0], y, W, H);
                writePlaneToImage(img.getPlanes()[1], u, W / 2, H / 2);
                writePlaneToImage(img.getPlanes()[2], v, W / 2, H / 2);
                enc.queueInputBuffer(inIdx, 0, W * H * 3 / 2, ptsUs, 0);
                break;
            }
            drainEncoder(false);
        }
        drainEncoder(false);
    }

    private void copyToGallery(File f) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, f.getName());
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MeerFacts");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return;
            OutputStream os = cr.openOutputStream(uri);
            InputStream is = new FileInputStream(f);
            byte[] b = new byte[262144];
            int r;
            while ((r = is.read(b)) > 0) os.write(b, 0, r);
            os.close(); is.close();
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // MAIN RENDER — segments: [{clip, audio(wav), caption}]
    // return: "ok:<path>" ya "error:<wajah>"
    // ------------------------------------------------------------------
    public String render(JSONArray segs, String outName, Progress prog) {
        File outDir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MeerFacts");
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = new File(outDir, outName);
        if (outFile.exists()) outFile.delete();
        int n = segs == null ? 0 : segs.length();
        if (n == 0) return "error:no_segments";
        String[] aacPaths = new String[n];
        try {
            // --- 1) Awaz tayyar (har line ki AAC) ---
            long[] segDurUs = new long[n];
            long totalUs = 0;
            pendingAudioFmt = null;
            for (int i = 0; i < n; i++) {
                JSONObject seg = segs.getJSONObject(i);
                String wav = seg.optString("audio", "");
                String aac = wavToAac(wav);
                if (aac == null || aac.isEmpty()) {
                    return fail(outFile, "error:voice_line_" + (i + 1) + " (" + lastWavError + ")");
                }
                aacPaths[i] = aac;
                long adMs = mediaDurationMs(aac);
                if (adMs < 300) {
                    return fail(outFile, "error:voice_line_" + (i + 1) + "_too_short");
                }
                segDurUs[i] = adMs * 1000L + GAP_US;
                totalUs += segDurUs[i];
                if (pendingAudioFmt == null) {
                    MediaExtractor ae = new MediaExtractor();
                    try {
                        ae.setDataSource(aac);
                        for (int t = 0; t < ae.getTrackCount(); t++) {
                            String m = ae.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
                            if (m != null && m.startsWith("audio/")) { pendingAudioFmt = ae.getTrackFormat(t); break; }
                        }
                    } finally { try { ae.release(); } catch (Exception ignored) {} }
                }
            }
            if (pendingAudioFmt == null) return fail(outFile, "error:no_audio_format");

            // --- 2) Encoder + muxer ---
            MediaFormat vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            vFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            vFmt.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);
            vFmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            vFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            vTrack = -1; aTrack = -1; muxStarted = false; encEosSeen = false;
            heldSamples.clear();

            byte[] yOut = new byte[W * H];
            byte[] uOut = new byte[(W / 2) * (H / 2)];
            byte[] vOut = new byte[(W / 2) * (H / 2)];
            long segStartUs = 0;

            // Watermark tayyar karo (pehle segment ka "wm" — sab segments par wohi lagta hai)
            String wmReq = n > 0 ? segs.getJSONObject(0).optString("wm", "") : "";
            Bitmap wmBmp = makeWatermarkBitmap(wmReq);
            long wmPixels = countVisiblePixels(wmBmp);
            if (wmReq != null && wmReq.trim().length() > 0 && wmPixels == 0) {
                try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignored) {}
                try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
                return fail(outFile, "error:watermark_not_drawn");
            }

            // --- 3) Har segment: decode -> scale -> caption -> encode -> audio copy ---
            for (int s = 0; s < n; s++) {
                JSONObject seg = segs.getJSONObject(s);
                if (prog != null) prog.onProgress(5 + (int) (85.0 * s / n), "Scene " + (s + 1) + " / " + n + " ban raha hai");
                String clipPath = seg.optString("clip", "");
                SegmentDecoder sd = new SegmentDecoder();
                if (!sd.open(clipPath)) {
                    sd.close();
                    return fail(outFile, "error:clip_line_" + (s + 1) + " khul nahi saka");
                }
                Bitmap cap = makeCaptionBitmap(seg.optString("caption", ""));
                Scaler scaler = null;
                Object[] pf = sd.nextFrame();
                YuvFrame pendingF = pf == null ? null : (YuvFrame) pf[0];
                long pendingPts = pf == null ? Long.MAX_VALUE : (Long) pf[1];
                YuvFrame cur = null;
                int frames = (int) Math.ceil(segDurUs[s] / (double) FRAME_US);
                for (int f = 0; f < frames; f++) {
                    long tSrc = f * FRAME_US;
                    while (pendingF != null && pendingPts <= tSrc) {
                        cur = pendingF;
                        Object[] nf = sd.nextFrame();
                        if (nf == null) { pendingF = null; pendingPts = Long.MAX_VALUE; }
                        else { pendingF = (YuvFrame) nf[0]; pendingPts = (Long) nf[1]; }
                    }
                    if (cur == null) cur = pendingF;
                    if (cur == null) { sd.close(); return fail(outFile, "error:clip_line_" + (s + 1) + " frame nahi mila"); }
                    if (scaler == null || scaler.srcW != cur.w || scaler.srcH != cur.h) scaler = new Scaler(cur);
                    scalePlane(cur.y, cur.w, scaler.mapX0, scaler.mapX1, scaler.mapFx, scaler.mapY0, scaler.mapY1, scaler.mapFy, yOut, W, H);
                    int scw = (cur.w + 1) / 2;
                    scalePlane(cur.u, scw, scaler.cmapX0, scaler.cmapX1, scaler.cmapFx, scaler.cmapY0, scaler.cmapY1, scaler.cmapFy, uOut, W / 2, H / 2);
                    scalePlane(cur.v, scw, scaler.cmapX0, scaler.cmapX1, scaler.cmapFx, scaler.cmapY0, scaler.cmapY1, scaler.cmapFy, vOut, W / 2, H / 2);
                    drawCaption(yOut, uOut, vOut, cap);
                    drawWatermark(yOut, uOut, vOut, wmBmp);
                    feedFrame(yOut, uOut, vOut, segStartUs + f * FRAME_US);
                }
                sd.close();
                if (cap != null) cap.recycle();
                // muxer start lazmi (pehle segment ki frames se format aa jata hai)
                long spinDeadline = System.currentTimeMillis() + 10000;
                while (!muxStarted && System.currentTimeMillis() < spinDeadline) drainEncoder(true);
                if (!muxStarted) return fail(outFile, "error:muxer_start_nahi_hua");
                copyAudioSegment(aacPaths[s], muxer, aTrack, segStartUs);
                // temp safai (lambi videos me storage bachana)
                try { new File(clipPath).delete(); } catch (Exception ignored) {}
                try { new File(seg.optString("audio", "")).delete(); } catch (Exception ignored) {}
                try { new File(aacPaths[s]).delete(); } catch (Exception ignored) {}
                segStartUs += segDurUs[s];
            }

            // --- 4) EOS + band ---
            while (true) {
                int inIdx = enc.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    enc.queueInputBuffer(inIdx, 0, 0, segStartUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                drainEncoder(false);
            }
            long eosDeadline = System.currentTimeMillis() + 30000;
            while (!encEosSeen && System.currentTimeMillis() < eosDeadline) drainEncoder(true);
            try { if (muxStarted) muxer.stop(); } catch (Exception ignored) {}
            try { muxer.release(); } catch (Exception ignored) {}
            try { enc.stop(); } catch (Exception ignored) {}
            try { enc.release(); } catch (Exception ignored) {}
            muxer = null; enc = null;

            // --- 5) Validation (MEER wala usool: baghair check ok nahi) ---
            if (!outFile.exists() || outFile.length() < 10000) {
                return fail(outFile, "error:video_file_too_small");
            }
            MediaExtractor ve = new MediaExtractor();
            boolean hasV = false, hasA = false;
            long outDurMs = 0;
            try {
                ve.setDataSource(outFile.getAbsolutePath());
                for (int t = 0; t < ve.getTrackCount(); t++) {
                    String m = ve.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("video/")) hasV = true;
                    if (m != null && m.startsWith("audio/")) hasA = true;
                }
            } finally { try { ve.release(); } catch (Exception ignored) {} }
            outDurMs = mediaDurationMs(outFile.getAbsolutePath());
            if (!hasV) return fail(outFile, "error:no_video_track");
            if (!hasA) return fail(outFile, "error:no_audio_track");
            long expectMs = totalUs / 1000;
            if (outDurMs < expectMs * 0.6 || outDurMs > expectMs * 1.5 + 5000) {
                return fail(outFile, "error:duration_mismatch (" + outDurMs + "ms vs ~" + expectMs + "ms)");
            }
            copyToGallery(outFile);
            if (prog != null) prog.onProgress(100, "Video tayyar hai");
            if (wmBmp != null) wmBmp.recycle();
            return "ok:" + outFile.getAbsolutePath() + "|wm=" + wmPixels;
        } catch (Exception e) {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (enc != null) enc.release(); } catch (Exception ignored) {}
            muxer = null; enc = null;
            return fail(outFile, "error:" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String fail(File outFile, String msg) {
        try { if (outFile != null && outFile.exists()) outFile.delete(); } catch (Exception ignored) {}
        return msg;
    }

    // ------------------------------------------------------------------
    // wavToAac — MEER MainActivity se VERBATIM splice (v3.69-v3.84 wala
    // azmaya hua code; sirf getCacheDir() -> ctx.getCacheDir() badla hai).
    // WAV (Kokoro) -> AAC-in-MP4 taake MediaMuxer me copy ho sake.
    // ------------------------------------------------------------------
        private String wavToAac(String wavPath) {
            lastWavError = "";
            File wavFile = new File(wavPath);
            if (!wavFile.exists() || wavFile.length() < 100) { lastWavError = "no_file"; return ""; }
            try {
                // Poori file parho
                byte[] fileData = new byte[(int) wavFile.length()];
                java.io.FileInputStream fis = new java.io.FileInputStream(wavFile);
                int totalRead = 0;
                while (totalRead < fileData.length) {
                    int r = fis.read(fileData, totalRead, fileData.length - totalRead);
                    if (r < 0) break;
                    totalRead += r;
                }
                fis.close();
                if (totalRead < 44) { lastWavError = "short_file"; return ""; }
                // "RIFF" check
                if (fileData[0] != 'R' || fileData[1] != 'I' || fileData[2] != 'F' || fileData[3] != 'F') { lastWavError = "no_riff"; return ""; }

                // Chunks dhoondo: "fmt " aur "data" (extra chunks skip karo!)
                int sampleRate = 22050, channels = 1, bitsPerSample = 16;
                int dataStart = -1, dataLen = 0;
                int pos = 12; // "RIFF" + size + "WAVE" ke baad
                while (pos + 8 <= fileData.length) {
                    String chunkId = new String(fileData, pos, 4, "ASCII");
                    int chunkSize = (fileData[pos+4] & 0xFF) | ((fileData[pos+5] & 0xFF) << 8) |
                                   ((fileData[pos+6] & 0xFF) << 16) | ((fileData[pos+7] & 0xFF) << 24);
                    if (chunkSize < 0 || pos + 8 + chunkSize > fileData.length + 1) break;
                    if (chunkId.equals("fmt ")) {
                        if (chunkSize >= 16) {
                            // Audio format (bytes 0-1), channels (2-3), sample rate (4-7), bits (14-15)
                            channels = (fileData[pos+8+2] & 0xFF) | ((fileData[pos+8+3] & 0xFF) << 8);
                            sampleRate = (fileData[pos+8+4] & 0xFF) | ((fileData[pos+8+5] & 0xFF) << 8) |
                                        ((fileData[pos+8+6] & 0xFF) << 16) | ((fileData[pos+8+7] & 0xFF) << 24);
                            bitsPerSample = (fileData[pos+8+14] & 0xFF) | ((fileData[pos+8+15] & 0xFF) << 8);
                        }
                    } else if (chunkId.equals("data")) {
                        dataStart = pos + 8;
                        dataLen = chunkSize;
                        break; // Data mil gaya, bas!
                    }
                    pos += 8 + chunkSize;
                    if (chunkSize % 2 == 1) pos++; // Word-aligned padding
                }
                if (dataStart < 0) {
                    // "data" chunk nahi mila - purana tareeqa (offset 44)
                    dataStart = 44;
                    dataLen = fileData.length - 44;
                    // Sample rate/channels/bits header se lo
                    sampleRate = (fileData[24] & 0xFF) | ((fileData[25] & 0xFF) << 8) |
                                ((fileData[26] & 0xFF) << 16) | ((fileData[27] & 0xFF) << 24);
                    channels = (fileData[22] & 0xFF) | ((fileData[23] & 0xFF) << 8);
                    bitsPerSample = (fileData[34] & 0xFF) | ((fileData[35] & 0xFF) << 8);
                }
                if (sampleRate <= 0 || sampleRate > 96000) sampleRate = 22050;
                if (channels <= 0 || channels > 2) channels = 1;
                if (dataLen <= 0 || dataStart + dataLen > fileData.length) dataLen = fileData.length - dataStart;
                if (dataLen < 100) { lastWavError = "no_pcm"; return ""; }

                // PCM data nikalo (8/16/24-bit -> 16-bit convert!)
                byte[] pcmData;
                if (bitsPerSample == 16) {
                    pcmData = new byte[dataLen];
                    System.arraycopy(fileData, dataStart, pcmData, 0, dataLen);
                } else if (bitsPerSample == 8) {
                    // 8-bit unsigned -> 16-bit signed
                    int samples = dataLen;
                    pcmData = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) {
                        short s = (short) ((fileData[dataStart + i] & 0xFF) - 128 << 8);
                        pcmData[i*2] = (byte)(s & 0xFF);
                        pcmData[i*2+1] = (byte)((s >> 8) & 0xFF);
                    }
                } else if (bitsPerSample == 24) {
                    // 24-bit -> 16-bit (top 2 bytes)
                    int samples = dataLen / 3;
                    pcmData = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) {
                        pcmData[i*2] = fileData[dataStart + i*3 + 1];
                        pcmData[i*2+1] = fileData[dataStart + i*3 + 2];
                    }
                } else {
                    lastWavError = "bad_bits"; return ""; // Unsupported bit depth
                }
                int read = pcmData.length;

                // AAC encoder setup
                android.media.MediaFormat format = android.media.MediaFormat.createAudioFormat(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                format.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 64000);
                format.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

                android.media.MediaCodec encoder = android.media.MediaCodec.createEncoderByType(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC);
                encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();

                // v3.80: AAC ko MP4 container me likho! (raw .aac MediaExtractor nahi parh sakta - isi liye awaz nahi aati thi!)
                File mp4File = new File(ctx.getCacheDir(), "aac_" + System.nanoTime() + ".mp4"); // v3.84: nanoTime (millis collision se bacho!)
                android.media.MediaMuxer tmpMuxer = null;
                int tmpAudioTrack = -1;
                boolean tmpMuxerStarted = false;
                try { // v3.84: encoder/muxer HAMESHA release ho (exception par bhi - leak se agle segments fail hote the!)

                java.nio.ByteBuffer[] inputBuffers = encoder.getInputBuffers();
                java.nio.ByteBuffer[] outputBuffers = encoder.getOutputBuffers();
                android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

                int offset = 0;
                boolean inputDone = false;
                boolean outputDone = false;
                long ptsUs = 0;
                int bytesPerSample = 2; // 16-bit
                long totalSamples = read / (bytesPerSample * channels);

                while (!outputDone) {
                    if (!inputDone) {
                        int inputIdx = encoder.dequeueInputBuffer(10000);
                        if (inputIdx >= 0) {
                            java.nio.ByteBuffer inputBuf = inputBuffers[inputIdx];
                            inputBuf.clear();
                            int chunkSize = Math.min(inputBuf.remaining(), read - offset);
                            if (chunkSize > 0) {
                                inputBuf.put(pcmData, offset, chunkSize);
                                long samplesInChunk = chunkSize / (bytesPerSample * channels);
                                // v3.83: AAKHRI chunk par EOS flag ISI buffer par lagao!
                                // (Pehle: data queue ke baad wohi index dobara EOS ke liye queue hota tha
                                //  -> IllegalStateException -> poora transcode fail -> awaz ghaib!)
                                if (offset + chunkSize >= read) {
                                    encoder.queueInputBuffer(inputIdx, 0, chunkSize, ptsUs,
                                        android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputDone = true;
                                } else {
                                    encoder.queueInputBuffer(inputIdx, 0, chunkSize, ptsUs, 0);
                                }
                                ptsUs += (samplesInChunk * 1000000L) / sampleRate;
                                offset += chunkSize;
                            } else {
                                // Koi data nahi bacha — khaali EOS buffer bhejo
                                encoder.queueInputBuffer(inputIdx, 0, 0, ptsUs,
                                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            }
                        }
                    }
                    int outputIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                    if (outputIdx >= 0) {
                        java.nio.ByteBuffer outputBuf = outputBuffers[outputIdx];
                        if ((bufferInfo.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // Codec config: muxer ka track banao!
                            if (tmpMuxer == null) {
                                try {
                                    tmpMuxer = new android.media.MediaMuxer(mp4File.getAbsolutePath(),
                                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                                    tmpAudioTrack = tmpMuxer.addTrack(encoder.getOutputFormat());
                                    tmpMuxer.start();
                                    tmpMuxerStarted = true;
                                } catch (Exception ignored) {}
                            }
                        } else if (bufferInfo.size > 0) {
                            // v3.82: Agar config buffer kabhi nahi aaya (kuch encoders aise hain),
                            // to pehle real sample par hi format se track banao (csd-0 format me hota hai)!
                            if (!tmpMuxerStarted) {
                                try {
                                    tmpMuxer = new android.media.MediaMuxer(mp4File.getAbsolutePath(),
                                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                                    tmpAudioTrack = tmpMuxer.addTrack(encoder.getOutputFormat());
                                    tmpMuxer.start();
                                    tmpMuxerStarted = true;
                                } catch (Exception ignored) {}
                            }
                            if (tmpMuxerStarted) {
                                // AAC sample ko MP4 me likho!
                                tmpMuxer.writeSampleData(tmpAudioTrack, outputBuf, bufferInfo);
                            }
                        }
                        encoder.releaseOutputBuffer(outputIdx, false);
                        if ((bufferInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    } else if (outputIdx == android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        outputBuffers = encoder.getOutputBuffers();
                    } else if (outputIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // v3.82: Encoder ne output format de diya (csd-0 isi me!) — muxer track banao!
                        if (tmpMuxer == null) {
                            try {
                                tmpMuxer = new android.media.MediaMuxer(mp4File.getAbsolutePath(),
                                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                                tmpAudioTrack = tmpMuxer.addTrack(encoder.getOutputFormat());
                                tmpMuxer.start();
                                tmpMuxerStarted = true;
                            } catch (Exception ignored) {}
                        }
                    }
                }
                } finally {
                    try { encoder.stop(); } catch (Exception ignored) {}
                    try { encoder.release(); } catch (Exception ignored) {}
                    if (tmpMuxer != null) {
                        try { tmpMuxer.stop(); } catch (Exception ignored) {}
                        try { tmpMuxer.release(); } catch (Exception ignored) {}
                    }
                }

                if (mp4File.exists() && mp4File.length() > 100) {
                    return mp4File.getAbsolutePath();
                }
            } catch (Exception e) {
                lastWavError = "exception:" + e.getClass().getSimpleName();
            }
            return "";
        }
}
