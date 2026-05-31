# PayTo — Trusted Web Activity (TWA)

Due app Android separate (stesso progetto Gradle):

| Modulo | Package | APK | PWA |
|--------|---------|-----|-----|
| `app` | `it.payto.wallet` | PayTo | `/` — pagamenti cliente |
| `seller-app` | `it.payto.seller` | PayTo Cassa | `/seller` — cassa esercente |

Entrambe aprono la PWA a **schermo intero** via Chrome Trusted Web Activity. Il wallet ha handler nativo per `payto://`; la cassa apre solo `/seller`.

Il QR e il tag NFC contengono lo stesso `payto://iban/…` — Android apre il wallet come per un deep link (intent filter + protocol handler).

## Architettura (wallet)

```
payto://iban/…  →  PaytoLauncherActivity (TWA)
                        ↓
                   https://payto.fly.dev/?uri=payto%3A%2F%2F…  (solo internamente)
                        ↓
                   PWA client (Chrome, fullscreen)
```

Usa [android-browser-helper](https://github.com/GoogleChrome/android-browser-helper) (`LauncherActivity` + protocol handler `payto`).

## Prerequisiti

- Android SDK
- JDK 17+
- Chrome installato sul dispositivo

## Build

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r seller-app/build/outputs/apk/debug/seller-app-debug.apk
```

## Release CI (GitHub Actions)

Ogni push su `main` compila l'APK release e lo pubblica in [GitHub Releases](https://github.com/lolgab/payto/releases).

- Workflow: `.github/workflows/android-release.yml`
- Tag: `apk-v<run_number>` (es. `apk-v42`)
- Artifact: `payto-wallet.apk`, `payto-cassa.apk`
- `versionCode` = numero di run GitHub Actions

### Keystore di firma

Senza secret configurati, CI genera un keystore `release.keystore` (cache persistente) con password `payto-ci`. Dopo la **prima** run, copia l'impronta SHA-256 dalla release note in `web/.well-known/assetlinks.json`.

Opzionale — secret del repository per un keystore personalizzato:

| Secret | Descrizione |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | Keystore JKS codificato in base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Password keystore |
| `ANDROID_KEY_ALIAS` | Alias chiave |
| `ANDROID_KEY_PASSWORD` | Password chiave (se diversa) |

Generare il base64:

```bash
base64 -i release.keystore | pbcopy
```

## Digital Asset Links (obbligatorio per TWA fullscreen)

Per nascondere la barra del browser serve la verifica bidirezionale app ↔ sito.

### 1. Impronta certificato

```bash
./gradlew printSigningCertSha256
```

Copia la riga `SHA256:` (solo hex, con i `:`).

### 2. Aggiorna assetlinks.json

In `web/.well-known/assetlinks.json` sostituisci `REPLACE_WITH_SHA256_FROM_gradlew_printSigningCertSha256` con l'impronta del keystore usato per firmare gli APK (stessa impronta per `it.payto.wallet` e `it.payto.seller`; debug per test locali, release/upload key per produzione).

### 3. Deploy del server

Il file deve essere raggiungibile su:

```
https://payto.fly.dev/.well-known/assetlinks.json
```

Verifica con [Statement List Generator and Tester](https://developers.google.com/digital-asset-links/tools/generator).

### Debug locale

Su emulatore/telefono con server HTTP locale la verifica TWA **non** passa: l'app usa fallback **WebView** (configurato in `build.gradle.kts`). Il flusso `payto://` funziona comunque.

## Server locale (telefono fisico)

```bash
./mill run
adb reverse tcp:8080 tcp:8080
```

`android/local.properties`:

```properties
sdk.dir=/percorso/Android/sdk
payto.serverUrl=http://127.0.0.1:8080
```

## Test deep link

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "payto://iban/DE75512108001245126199?amount=EUR:42.50&receiver-name=Negozio+Demo"
```

## Release

```bash
./gradlew assembleRelease
```

- Wallet: `https://payto.fly.dev/`
- Cassa: `https://payto.fly.dev/seller`
- Fallback: WebView a schermo intero (se la verifica TWA fallisce, es. su Realme/Xiaomi)
- Aggiorna `assetlinks.json` con l'impronta del keystore di **release** prima del deploy (entrambi i package)
