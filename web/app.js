// --- payto parser (RFC 8905) ---

function parsePayto(uri) {
  let s = uri.trim();
  if (s.startsWith('web+payto://')) s = 'payto://' + s.slice(12);
  if (s.startsWith('payto:') && !s.startsWith('payto://')) s = 'payto://' + s.slice(6).replace(/^\/+/, '');
  if (!s.startsWith('payto://')) throw new Error('Not a payto URI');

  const rest = s.slice(8);
  const q = rest.indexOf('?');
  const pathPart = q >= 0 ? rest.slice(0, q) : rest;
  const query = q >= 0 ? rest.slice(q + 1) : '';

  const slash = pathPart.indexOf('/');
  const authority = (slash >= 0 ? pathPart.slice(0, slash) : pathPart).toLowerCase();
  const path = slash >= 0 ? pathPart.slice(slash + 1) : '';
  const segments = path ? path.split('/').map(decodeURIComponent) : [];

  const opts = {};
  for (const pair of query.split('&').filter(Boolean)) {
    const eq = pair.indexOf('=');
    const k = decodeURIComponent(eq >= 0 ? pair.slice(0, eq) : pair).toLowerCase();
    const v = decodeURIComponent(eq >= 0 ? pair.slice(eq + 1) : '');
    opts[k] = v;
  }

  const payment = {
    authority,
    iban: null,
    bic: null,
    amount: null,
    receiverName: opts['receiver-name'] || null,
    message: opts.message || null,
  };

  if (authority === 'iban') {
    if (segments.length === 1) payment.iban = segments[0].replace(/\s/g, '').toUpperCase();
    else if (segments.length === 2) {
      payment.bic = segments[0].replace(/\s/g, '').toUpperCase();
      payment.iban = segments[1].replace(/\s/g, '').toUpperCase();
    }
  }

  if (opts.amount) {
    const m = opts.amount.match(/^([A-Za-z]+):([\d,]+)(?:\.([\d,]+))?$/);
    if (!m) throw new Error('Invalid amount');
    const frac = (m[3] || '').replace(/,/g, '');
    payment.amount = {
      currency: m[1].toUpperCase(),
      value: Number(m[2].replace(/,/g, '') + '.' + (frac || '0')),
      formatted: m[2].replace(/,/g, '') + (frac ? '.' + frac : ''),
    };
  }

  return payment;
}

// --- UI ---

const $ = (id) => document.getElementById(id);
const screens = ['home', 'payment', 'success'];
let me = null;

function show(name) {
  screens.forEach((s) => { $(s).hidden = s !== name; });
}

function fmtIban(iban) {
  return iban ? iban.replace(/(.{4})/g, '$1 ').trim() : '—';
}

function fmtMoney(amount, currency) {
  return '€ ' + Number(amount).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    + (currency && currency !== 'EUR' ? ' ' + currency : '');
}

async function loadMe() {
  const res = await fetch('/api/me');
  me = await res.json();
  $('balance').textContent = fmtMoney(me.balance, me.currency);
  $('my-iban').textContent = fmtIban(me.iban);
}

async function lookupRecipient(iban) {
  const res = await fetch('/api/account?iban=' + encodeURIComponent(iban));
  if (!res.ok) return null;
  return res.json();
}

function showPayment(p) {
  $('f-iban').textContent = fmtIban(p.iban) || '—';
  $('f-bic').textContent = p.bic || '—';
  $('f-amount').textContent = p.amount
    ? p.amount.formatted + ' ' + p.amount.currency
    : '—';
  $('f-receiver').textContent = p.receiverName || '—';
  $('f-message').textContent = p.message || '—';

  const errors = [];
  if (p.authority !== 'iban') errors.push('Only IBAN payments supported in this POC');
  if (!p.iban) errors.push('Missing IBAN');
  if (!p.amount || p.amount.value <= 0) errors.push('Missing or invalid amount');
  if (me && p.iban === me.iban) errors.push('Cannot pay yourself');

  if (p.iban && !p.receiverName) {
    lookupRecipient(p.iban).then((acc) => {
      if (acc) $('f-receiver').textContent = acc.name;
    });
  }

  $('errors').innerHTML = errors.map((e) => '<li>' + e + '</li>').join('');
  $('btn-pay').disabled = errors.length > 0;

  show('payment');
  window._payment = p;
}

function handleUri(raw) {
  try {
    showPayment(parsePayto(raw));
  } catch (e) {
    $('nfc-status').textContent = e.message;
    show('home');
  }
}

async function readNfc() {
  if (!('NDEFReader' in window)) {
    $('nfc-status').textContent = 'Web NFC not available (needs Chrome on Android + HTTPS/localhost)';
    return;
  }
  $('nfc-status').textContent = 'Hold an NFC tag near your device…';
  const reader = new NDEFReader();
  await reader.scan();
  reader.onreading = (e) => {
    for (const r of e.message.records) {
      const text = new TextDecoder(r.encoding || 'utf-8').decode(r.data);
      if (text.includes('payto')) { handleUri(text.trim()); return; }
    }
    $('nfc-status').textContent = 'No payto URI on tag';
  };
}

async function doPay() {
  const p = window._payment;
  const res = await fetch('/api/pay', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      toIban: p.iban,
      amount: p.amount.value,
      message: p.message || '',
    }),
  });
  const data = await res.json();
  if (!res.ok || !data.ok) {
    $('errors').innerHTML = '<li>' + (data.error || 'Payment failed') + '</li>';
    return;
  }
  me.balance = data.balance;
  $('balance').textContent = fmtMoney(me.balance, me.currency);
  $('success-text').textContent =
    'Sent ' + p.amount.formatted + ' ' + p.amount.currency +
    ' to ' + fmtIban(p.iban) + (p.message ? ' — "' + p.message + '"' : '') + '.';
  show('success');
}

// --- boot ---

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');
if ('launchQueue' in window) launchQueue.setConsumer((p) => { if (p.targetURL) handleUri(p.targetURL); });

loadMe().then(() => {
  const uri = new URLSearchParams(location.search).get('uri');
  if (uri) handleUri(uri);
});

$('btn-demo').onclick = () =>
  handleUri('payto://iban/DE75512108001245126199?amount=EUR:42.50&message=Coffee&receiver-name=Demo+Shop');

$('btn-nfc-read').onclick = () => readNfc().catch((e) => { $('nfc-status').textContent = e.message; });
$('btn-pay').onclick = () => doPay().catch((e) => { $('errors').innerHTML = '<li>' + e.message + '</li>'; });
$('btn-cancel').onclick = () => { show('home'); history.replaceState(null, '', '/'); };
$('btn-done').onclick = () => { show('home'); history.replaceState(null, '', '/'); };
