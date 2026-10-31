# Meer Facts Maker

Free facts / explainer videos **on the phone** — no credits, no paid APIs, no accounts.

**How it works:** type or pick a script (one fact per line) → the app finds a free archive
clip for each line (Wikimedia Commons + NASA) → free Kokoro voice inside the app reads each
line → clips are transcoded to 1080x1920, trimmed to the voice, captions + watermark burned
in (both with ON/OFF toggles) → one finished MP4 in Gallery (Movies/MeerFacts).

- App source: `android/` (package `com.hamza.factsmaker`, version 1.0)
- Online script bank: `scripts-bank.json` in this repo root — the app fetches it fresh
  (cache-busted) on every "Nayi Script Lo" tap, so new scripts appear **without an app
  update**. Used scripts are tracked per device and never repeat.
- The sherpa-onnx AAR is not stored here; builds download `sherpa-onnx-2.0.1.aar` from the
  official k2-fsa release (SHA-256 a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7…).
- The facts UI/logic is one self-contained module in
  `android/app/src/main/assets/www/index.html` (FACTS MODULE — START/END) so the same
  feature can be lifted into the MEER app later; the bank JSON is shared by both apps.
- Release APK: tag `v1.0-apk`.
