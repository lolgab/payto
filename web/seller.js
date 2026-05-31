const $ = (id) => document.getElementById(id);

let seller = null;
let cents = 0;
let pendingUri = null;

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

async function startNfc() {
  const amount = cents / 100;
  pendingUri = buildPaytoUri(amount);
  $('nfc-amount').textContent = fmtCents(cents);
  $('nfc-status').textContent = 'Tap customer phone — PayTo will appear in the app chooser';
  show('screen-nfc');

  if (!('NDEFWriter' in window)) {
    $('nfc-status').textContent =
      'Web NFC not available (needs Chrome on Android + HTTPS/localhost)';
    return;
  }

  try {
    const writer = new NDEFWriter();
    await writer.write({
      records: [{ recordType: 'url', data: pendingUri }],
    });
    $('done-text').textContent = fmtCents(cents) + ' — tap customer phone to open PayTo';
    show('screen-done');
  } catch (e) {
    if (e.name === 'NotAllowedError') {
      $('nfc-status').textContent = 'NFC permission denied';
    } else if (e.name === 'NotSupportedError') {
      $('nfc-status').textContent = 'NFC not supported on this device';
    } else {
      $('nfc-status').textContent = e.message || 'NFC write failed';
    }
  }
}

function newSale() {
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
$('btn-cancel').onclick = () => { clearAmount(); show('screen-input'); };
$('btn-new').onclick = () => newSale();

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
