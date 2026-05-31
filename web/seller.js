const $ = (id) => document.getElementById(id);

let seller = null;
let cents = 0;
let pendingUri = null;
let pollTimer = null;
let expectedAmount = 0;
let baselineId = 0;

function fmtCents(c) {
  return '€ ' + (c / 100).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function show(name) {
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

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function getBaseline() {
  const res = await fetch('/api/seller/baseline');
  if (!res.ok) throw new Error('Could not read payment baseline');
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
  show('screen-done');
}

async function startNfc() {
  expectedAmount = cents / 100;
  pendingUri = buildPaytoUri(expectedAmount);
  $('nfc-amount').textContent = fmtCents(cents);
  $('nfc-status').textContent = 'Tap customer phone — PayTo will appear in the app chooser';
  show('screen-nfc');

  try {
    baselineId = await getBaseline();
  } catch (e) {
    $('nfc-status').textContent = e.message;
    return;
  }

  if (!('NDEFWriter' in window)) {
    $('nfc-status').textContent =
      'Web NFC not available (needs Chrome on Android + HTTPS/localhost)';
    startPolling();
    return;
  }

  try {
    const writer = new NDEFWriter();
    await writer.write({
      records: [{ recordType: 'url', data: pendingUri }],
    });
    $('nfc-status').textContent = 'Waiting for payment…';
    startPolling();
  } catch (e) {
    if (e.name === 'NotAllowedError') {
      $('nfc-status').textContent = 'NFC permission denied';
    } else if (e.name === 'NotSupportedError') {
      $('nfc-status').textContent = 'NFC not supported on this device';
    } else {
      $('nfc-status').textContent = e.message || 'NFC write failed';
    }
    startPolling();
  }
}

function cancelNfc() {
  stopPolling();
  clearAmount();
  show('screen-input');
}

function newSale() {
  stopPolling();
  clearAmount();
  pendingUri = null;
  show('screen-input');
}

async function loadSeller() {
  const res = await fetch('/api/seller');
  if (!res.ok) throw new Error('Could not load seller account');
  seller = await res.json();
  $('shop-name').textContent = seller.name;
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

loadSeller()
  .then(() => refreshDisplay())
  .catch((e) => { $('shop-name').textContent = e.message; });

document.addEventListener('keydown', (e) => {
  if ($('screen-input').hidden) return;
  if (e.key >= '0' && e.key <= '9') { digit(Number(e.key)); e.preventDefault(); }
  else if (e.key === 'Backspace') { backspace(); e.preventDefault(); }
  else if (e.key === 'Escape') { clearAmount(); e.preventDefault(); }
  else if (e.key === 'Enter' && cents > 0) { startNfc(); e.preventDefault(); }
});
