# Meer Facts Maker — STEP 0 SPIKE (2026-10-31, is VM se live test)

Sawal: kya FREE, keyless archive sources se asal downloadable VIDEO clips milte hain
jo Hamza ka phone seedha fetch kar sake? Teen sources test hue, asal downloads + ffprobe ke saath.

## 1. Wikimedia Commons API — ✅ PRIMARY SOURCE (coverage sab se bari)
- Search: `https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=<q> filetype:video&gsrnamespace=6&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=320`
- Results (live): octopus → 5+ clips (1280x720–1920x1080); honey bee → kai clips (incl. ek 1080x1920 portrait!);
  moon landing → Apollo clips; sea otter → kai clips. Har topic par results mile.
- **Format haqeeqat:** originals **WebM (VP9) / OGV (Theora)** hain, MP4 NAHI.
  - Asal download proof: `Hairy_octopus.webm` — ffprobe: **vp9 + opus, 1280x720, 18.9s, 8.5 MB**.
  - MP4 derivatives: **NAHI milte** (transcoded `...480p.mp4 / 720p.mp4 / 1080p.mp4` teeno 404).
    Sirf VP9 webm derivatives hain (`480p.vp9.webm` 200 OK, 2.6 MB).
  - Native MP4 uploads: top-30 results me **0** (octopus / sea otter / honey bee / moon — sab par).
- Matlab: Commons = coverage king, lekin clip WebM/VP9 me aata hai.
  Android ke MediaCodec/ExoPlayer VP9 decode har modern phone par hai (Pixel 9, Infinix dono),
  is liye **decode-anything → uniform encode** wala naya engine chahiye (neeche faisla dekho).
  MEER ka purana `composeVideo` remuxer is par NAHI chalega (wo H.264 samples copy karta hai).

## 2. NASA Image & Video Library API — ✅ SECONDARY SOURCE (space/science, seedha MP4)
- Search: `https://images-api.nasa.gov/search?q=<q>&media_type=video` (keyless, CORS-friendly)
  → item ka `collection.json` file list deta hai jisme seedha MP4 variants hote hain.
- Asal download proof (VIPER Moon rover video, nasa_id ARC-20210920-AAV3366-...):
  - `~mobile.mp4` — h264+aac, **320x180**, 6.8 MB / 165s
  - `~small.mp4` — h264+aac, **640x360**, 30.7 MB / 165s
  - `~medium.mp4` — h264+aac, **1280x720**, 47.4 MB / 165s
  - `~large.mp4` — 104 MB (HEAD 200)
- Neutron star search par bhi video results mile (pulsar simulations).
- Kami: sirf space/science topics; clips LAMBE hote hain (minutes) aur bhari (~0.3 MB per second medium par).
  Hamza ki hidayat (2026-10-31 21:53): **net hota hai, sab online rakho, size ki fikr NAHI** — is liye ye kami qabool hai.
- App variant order: `~medium` (720p) > `~large` > `~small` > `~mobile` > `~orig`.

## 3. Archive.org — ❌ REJECT for v1
- advancedsearch (mediatype:movies) generic topics par **full movies / TV rips** deta hai
  (octopus search ka top result "LetItBe.mp4" 502.9 MB — Beatles film!). Copyright + size + relevance teeno fail.
  Short fact B-roll ke liye na search kaam ka, na files. **v1 me NAHI.**

## 4. Openverse API — ❌ skip
- `api.openverse.org/v1/videos/` is VM se Cloudflare challenge par atka; phone par bhi bharosa nahi. Skip.

## ENGINE FAISLA (spike se nikla asal technical sach)
- MEER ka `composeVideo` ek **REMUXER** hai: pehle clip ka format poori video ka format ban jata hai,
  samples copy hote hain, transcode NAHI hota. Isi liye MEER library ke saare clips pehle se uniform 1080x1920 H.264 hain.
  Archive clips (mukhtalif codec/size: VP9 webm 720p, H.264 360p/720p...) is par **NAHI** chalenge.
- MEER me koi "koi bhi clip → 1080x1920" transcode engine **maujud NAHI** hai
  (renderCanvasVideo sirf canvas animation hai, 1280x720; Journey 1080x1920 sirf is liye hai ke library clips khud 1080x1920 hain).
- **Is liye Facts Maker me NAYA transcode engine likha jayega** (Java, MediaCodec):
  decoder (input mime ke hisaab se — H.264/VP9 jo bhi aye) → YUV frames → scale/crop portrait
  **1080x1920** → H.264 encoder → MediaMuxer; sath MEER wala hi wavToAac tareeqa audio ke liye.
  Yehi engine WebM ko bhi chala dega aur MP4 ko bhi — ek hi uniform output.
- Captions: isi engine me har frame par caption text YUV frame par draw hoga (engine ke andar),
  is liye burned captions v1 me mumkin hain (aur .srt bhi sath bane gi).

## v1 SOURCES (final)
1. **Wikimedia Commons** (primary — har topic) — original WebM download, transcode engine handle karega.
2. **NASA** (space/science par Commons ke sath merged results) — `~medium.mp4` seedha.
Dono keyless, dono CORS/API phone-friendly. "Badlo" button merged results me cycle karega.
