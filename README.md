# PayTo — pagamenti in negozio con bonifico SEPA Instant

PayTo propone uno **standard decentralizzato** per pagare nei negozi usando il **bonifico SEPA Instant** (SCT Inst): niente circuiti chiusi, niente POS dedicato, niente commissioni aggiuntive per chi ha già un conto con **bonifici a zero costo**.

L’esercente espone un **IBAN** e un importo; il cliente conferma un bonifico istantaneo dalla propria app bancaria. I soldi arrivano sul conto del negozio come un normale accredito SEPA, in pochi secondi.

Questo repository contiene una **implementazione di riferimento** (PWA + app Android) che dimostra il flusso end-to-end. Lo standard in sé è aperto: qualsiasi banca, wallet o cassa può aderire senza chiedere permesso a un ente centrale.

## Perché `payto://`

Oggi i pagamenti in negozio passano quasi sempre da **reti a commissione** (carte, wallet chiusi). Il bonifico istantaneo europeo è già interoperabile tra banche, ma mancava un modo **semplice e universale** per dire «paga questo IBAN, questo importo, a questo beneficiario» — da QR, da link, da NFC, da email.

Lo schema URI **`payto://`** risolve questo problema. È definito dallo standard IETF **[RFC 8905 — The *payto* URI Scheme for Payments](https://www.rfc-editor.org/rfc/rfc8905)**.

Un pagamento verso conto corrente in euro si esprime così:

```text
payto://iban/IT60X0542811101000000123456?amount=EUR:12.50&receiver-name=Caff%C3%A8+Roma&message=Ordine+42
```

Altri esempi con IBAN italiani:

```text
payto://iban/IT86P0329601600000000003419?amount=EUR:3.20&receiver-name=Panificio+Milano&message=Cornetti
payto://iban/IT35Q0542811101000000123488?amount=EUR:48.00&receiver-name=Farmacia+Duomo&message=Scontrino+A17
```

| Parte | Significato |
|--------|-------------|
| `iban` | *Authority* RFC 8905: destinazione tramite IBAN (eventualmente `BIC/IBAN` nel path) |
| path | IBAN del beneficiario (senza spazi) |
| `amount` | `VALUTA:importo` (es. `EUR:12.50`) |
| `receiver-name` | Nome visualizzato del beneficiario |
| `message` | Causale / riferimento pagamento |

L’app che gestisce il link **non deve inventare** i dati del bonifico: li legge dall’URI, li mostra all’utente e avvia un **bonifico SEPA Instant** (o SCT ordinario, se l’utente sceglie) dopo conferma esplicita, come richiesto dal RFC per la sicurezza.

> **Nota:** nel RFC la forma canonica è `payto://`; su Android e nelle PWA si registra lo schema `payto`. Varianti come `web+payto://` sono previste per i browser.

## Architettura decentralizzata

```text
  Negozio                         Cliente                    Banca del cliente
  ───────                         ───────                    ─────────────────
  Cassa genera payto://iban/…  →  QR / NFC / link      →   App banca apre
  (QR, tag NFC, HCE)              Cliente conferma            schermata bonifico
                                                              precompilata
                                        │
                                        ▼
                              Bonifico SEPA Instant
                              (rete SCT Inst, ~secondi)
                                        │
                                        ▼
                              Accredito su IBAN negozio
```

- **Nessun intermediario obbligatorio** oltre al sistema bancario europeo già esistente.
- **Zero commissioni PayTo**: il costo dipende solo dal conto del cliente e dell’esercente (molte banche offrono bonifici SEPA gratuiti).
- **Stesso URI ovunque**: QR in cassa, link su fattura, tag NFC, tap phone-to-phone.

## Implementazione di riferimento in questo repo

| Componente | Ruolo |
|------------|--------|
| [`web/`](web/) | PWA cassa (`/seller`) e demo wallet; parser `payto://` ([RFC 8905](https://www.rfc-editor.org/rfc/rfc8905)) in [`web/app.js`](web/app.js) |
| [`android/`](android/) | App Android wallet e cassa (TWA); vedi [`android/README.md`](android/README.md) |
| [`web/bonifico.html`](web/bonifico.html) | Generatore QR **EPC** (SEPA Instant) per banche che supportano già lo standard EPC069-12 — complementare a `payto://` |

Deploy demo: [https://payto.fly.dev](https://payto.fly.dev)

## Screenshot app

### Wallet (cliente)

![Wallet PayTo](docs/screenshots/wallet-app.svg)

### Cassa (esercente)

![Cassa PayTo](docs/screenshots/seller-app.svg)

## Come una banca può supportare `payto://` (facile)

L’integrazione minima è **registrare l’app come handler** dello schema `payto` e, alla ricezione dell’URI, aprire la schermata di bonifico con campi precompilati.

### 1. Parsing (RFC 8905)

Per l’authority `iban`:

- Path con un segmento: `payto://iban/IT…`
- Path con due segmenti: `payto://iban/BIC/IT…` (BIC opzionale)
- Query: almeno `amount`, `receiver-name`, `message` (tutti opzionali ma consigliati in cassa)

Validare IBAN, importo e lunghezza causale secondo le regole SEPA. **Non eseguire mai** un bonifico senza schermata di conferma utente (RFC 8905, § sicurezza).

### 2. Avviare il bonifico SEPA Instant

Dopo la conferma, instradare il pagamento su **SCT Inst** quando disponibile (stesso conto/destinazione, servizio istantaneo della banca).

### 3. Registrazione handler per piattaforma

**Android** — `AndroidManifest.xml` dell’app bancaria:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="payto" />
</intent-filter>
```

Opzionale: filtrare per authority `iban` con `android:host="iban"` se si vogliono ignorare altri tipi `payto` (crypto, ecc.).

**iOS** — dichiarare lo URL scheme `payto` in `Info.plist` e gestire `application(_:open:options:)` (o Universal Links se la banca espone una pagina web intermedia).

**PWA / browser** — Web App Manifest:

```json
"protocol_handlers": [
  { "protocol": "payto", "url": "/pagamento?uri=%s" }
]
```

Come in [`web/manifest.webmanifest`](web/manifest.webmanifest). Chrome installerà la PWA tra le app che possono aprire link `payto:`.

**Desktop** — registrare il protocollo `payto` a livello OS (es. handler custom URL su Windows/macOS/Linux) che apre l’app desktop o una pagina sicura.

### 4. Test rapido (Android)

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "payto://iban/IT60X0542811101000000123456?amount=EUR:1.00&receiver-name=Test"
```

Se l’app bancaria compare tra i gestori del link, l’integrazione di base funziona.

### 5. Cosa non serve alla banca

- Nessun contratto con PayTo o con questo repository.
- Nessun server proprietario: l’URI contiene già tutto il necessario per il bonifico.
- Nessuna sostituzione del flusso antifrode e di strong customer authentication già previsti dalla PSD2.

## `payto://` su NFC

Il QR e il NFC devono trasportare **lo stesso URI** `payto://iban/…`. Il cliente avvicina il telefono; il sistema operativo consegna l’URI all’app bancaria scelta dall’utente.

### Cosa fa il negozio (emissione)

1. La cassa costruisce l’URI (importo, IBAN, nome esercente).
2. Lo espone in uno di questi modi:
   - **QR** con il testo dell’URI (o immagine che lo codifica);
   - **Tag NFC fisico** (NDEF URI record con `payto://…`);
   - **Phone-to-phone Android**: emulazione **HCE** che espone un record NDEF URI — come nell’app cassa di questo repo ([`SellerNfcBridge`](android/seller-app/src/main/kotlin/it/payto/seller/SellerNfcBridge.kt)).

Il record NDEF rilevante è di tipo **URI** (RTD URI well-known), payload = stringa `payto://iban/…` (eventualmente con prefisso di abbreviazione URI standard NFC Forum).

### Cosa deve fare la banca (ricezione NFC)

1. **Permesso NFC** nell’app (`android.permission.NFC`) e dichiarazione `uses-feature` NFC (non obbligatorio su tutti i device).

2. **Intent filter `NDEF_DISCOVERED`** con lo stesso schema `payto` usato per i deep link:

```xml
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="payto" />
</intent-filter>
```

È lo stesso pattern dell’app wallet di riferimento in [`android/app/src/main/AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml).

3. In `onCreate` / `onNewIntent`, leggere `intent.data` (Android risolve spesso l’NDEF URI direttamente in un `payto://…`) oppure parsare i record NDEF grezzi se necessario.

4. Passare l’URI al **medesimo parser** usato per QR e link web → schermata bonifico precompilata → conferma → SEPA Instant.

5. **Non serve HCE lato banca**: il telefono del cliente è il **lettore** NFC; la cassa (o il tag) è l’**emettore**. La banca implementa solo la parte reader + pagamento.

### Flusso phone-to-phone (Android)

```text
  Telefono cassa (HCE)              Telefono cliente
  ────────────────────              ────────────────
  Emula tag NDEF con payto://…  →   NFC legge URI
                                    NDEF_DISCOVERED
                                    App banca / wallet payto
```

Su iOS il supporto dipende dalle API NFC in lettura (NDEF) e dalla registrazione degli URL scheme; il principio resta: **un URI `payto://` letto dal tag o dal peer**.

## Confronto con QR EPC (opzionale)

Molte banche italiane/europee leggono già il **QR EPC** (standard EPC069-12, servizio `INST` per SEPA Instant). È un formato parallelo, non sostitutivo di RFC 8905:

| | `payto://` (RFC 8905) | QR EPC |
|--|------------------------|--------|
| Supporto | Handler URI universale (app, OS, web) | Principalmente app bancarie con scanner EPC |
| NFC | NDEF URI record | Meno comune su NFC consumer |
| Estensibilità | Authority diverse oltre `iban` | Bonifico SEPA classico |

Una banca può supportare **entrambi** con lo stesso backend di bonifico precompilato.

## Contribuire

- Segnalazioni e PR su questo repository.
- Per l’app Android: [`android/README.md`](android/README.md).
- Specifica normativa: **[RFC 8905](https://www.rfc-editor.org/rfc/rfc8905)**.

## Licenza

Vedi il repository per i dettagli sulla licenza del codice di riferimento.
