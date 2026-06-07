const $ = (id) => document.getElementById(id);

let seller = null;
let cents = 0;
let pendingUri = null;
let pollTimer = null;
let expectedAmount = 0;
let baselineId = 0;
let nativeNfcActive = false;

function fmtCents(c) {
  return (c / 100).toLocaleString('it-IT', {
    style: 'currency',
    currency: 'EUR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function fmtIban(iban) {
  return iban.replace(/\s/g, '').replace(/(.{4})/g, '$1 ').trim();
}

function normalizeIban(iban) {
  return (iban || '').replace(/\s/g, '').toUpperCase();
}

function setSellerIbanStatus(msg, isError = false) {
  const el = $('seller-iban-status');
  if (!el) return;
  el.textContent = msg || '';
  el.style.color = isError ? '#dc2626' : '#64748b';
}

function refreshSellerAccount() {
  if (!seller) return;
  $('shop-name').textContent = seller.name;
  $('seller-iban').textContent = fmtIban(seller.iban);
}

function show(name) {
  // Safety net: NFC must be active only on the QR/NFC screen.
  if (name !== 'screen-nfc') stopNativeNfc();
  for (const id of ['screen-input', 'screen-nfc', 'screen-done']) {
    $(id).hidden = id !== name;
  }
}

function refreshDisplay() {
  $('amount-display').textContent = fmtCents(cents);
  $('btn-enter').disabled = cents <= 0;
}

function digit(d) {
  if (cents > 999_999_99) return;
  cents = cents * 10 + d;
  refreshDisplay();
}

function backspace() {
  cents = Math.floor(cents / 10);
  refreshDisplay();
}

function clearAmount() {
  cents = 0;
  refreshDisplay();
}

function buildPaytoUri(amount) {
  const params = new URLSearchParams({
    amount: `${seller.currency}:${amount.toFixed(2)}`,
    'receiver-name': seller.name,
  });
  return `payto://iban/${seller.iban}?${params}`;
}

function renderPaymentQr(uri) {
  QrCreator.render(
    {
      text: uri,
      radius: 0.2,
      ecLevel: 'M',
      fill: '#0d3b66',
      background: '#ffffff',
      size: 200,
    },
    $('payment-qr'),
  );
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function getBaseline() {
  const res = await fetch('/api/seller/baseline');
  if (!res.ok) throw new Error('Impossibile leggere lo stato dei pagamenti');
  const data = await res.json();
  return data.lastId;
}

async function checkPayment() {
  const res = await fetch(
    `/api/seller/payment?after=${baselineId}&amount=${expectedAmount}`,
  );
  if (!res.ok) return;
  const data = await res.json();
  if (data.paid) showPaymentReceived(data);
}

function startPolling() {
  stopPolling();
  pollTimer = setInterval(() => {
    checkPayment().catch(() => {});
  }, 1500);
  checkPayment().catch(() => {});
}

function showPaymentReceived(data) {
  stopPolling();
  $('done-amount').textContent = fmtCents(Math.round(data.amount * 100));
  $('done-from-name').textContent = data.fromName || '—';
  $('done-from-iban').textContent = data.fromIban ? fmtIban(data.fromIban) : '—';
  show('screen-done');
}

function invokeSellerApp(path) {
  // Navigazione main-frame: WebView intercetta payto-seller:// (iframe non funziona).
  window.location.href = `payto-seller://${path}`;
}

function startNativeNfc(uri) {
  nativeNfcActive = true;
  invokeSellerApp('nfc?uri=' + encodeURIComponent(uri));
}

function stopNativeNfc() {
  if (!nativeNfcActive) return;
  nativeNfcActive = false;
  invokeSellerApp('nfc-stop');
}

function syncNativeNfc() {
  const onNfcScreen = !$('screen-nfc').hidden;
  if (onNfcScreen && pendingUri) startNativeNfc(pendingUri);
  else stopNativeNfc();
}

async function startNfc() {
  expectedAmount = cents / 100;
  pendingUri = buildPaytoUri(expectedAmount);
  $('nfc-amount').textContent = fmtCents(cents);
  renderPaymentQr(pendingUri);
  $('nfc-status').textContent =
    'Avvicina il telefono del cliente — PayTo apparirà tra le app disponibili';
  show('screen-nfc');

  try {
    baselineId = await getBaseline();
  } catch (e) {
    $('nfc-status').textContent = e.message;
    return;
  }

  // Emulazione NFC nativa (HCE): unico modo affidabile phone-to-phone su Android.
  // Web NFC write() serve solo per tag fisici, non per avvicinare due telefoni.
  startNativeNfc(pendingUri);
  $('nfc-status').textContent = 'In attesa del pagamento…';
  startPolling();
}

function cancelNfc() {
  stopNativeNfc();
  stopPolling();
  clearAmount();
  show('screen-input');
}

function newSale() {
  stopNativeNfc();
  stopPolling();
  clearAmount();
  pendingUri = null;
  show('screen-input');
}

async function loadSeller() {
  const res = await fetch('/api/seller');
  if (!res.ok) throw new Error('Impossibile caricare il conto venditore');
  seller = await res.json();
  refreshSellerAccount();
}

async function editSellerIban() {
  if (!seller) return;
  const input = prompt('Inserisci un IBAN italiano per la cassa:', seller.iban || '');
  if (input == null) return;
  const iban = normalizeIban(input);
  if (!iban) {
    setSellerIbanStatus('Nessun IBAN inserito', true);
    return;
  }
  setSellerIbanStatus('Aggiornamento in corso…');
  try {
    const res = await fetch('/api/seller/iban', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ iban }),
    });
    const data = await res.json();
    if (!res.ok) {
      setSellerIbanStatus(data.error || 'Impossibile aggiornare l\'IBAN', true);
      return;
    }
    seller = data;
    refreshSellerAccount();
    setSellerIbanStatus('IBAN aggiornato');
  } catch (e) {
    setSellerIbanStatus(e.message || 'Errore di rete', true);
  }
}

document.querySelectorAll('.key').forEach((btn) => {
  btn.onclick = () => {
    const k = btn.dataset.key;
    if (k === 'clear') clearAmount();
    else if (k === 'back') backspace();
    else digit(Number(k));
  };
});

$('btn-enter').onclick = () => startNfc();
$('btn-cancel').onclick = () => cancelNfc();
$('screen-done').onclick = () => newSale();
$('btn-edit-seller-iban').onclick = () => editSellerIban();

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/seller/sw.js');

loadSeller()
  .then(() => refreshDisplay())
  .catch((e) => {
    $('shop-name').textContent = e.message;
    setSellerIbanStatus(e.message, true);
  });

document.addEventListener('keydown', (e) => {
  if ($('screen-input').hidden) return;
  if (e.key >= '0' && e.key <= '9') { digit(Number(e.key)); e.preventDefault(); }
  else if (e.key === 'Backspace') { backspace(); e.preventDefault(); }
  else if (e.key === 'Escape') { clearAmount(); e.preventDefault(); }
  else if (e.key === 'Enter' && cents > 0) { startNfc(); e.preventDefault(); }
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') syncNativeNfc();
  else stopNativeNfc();
});

window.addEventListener('pagehide', () => stopNativeNfc());
