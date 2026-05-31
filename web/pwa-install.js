(function () {
  const scope = location.pathname.startsWith('/seller') ? '/seller' : '/';
  const storageKey = 'payto-pwa-dismissed:' + scope;
  const appName = scope === '/seller' ? 'PayTo Cassa' : 'PayTo Pagamenti';

  if (window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone) return;
  if (localStorage.getItem(storageKey)) return;

  let deferredPrompt = null;
  let iosMode = false;

  const overlay = document.createElement('div');
  overlay.className = 'pwa-install';
  overlay.innerHTML =
    '<div class="pwa-install__card" role="dialog" aria-labelledby="pwa-install-title" aria-modal="true">' +
    '<p id="pwa-install-title" class="pwa-install__title">Installa ' + appName + '</p>' +
    '<p id="pwa-install-text" class="pwa-install__text"></p>' +
    '<div class="pwa-install__actions">' +
    '<button type="button" class="pwa-install__btn pwa-install__btn--ghost" data-action="dismiss">Più tardi</button>' +
    '<button type="button" class="pwa-install__btn pwa-install__btn--primary" data-action="install">Installa</button>' +
    '</div></div>';

  const textEl = overlay.querySelector('#pwa-install-text');
  const installBtn = overlay.querySelector('[data-action="install"]');

  function dismiss() {
    localStorage.setItem(storageKey, '1');
    overlay.classList.remove('is-visible');
    setTimeout(() => overlay.remove(), 250);
  }

  function show() {
    document.body.appendChild(overlay);
    requestAnimationFrame(() => overlay.classList.add('is-visible'));
  }

  function setChromeMode() {
    textEl.textContent =
      'Aggiungi l\'app alla schermata Home per pagare più velocemente e usarla a schermo intero.';
    installBtn.textContent = 'Installa';
    installBtn.hidden = false;
  }

  function setIosMode() {
    iosMode = true;
    textEl.textContent =
      'Per installare l\'app: tocca Condividi (□↑) e poi "Aggiungi a Home".';
    installBtn.textContent = 'Ho capito';
  }

  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) dismiss();
  });

  overlay.querySelector('[data-action="dismiss"]').onclick = dismiss;

  installBtn.onclick = async () => {
    if (iosMode || !deferredPrompt) {
      dismiss();
      return;
    }
    deferredPrompt.prompt();
    await deferredPrompt.userChoice;
    deferredPrompt = null;
    dismiss();
  };

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    setChromeMode();
    setTimeout(show, 600);
  });

  const isIos =
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  if (isIos) {
    setIosMode();
    setTimeout(show, 800);
  }
})();
