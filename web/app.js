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

function buildPaytoUri({ iban, name, amount, currency, message }) {
  const params = new URLSearchParams({
    amount: `${currency || 'EUR'}:${amount.toFixed(2)}`,
    'receiver-name': name,
  });
  if (message) params.set('message', message);
  return `payto://iban/${iban}?${params}`;
}

// --- UI ---

const $ = (id) => document.getElementById(id);
const screens = ['home', 'transfer', 'request', 'request-qr', 'payment', 'success'];
let me = null;
let appReady = false;
let paying = false;
let paymentErrors = [];
let currentScreen = 'home';

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
  currentScreen = name;
  screens.forEach((s) => { $(s).hidden = s !== name; });
  document.querySelector('.app').classList.toggle('is-paying', name === 'payment');
  updateNav(name);
}

function updateNav(name) {
  const map = { home: 'nav-home', transfer: 'nav-transfer', request: 'nav-request', 'request-qr': 'nav-request' };
  document.querySelectorAll('.nav-item').forEach((el) => el.classList.remove('nav-item-active'));
  const active = map[name];
  if (active) $(active)?.classList.add('nav-item-active');
}

function normalizeIban(iban) {
  return (iban || '').replace(/\s/g, '').toUpperCase();
}

function fmtIban(iban) {
  return iban ? iban.replace(/(.{4})/g, '$1 ').trim() : '—';
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

function fmtCentsDigits(c) {
  return (c / 100).toLocaleString('it-IT', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

const amountCents = { 'tf-amount': 0, 'rf-amount': 0 };

function refreshAmountInput(id) {
  const el = $(id);
  if (!el) return;
  el.value = amountCents[id] ? fmtCentsDigits(amountCents[id]) : '';
}

function resetAmountInput(id) {
  amountCents[id] = 0;
  refreshAmountInput(id);
}

function amountDigit(id, d) {
  if (amountCents[id] > 999_999_99) return;
  amountCents[id] = amountCents[id] * 10 + d;
  refreshAmountInput(id);
}

function amountBackspace(id) {
  amountCents[id] = Math.floor(amountCents[id] / 10);
  refreshAmountInput(id);
}

function amountFromField(id) {
  const c = amountCents[id];
  if (!c || c <= 0) return null;
  return c / 100;
}

function amountFromPaste(id, text) {
  const digits = (text || '').replace(/\D/g, '');
  if (!digits) return;
  amountCents[id] = Math.min(parseInt(digits, 10), 999_999_99);
  refreshAmountInput(id);
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

function setErrors(elId, errors) {
  const el = $(elId);
  if (!el) return;
  el.innerHTML = errors.map((e) => '<li>' + e + '</li>').join('');
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
  if ($('my-name')) $('my-name').textContent = me.name || '—';
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
  row.hidden = !(value && value !== '—');
}

function showPayment(p) {
  $('pay-from-name').textContent = me?.name || 'Il tuo conto';
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
    return;
  }
  if (paying) return;
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
  const origHtml = btn.innerHTML;
  btn.innerHTML = '<span class="btn-pay-icon" aria-hidden="true"></span> Invio…';

  try {
    const res = await apiFetch('/api/pay', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        toIban: p.iban,
        amount: p.amount.value,
        message: p.message || '',
        receiverName: p.receiverName || '',
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
      'Hai inviato ' + fmtMoney(p.amount.value, p.amount.currency) +
      ' a ' + (p.receiverName || fmtIban(p.iban)) + msg + '.';
    show('success');
  } catch (e) {
    const msg = e.name === 'AbortError'
      ? 'Server non raggiungibile — controlla la connessione'
      : (e.message || 'Pagamento non riuscito');
    showPayError(msg);
  } finally {
    paying = false;
    btn.innerHTML = origHtml;
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

// --- Transfer form ---

function validateTransferForm() {
  const iban = normalizeIban($('tf-iban').value);
  const name = ($('tf-name').value || '').trim();
  const amount = amountFromField('tf-amount');
  const message = ($('tf-message').value || '').trim();
  const errors = [];

  if (!iban) errors.push('Inserisci l\'IBAN del beneficiario');
  else if (!isValidIban(iban)) errors.push('IBAN non valido');
  else if (me && iban === me.iban) errors.push('Non puoi inviare denaro a te stesso');

  if (!name) errors.push('Inserisci l\'intestatario');
  if (!amount) errors.push('Inserisci un importo valido');

  setErrors('transfer-errors', errors);
  if (errors.length) return null;

  return { iban, name, amount, message, currency: me?.currency || 'EUR' };
}

function onTransferSubmit(e) {
  e.preventDefault();
  const data = validateTransferForm();
  if (!data) return;

  showPayment({
    authority: 'iban',
    iban: data.iban,
    bic: null,
    amount: {
      currency: data.currency,
      value: data.amount,
      formatted: data.amount.toFixed(2),
    },
    receiverName: data.name,
    message: data.message || null,
  });
}

// --- Request payment QR ---

function validateRequestForm() {
  const amount = amountFromField('rf-amount');
  const message = ($('rf-message').value || '').trim();
  const errors = [];

  if (!me) errors.push('Conto non caricato');
  if (!amount) errors.push('Inserisci un importo valido');

  setErrors('request-errors', errors);
  if (errors.length) return null;

  return { amount, message };
}

function renderRequestQr(uri) {
  const container = $('request-qr-canvas');
  container.innerHTML = '';
  QrCreator.render(
    {
      text: uri,
      radius: 0.2,
      ecLevel: 'M',
      fill: '#005c38',
      background: '#ffffff',
      size: 220,
    },
    container,
  );
}

function onRequestSubmit(e) {
  e.preventDefault();
  const data = validateRequestForm();
  if (!data || !me) return;

  const uri = buildPaytoUri({
    iban: me.iban,
    name: me.name,
    amount: data.amount,
    currency: me.currency,
    message: data.message,
  });

  $('rq-amount').textContent = fmtMoney(data.amount, me.currency);
  $('rq-beneficiary').textContent = me.name + ' · ' + fmtIban(me.iban);
  $('rq-uri').textContent = uri;
  renderRequestQr(uri);
  show('request-qr');
}

function amountFieldFromEvent(e) {
  const id = e.target?.id;
  if (id === 'tf-amount' || id === 'rf-amount') return id;
  const activeId = document.activeElement?.id;
  if (activeId === 'tf-amount' || activeId === 'rf-amount') return activeId;
  return null;
}

function onAmountKeydown(e) {
  const id = amountFieldFromEvent(e);
  if (!id) return;
  if (e.key >= '0' && e.key <= '9') {
    amountDigit(id, Number(e.key));
    e.preventDefault();
  } else if (e.key === 'Backspace' || e.key === 'Delete') {
    amountBackspace(id);
    e.preventDefault();
  } else if (e.key.length === 1 && !e.metaKey && !e.ctrlKey) {
    e.preventDefault();
  }
}

function onAmountPaste(e) {
  const id = e.target.id;
  if (id !== 'tf-amount' && id !== 'rf-amount') return;
  e.preventDefault();
  amountFromPaste(id, e.clipboardData?.getData('text') || '');
}

function onAmountInput(e) {
  const id = e.target.id;
  if (id !== 'tf-amount' && id !== 'rf-amount') return;
  refreshAmountInput(id);
}

function onIbanInput(e) {
  const el = e.target;
  if (el.id !== 'tf-iban') return;
  const pos = el.selectionStart;
  const raw = normalizeIban(el.value);
  el.value = fmtIban(raw);
  if (pos != null) el.setSelectionRange(el.value.length, el.value.length);

  if (raw.length >= 15) {
    lookupRecipient(raw).then((acc) => {
      if (acc && !$('tf-name').value.trim()) $('tf-name').value = acc.name;
    });
  }
}

function onAppClick(e) {
  const btn = e.target.closest('button');
  if (!btn) return;

  switch (btn.id) {
    case 'btn-edit-iban':
      e.preventDefault();
      editMyIban();
      break;
    case 'btn-transfer':
    case 'nav-transfer':
      e.preventDefault();
      resetAmountInput('tf-amount');
      show('transfer');
      break;
    case 'btn-request':
    case 'nav-request':
      e.preventDefault();
      resetAmountInput('rf-amount');
      show('request');
      break;
    case 'nav-home':
      e.preventDefault();
      goHome();
      break;
    case 'btn-transfer-back':
      e.preventDefault();
      show('home');
      break;
    case 'btn-request-back':
      e.preventDefault();
      show('home');
      break;
    case 'btn-request-qr-back':
      e.preventDefault();
      show('request');
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
$('transfer-form')?.addEventListener('submit', onTransferSubmit);
$('request-form')?.addEventListener('submit', onRequestSubmit);
document.addEventListener('input', onIbanInput);
document.addEventListener('input', onAmountInput);
document.addEventListener('keydown', onAmountKeydown);
$('tf-amount')?.addEventListener('paste', onAmountPaste);
$('rf-amount')?.addEventListener('paste', onAmountPaste);
boot();
