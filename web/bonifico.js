const $ = (id) => document.getElementById(id);

const STORAGE_KEY = 'payto-bonifico-config';
const LEGACY_STORAGE_KEY = 'payto-epc-config';
const CAUSALE_MAX = 140;

// EPC069-12 v3.1 (2024-03-19): version 002, UTF-8; INST = SEPA Instant (SCT Inst)
const EPC_VERSION = '002';
const EPC_CHARSET = '1';
const EPC_IDENT = 'INST';

let config = null;
let cents = 0;

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

function normalizeIban(raw) {
  return raw.replace(/\s/g, '').toUpperCase();
}

function normalizeCausale(raw) {
  return (raw || '').trim().slice(0, CAUSALE_MAX);
}

function isValidIban(iban) {
  if (!/^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$/.test(iban)) return false;
  const rearranged = iban.slice(4) + iban.slice(0, 4);
  const numeric = rearranged.replace(/[A-Z]/g, (ch) => (ch.charCodeAt(0) - 55).toString());
  let remainder = 0;
  for (const ch of numeric) {
    remainder = Number(String(remainder) + ch) % 97;
  }
  return remainder === 1;
}

function readStoredConfig() {
  let raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    raw = localStorage.getItem(LEGACY_STORAGE_KEY);
    if (raw) {
      localStorage.setItem(STORAGE_KEY, raw);
      localStorage.removeItem(LEGACY_STORAGE_KEY);
    }
  }
  return raw;
}

function loadConfig() {
  try {
    const raw = readStoredConfig();
    if (!raw) return null;
    const data = JSON.parse(raw);
    const iban = normalizeIban(data.iban || '');
    const name = (data.name || '').trim();
    if (!isValidIban(iban) || !name) return null;
    return {
      iban,
      name: name.slice(0, 70),
      causaleTemplate: normalizeCausale(data.causaleTemplate),
    };
  } catch {
    return null;
  }
}

function saveConfig(next) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  config = next;
}

function defaultCausale() {
  return config?.causaleTemplate || '';
}

function resetPaymentCausale() {
  $('input-causale').value = defaultCausale();
}

function paymentCausale() {
  return normalizeCausale($('input-causale').value);
}

function show(name) {
  for (const id of ['screen-setup', 'screen-input', 'screen-qr']) {
    $(id).hidden = id !== name;
  }
}

function updateHeader() {
  const configured = config !== null;
  $('btn-settings').hidden = !configured;
  $('iban-display').hidden = !configured;
  if (configured) {
    $('iban-display').textContent = fmtIban(config.iban);
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
  resetPaymentCausale();
  refreshDisplay();
}

function buildEpcPayload(amount, causale) {
  const lines = [
    'BCD',
    EPC_VERSION,
    EPC_CHARSET,
    EPC_IDENT,
    '',
    config.name,
    config.iban,
    `EUR${amount.toFixed(2)}`,
    '',
    '',
    causale,
  ];
  while (lines.length > 0 && lines[lines.length - 1] === '') {
    lines.pop();
  }
  return lines.join('\n');
}

function renderPaymentQr(payload) {
  QrCreator.render(
    {
      text: payload,
      radius: 0.2,
      ecLevel: 'M',
      fill: '#0d3b66',
      background: '#ffffff',
      size: 200,
    },
    $('payment-qr'),
  );
}

function showQr() {
  const amount = cents / 100;
  const causale = paymentCausale();
  $('qr-amount').textContent = fmtCents(cents);
  $('qr-iban').textContent = `${config.name} · ${fmtIban(config.iban)}`;
  const causaleEl = $('qr-causale');
  if (causale) {
    causaleEl.textContent = causale;
    causaleEl.hidden = false;
  } else {
    causaleEl.textContent = '';
    causaleEl.hidden = true;
  }
  renderPaymentQr(buildEpcPayload(amount, causale));
  show('screen-qr');
}

function cancelQr() {
  clearAmount();
  show('screen-input');
}

function openSetup(prefill = true) {
  if (prefill && config) {
    $('input-iban').value = fmtIban(config.iban);
    $('input-name').value = config.name;
    $('input-causale-template').value = config.causaleTemplate || '';
  } else {
    $('input-iban').value = '';
    $('input-name').value = '';
    $('input-causale-template').value = '';
  }
  $('setup-error').textContent = '';
  show('screen-setup');
}

function enterApp() {
  config = loadConfig();
  updateHeader();
  if (config) {
    clearAmount();
    show('screen-input');
  } else {
    openSetup(false);
  }
}

$('setup-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const iban = normalizeIban($('input-iban').value);
  const name = $('input-name').value.trim().slice(0, 70);
  const causaleTemplate = normalizeCausale($('input-causale-template').value);
  if (!isValidIban(iban)) {
    $('setup-error').textContent = 'IBAN non valido';
    return;
  }
  if (!name) {
    $('setup-error').textContent = 'Inserisci il nome del beneficiario';
    return;
  }
  saveConfig({ iban, name, causaleTemplate });
  updateHeader();
  clearAmount();
  show('screen-input');
});

document.querySelectorAll('.key').forEach((btn) => {
  btn.onclick = () => {
    const k = btn.dataset.key;
    if (k === 'clear') clearAmount();
    else if (k === 'back') backspace();
    else digit(Number(k));
  };
});

$('btn-enter').onclick = () => showQr();
$('btn-cancel').onclick = () => cancelQr();
$('btn-settings').onclick = () => openSetup();

document.addEventListener('keydown', (e) => {
  if ($('screen-input').hidden) return;
  if (e.target.matches('textarea, input')) return;
  if (e.key >= '0' && e.key <= '9') { digit(Number(e.key)); e.preventDefault(); }
  else if (e.key === 'Backspace') { backspace(); e.preventDefault(); }
  else if (e.key === 'Escape') { clearAmount(); e.preventDefault(); }
  else if (e.key === 'Enter' && cents > 0) { showQr(); e.preventDefault(); }
});

enterApp();
