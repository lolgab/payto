// --- payto parser (RFC 8905) ---

function parsePayto(uri) {
  let s = uri.trim();
  if (s.startsWith('web+payto://')) s = 'payto://' + s.slice(12);
  if (s.startsWith('payto:') && !s.startsWith('payto://')) s = 'payto://' + s.slice(6).replace(/^\/+/, '');
  if (!s.startsWith('payto://')) throw new Error('URI payto non valida');

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
    if (!m) throw new Error('Importo non valido');
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
  const cur = currency || 'EUR';
  return Number(amount).toLocaleString('it-IT', {
    style: 'currency',
    currency: cur,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
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
  if (p.authority !== 'iban') errors.push('In questa demo sono supportati solo pagamenti IBAN');
  if (!p.iban) errors.push('IBAN mancante');
  if (!p.amount || p.amount.value <= 0) errors.push('Importo mancante o non valido');
  if (me && p.iban === me.iban) errors.push('Non puoi pagare te stesso');

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
    if (location.search) history.replaceState(null, '', '/');
  } catch (e) {
    $('home-status').textContent = e.message;
    show('home');
  }
}

function extractPaytoUri(raw) {
  if (!raw) return null;
  if (raw.startsWith('payto:') || raw.startsWith('web+payto:')) return raw;
  try {
    const u = new URL(raw, location.origin);
    const q = u.searchParams.get('uri');
    if (q) return q;
    if (u.protocol === 'payto:' || u.protocol === 'web+payto:') return u.href;
  } catch (_) {}
  return null;
}

function tryLaunch(raw) {
  const uri = extractPaytoUri(raw);
  if (uri) handleUri(uri);
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
    $('errors').innerHTML = '<li>' + (data.error || 'Pagamento non riuscito') + '</li>';
    return;
  }
  me.balance = data.balance;
  $('balance').textContent = fmtMoney(me.balance, me.currency);
  const msg = p.message ? ' — "' + p.message + '"' : '';
  $('success-text').textContent =
    'Hai inviato ' + p.amount.formatted + ' ' + p.amount.currency +
    ' a ' + fmtIban(p.iban) + msg + '.';
  show('success');
}

// --- boot ---

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');
if ('launchQueue' in window) {
  launchQueue.setConsumer((p) => { if (p.targetURL) tryLaunch(p.targetURL); });
}

loadMe().then(() => tryLaunch(location.href));

$('btn-demo').onclick = () =>
  handleUri('payto://iban/DE75512108001245126199?amount=EUR:42.50&message=Caff%C3%A8&receiver-name=Negozio+Demo');
$('btn-pay').onclick = () => doPay().catch((e) => { $('errors').innerHTML = '<li>' + e.message + '</li>'; });
$('btn-cancel').onclick = () => { show('home'); history.replaceState(null, '', '/'); };
$('btn-done').onclick = () => { show('home'); history.replaceState(null, '', '/'); };
