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
let appReady = false;
let paying = false;
let paymentErrors = [];

async function apiFetch(url, opts = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 15000);
  try {
    return await fetch(url, { credentials: 'same-origin', signal: ctrl.signal, ...opts });
  } finally {
    clearTimeout(timer);
  }
}

function show(name) {
  screens.forEach((s) => { $(s).hidden = s !== name; });
  document.querySelector('.app').classList.toggle('is-paying', name === 'payment');
}

function normalizeIban(iban) {
  return (iban || '').replace(/\s/g, '').toUpperCase();
}

function fmtIban(iban) {
  return iban ? iban.replace(/(.{4})/g, '$1 ').trim() : '—';
}

function formatPayAmount(value, currency) {
  const parts = new Intl.NumberFormat('it-IT', {
    style: 'currency',
    currency: currency || 'EUR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).formatToParts(value);
  const symbol = parts.find((x) => x.type === 'currency')?.value || '';
  const digits = parts
    .filter((x) => x.type !== 'currency' && x.type !== 'literal')
    .map((x) => x.value)
    .join('')
    .trim();
  return { symbol, digits };
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
  const res = await apiFetch('/api/me');
  if (!res.ok) throw new Error('Impossibile caricare il conto');
  me = await res.json();
  refreshMeCard();
}

function refreshMeCard() {
  if (!me) return;
  $('balance').textContent = fmtMoney(me.balance, me.currency);
  $('my-iban').textContent = fmtIban(me.iban);
}

function setIbanStatus(msg, isError = false) {
  const el = $('iban-status');
  if (!el) return;
  el.textContent = msg || '';
  el.style.color = isError ? '#dc2626' : '#64748b';
}

async function editMyIban() {
  if (!me) return;
  const input = prompt('Inserisci un IBAN italiano (es. IT60X0542811101000000123456):', me.iban || '');
  if (input == null) return;
  const iban = normalizeIban(input);
  if (!iban) {
    setIbanStatus('Nessun IBAN inserito', true);
    return;
  }
  setIbanStatus('Aggiornamento in corso…');
  try {
    const res = await apiFetch('/api/me/iban', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ iban }),
    });
    const data = await res.json();
    if (!res.ok) {
      setIbanStatus(data.error || 'Impossibile aggiornare l\'IBAN', true);
      return;
    }
    me = data;
    refreshMeCard();
    if (!window._payment) $('home-status').textContent = '';
    setIbanStatus('IBAN aggiornato');
  } catch (e) {
    setIbanStatus(e.message || 'Errore di rete', true);
  }
}

async function lookupRecipient(iban) {
  const res = await apiFetch('/api/account?iban=' + encodeURIComponent(iban));
  if (!res.ok) return null;
  return res.json();
}

function setOptionalRow(id, value) {
  const row = $(id);
  if (!row) return;
  const showRow = Boolean(value && value !== '—');
  row.hidden = !showRow;
}

function showPayment(p) {
  $('pay-from-iban').textContent = me ? fmtIban(me.iban) : '—';
  $('f-iban').textContent = fmtIban(p.iban) || '—';
  $('f-bic').textContent = p.bic || '—';
  $('f-receiver').textContent = p.receiverName || 'Destinatario';
  $('f-message').textContent = p.message || '—';

  const amountEl = $('f-amount');
  if (p.amount) {
    const { symbol, digits } = formatPayAmount(p.amount.value, p.amount.currency);
    amountEl.innerHTML =
      '<span class="pay-amount-symbol">' + symbol + '</span>' +
      '<span class="pay-amount-value">' + digits + '</span>';
  } else {
    amountEl.textContent = '—';
  }

  setOptionalRow('row-bic', p.bic);
  setOptionalRow('row-message', p.message);

  const errors = [];
  if (p.authority !== 'iban') errors.push('Sono supportati solo pagamenti IBAN');
  if (!p.iban) errors.push('IBAN mancante');
  if (!p.amount || p.amount.value <= 0) errors.push('Importo mancante o non valido');
  if (me && p.iban === me.iban) errors.push('Non puoi pagare te stesso');

  if (p.iban && !p.receiverName) {
    lookupRecipient(p.iban).then((acc) => {
      if (acc) $('f-receiver').textContent = acc.name;
    });
  }

  paymentErrors = errors;
  $('errors').innerHTML = errors.map((e) => '<li>' + e + '</li>').join('');
  refreshPayButton();

  show('payment');
  window._payment = p;
  if (errors.length) $('errors').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function refreshPayButton() {
  const btn = $('btn-pay');
  if (!btn) return;
  if (!appReady) {
    btn.disabled = true;
    btn.textContent = 'Caricamento…';
    return;
  }
  if (paying) return;
  btn.textContent = 'Conferma pagamento';
  btn.disabled = paymentErrors.length > 0;
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

function showPayError(msg) {
  paymentErrors = [msg];
  $('errors').innerHTML = '<li>' + msg + '</li>';
  $('errors').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  refreshPayButton();
}

async function doPay() {
  if (paying || !appReady) return;
  const p = window._payment;
  if (!p?.iban || !p.amount) {
    showPayError('Pagamento non valido — riapri il link payto');
    return;
  }

  const btn = $('btn-pay');
  paying = true;
  btn.disabled = true;
  btn.textContent = 'Invio…';

  try {
    const res = await apiFetch('/api/pay', {
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
      showPayError(data.error || 'Pagamento non riuscito');
      return;
    }
    me.balance = data.balance;
    $('balance').textContent = fmtMoney(me.balance, me.currency);
    const msg = p.message ? ' — "' + p.message + '"' : '';
    $('success-text').textContent =
      'Hai inviato ' + p.amount.formatted + ' ' + p.amount.currency +
      ' a ' + fmtIban(p.iban) + msg + '.';
    show('success');
  } catch (e) {
    const msg = e.name === 'AbortError'
      ? 'Server non raggiungibile — controlla la connessione'
      : (e.message || 'Pagamento non riuscito');
    showPayError(msg);
  } finally {
    paying = false;
    refreshPayButton();
  }
}

function goHome() {
  window._payment = null;
  paymentErrors = [];
  paying = false;
  show('home');
  $('errors').innerHTML = '';
  $('home-status').textContent = '';
  history.replaceState(null, '', '/');
}

function onAppClick(e) {
  const btn = e.target.closest('button');
  if (!btn) return;
  switch (btn.id) {
    case 'btn-edit-iban':
      e.preventDefault();
      editMyIban();
      break;
    case 'btn-pay':
      e.preventDefault();
      doPay();
      break;
    case 'btn-cancel':
    case 'btn-done':
      goHome();
      break;
  }
}

function flushLaunches() {
  tryLaunch(location.href);
}

async function boot() {
  if ('launchQueue' in window) {
    launchQueue.setConsumer((p) => {
      if (p.targetURL) tryLaunch(p.targetURL);
    });
  }

  flushLaunches();

  try {
    await loadMe();
  } catch (e) {
    if (!window._payment) {
      $('home-status').textContent =
        e.name === 'AbortError'
          ? 'Server non raggiungibile — riprova tra poco'
          : (e.message || 'Impossibile caricare il conto');
      show('home');
    }
  }

  appReady = true;
  if (window._payment) showPayment(window._payment);
  refreshPayButton();
}

// --- boot ---

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');
document.querySelector('.app').addEventListener('click', onAppClick);
boot();
