// What the Kotlin/Wasm app asks of the browser that Compose has no word for: storage, codecs,
// links, history, fonts and the player. Small on purpose; each call is wrapped so a blocked
// feature (private windows, old browsers) degrades instead of throwing into Wasm.
(function () {
  'use strict';

  const store = (() => {
    try {
      const k = '__tm_probe__';
      window.localStorage.setItem(k, '1');
      window.localStorage.removeItem(k);
      return window.localStorage;
    } catch (e) {
      const mem = new Map();
      return {
        getItem: (k) => (mem.has(k) ? mem.get(k) : null),
        setItem: (k, v) => mem.set(k, String(v)),
        removeItem: (k) => mem.delete(k),
      };
    }
  })();

  let probe = null;
  function videoProbe() {
    if (!probe) probe = document.createElement('video');
    return probe;
  }

  let popHandler = null;

  // The handlers of the notifications shown (notifyTap), by id; the Service Worker hands a press
  // back as a message with the id (sw.js, notificationclick).
  const taps = new Map();
  let tapSeq = 0;
  function runTap(id) {
    const f = taps.get(id);
    taps.delete(id);
    if (f) {
      try { f(); } catch (e) {}
    }
  }
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', (e) => {
      if (e.data && e.data.type === 'tm-notification-tap') runTap(String(e.data.id));
    });
  }

  // Installing: Chromium offers its own prompt, kept here until the app's Install button asks
  // for it; iOS has none, so the app shows how to add it to the Home Screen instead.
  let installPrompt = null;
  let installedNow = false;
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    installPrompt = e;
  });
  window.addEventListener('appinstalled', () => {
    installPrompt = null;
    installedNow = true;
  });

  // What lasts for this tab's session only (the coffee button's x): sessionStorage where the
  // browser allows it, else a map that lasts as long as the page.
  const sessionStore = (() => {
    try {
      const k = '__tm_probe__';
      window.sessionStorage.setItem(k, '1');
      window.sessionStorage.removeItem(k);
      return window.sessionStorage;
    } catch (e) {
      const mem = new Map();
      return {
        getItem: (k) => (mem.has(k) ? mem.get(k) : null),
        setItem: (k, v) => mem.set(k, String(v)),
      };
    }
  })();
  window.addEventListener('popstate', () => {
    // The player's own history entry (web/player/player.js) is not one of the app's screens.
    if (window.__tmSkipPop) {
      window.__tmSkipPop = false;
      return;
    }
    if (window.TmPlayer && window.TmPlayer.isOpen) return;
    if (popHandler) popHandler();
  });

  const TmHost = {
    // The keeper trims the metadata shelves here directly.
    rawStore: store,
    ready() {
      const s = document.getElementById('splash');
      if (s) {
        s.classList.add('gone');
        setTimeout(() => s.remove(), 300);
      }
    },
    splashNote(text) {
      const n = document.getElementById('splash-note');
      if (n) n.textContent = text;
    },

    getItem(key) {
      try {
        return store.getItem(key);
      } catch (e) {
        return null;
      }
    },
    // A full local storage never loses the write silently nor throws into Wasm: the storage
    // keeper (storage-keeper.js) frees the metadata answers and writes it again.
    setItem(key, value) {
      try {
        if (window.TmKeeper) window.TmKeeper.setItemKept(store, key, value);
        else store.setItem(key, value);
      } catch (e) {}
    },
    removeItem(key) {
      try {
        store.removeItem(key);
      } catch (e) {}
    },

    // "probably", "maybe" or "" as the element says, and the Media Source answer beside it.
    canPlay(mime) {
      try {
        return videoProbe().canPlayType(mime) || '';
      } catch (e) {
        return '';
      }
    },
    canSource(mime) {
      try {
        return !!(window.MediaSource && MediaSource.isTypeSupported(mime));
      } catch (e) {
        return false;
      }
    },

    async fetchText(url) {
      try {
        const r = await fetch(url, { credentials: 'omit' });
        if (!r.ok) return '';
        return await r.text();
      } catch (e) {
        return '';
      }
    },
    // A same origin binary (fonts) as base64, which Kotlin decodes in one pass.
    async fetchBase64(url) {
      try {
        const r = await fetch(url);
        if (!r.ok) return '';
        const bytes = new Uint8Array(await r.arrayBuffer());
        let s = '';
        for (let i = 0; i < bytes.length; i += 0x8000) {
          s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        }
        return btoa(s);
      } catch (e) {
        return '';
      }
    },
    // A cross origin JSON API (TMDB, TVmaze) that answers with CORS headers.
    async fetchJson(url, headersJson) {
      try {
        const headers = headersJson ? JSON.parse(headersJson) : {};
        const r = await fetch(url, { credentials: 'omit', headers: headers });
        return JSON.stringify({ status: r.status, body: await r.text() });
      } catch (e) {
        return JSON.stringify({ status: 0, body: '' });
      }
    },

    openUrl(url) {
      window.open(url, '_blank', 'noopener');
    },
    async copyText(text) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch (e) {
        return false;
      }
    },
    language() {
      return (navigator.languages && navigator.languages[0]) || navigator.language || 'en';
    },
    // Every language the browser is set to, in order, comma separated: the app takes the first it
    // has a catalog for, as the apps do with the system's list.
    languages() {
      const list = (navigator.languages && navigator.languages.length) ? navigator.languages : [navigator.language || 'en'];
      return list.join(',');
    },
    // The page's own lang and dir, and the splash line, in the language the app shows.
    setLanguage(tag, rtl) {
      const html = document.documentElement;
      html.setAttribute('lang', tag || 'en');
      html.setAttribute('dir', rtl ? 'rtl' : 'ltr');
    },
    userAgent() {
      return navigator.userAgent || '';
    },
    // The kind of device, for the "Get the app" link and the layout: phone, tablet or desktop.
    deviceKind() {
      const ua = navigator.userAgent || '';
      if (/iPhone|Android.+Mobile|Mobile Safari/i.test(ua)) return 'phone';
      if (/iPad|Android|Tablet/i.test(ua)) return 'tablet';
      return 'desktop';
    },
    platform() {
      const ua = navigator.userAgent || '';
      if (/Android/i.test(ua)) return 'android';
      if (/iPhone|iPad|iPod/i.test(ua)) return 'ios';
      if (/Windows/i.test(ua)) return 'windows';
      if (/Mac OS X|Macintosh/i.test(ua)) return 'macos';
      if (/Linux|X11/i.test(ua)) return 'linux';
      return 'other';
    },
    prefersDark() {
      try {
        return window.matchMedia('(prefers-color-scheme: dark)').matches;
      } catch (e) {
        return true;
      }
    },
    onDarkChange(handler) {
      try {
        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => handler(e.matches));
      } catch (e) {}
    },
    setThemeColor(color, dark) {
      // Both of index.html's theme colours (light and dark) take the app's own ground.
      document.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.setAttribute('content', color));
      document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
      // The ground around the canvas (the notch, the home indicator) matches the app's own.
      document.documentElement.style.setProperty('--bg', color);
    },
    setTitle(text) {
      document.title = text ? text + ' - TMPlayer Web' : 'TMPlayer Web';
    },

    // The browser's back button walks the app's own back stack: each screen pushes one entry.
    pushState(name) {
      try {
        history.pushState({ tm: name }, '', location.pathname + location.search);
      } catch (e) {}
    },
    onPop(handler) {
      popHandler = handler;
    },
    back() {
      history.back();
    },

    async persist() {
      try {
        if (window.TmKeeper) return await window.TmKeeper.persist('settings');
        if (navigator.storage && navigator.storage.persist) {
          return await navigator.storage.persist();
        }
      } catch (e) {}
      return false;
    },
    // Whether the browser has already agreed to keep the page's storage under pressure.
    async persisted() {
      try {
        if (navigator.storage && navigator.storage.persisted) return await navigator.storage.persisted();
      } catch (e) {}
      return false;
    },
    // A text file handed to the browser's downloads (a backup, the logs): the page has no folder.
    saveText(name, text, mime) {
      try {
        const url = URL.createObjectURL(new Blob([text], { type: mime || 'text/plain' }));
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
        return true;
      } catch (e) {
        return false;
      }
    },
    // A text file the viewer picks (a backup to restore): its text, or '' when nothing was
    // picked or it is larger than maxBytes.
    pickText(accept, maxBytes) {
      return new Promise((resolve) => {
        let done = false;
        const finish = (v) => {
          if (done) return;
          done = true;
          input.remove();
          resolve(v);
        };
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = accept || '';
        input.style.display = 'none';
        input.addEventListener('change', () => {
          const f = input.files && input.files[0];
          if (!f || f.size > maxBytes) return finish('');
          f.text().then(finish, () => finish(''));
        });
        input.addEventListener('cancel', () => finish(''));
        document.body.appendChild(input);
        input.click();
      });
    },
    async estimate() {
      try {
        const e = await navigator.storage.estimate();
        return JSON.stringify({ usage: e.usage || 0, quota: e.quota || 0 });
      } catch (e) {
        return JSON.stringify({ usage: 0, quota: 0 });
      }
    },

    notify(title, body) {
      try {
        if (!('Notification' in window) || Notification.permission !== 'granted') return false;
        new Notification(title, { body: body, icon: 'icon-192.png' });
        return true;
      } catch (e) {
        return false;
      }
    },
    // Whether the viewer is looking at the page: the new video checks notify only when not, as the
    // desktop's do while its window is not in front.
    inFront() {
      try {
        return document.visibilityState === 'visible' && document.hasFocus();
      } catch (e) {
        return true;
      }
    },
    // A notification whose press brings the page to the front and calls onTap. Through the Service
    // Worker where there is one (Chrome on Android has no page notifications, and an installed
    // iPhone app shows them only from there), else from the page.
    notifyTap(title, body, tag, onTap) {
      try {
        if (!('Notification' in window) || Notification.permission !== 'granted') return false;
        const id = String(++tapSeq);
        taps.set(id, onTap);
        if (taps.size > 20) taps.delete(taps.keys().next().value);
        const options = { body: body, icon: 'icon-192.png', badge: 'icon-192.png', tag: tag || id, data: { tmTap: id } };
        if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
          navigator.serviceWorker.ready
            .then((reg) => reg.showNotification(title, options))
            .catch(() => TmHost.notifyPage(title, options, id));
        } else {
          TmHost.notifyPage(title, options, id);
        }
        return true;
      } catch (e) {
        return false;
      }
    },
    notifyPage(title, options, id) {
      try {
        const n = new Notification(title, options);
        n.onclick = () => {
          try { window.focus(); } catch (e) {}
          n.close();
          runTap(id);
        };
      } catch (e) {}
    },
    async askNotify() {
      try {
        if (!('Notification' in window)) return 'unsupported';
        return await Notification.requestPermission();
      } catch (e) {
        return 'denied';
      }
    },
    notifyPermission() {
      try {
        return 'Notification' in window ? Notification.permission : 'unsupported';
      } catch (e) {
        return 'unsupported';
      }
    },

    isIos() {
      return /iPhone|iPad|iPod/.test(navigator.userAgent) ||
        (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    },
    // A phone or tablet in the hand: it cannot scan a QR code it is showing itself.
    isHandheld() {
      try {
        if (this.isIos() || /Android.+Mobile|Mobi/i.test(navigator.userAgent)) return true;
        if (navigator.userAgentData && navigator.userAgentData.mobile) return true;
        const coarse = window.matchMedia('(pointer: coarse)').matches && !window.matchMedia('(any-pointer: fine)').matches;
        return coarse && Math.min(screen.width, screen.height) < 820;
      } catch (e) {
        return false;
      }
    },
    isStandalone() {
      try {
        return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
      } catch (e) {
        return false;
      }
    },
    canInstall() {
      return installPrompt != null;
    },
    async install() {
      if (!installPrompt) return false;
      const p = installPrompt;
      installPrompt = null;
      p.prompt();
      try {
        const r = await p.userChoice;
        return r.outcome === 'accepted';
      } catch (e) {
        return false;
      }
    },
    // The app was installed while this page was open (Chromium's appinstalled).
    installedHere() {
      return installedNow;
    },
    // The platform the "Get the app" offer names: an iPad that says it is a Mac is iOS, a TV
    // browser on Android (Google TV, Fire TV, Android TV, a Bravia) is androidtv, and a
    // Chromebook is other, since TMPlayer has no ChromeOS build of its own.
    appPlatform() {
      try {
        const ua = navigator.userAgent || '';
        if (this.isIos()) return 'ios';
        if (/CrOS/i.test(ua)) return 'other';
        if (/Android/i.test(ua)) {
          return /Android TV|GoogleTV|Google TV|AFT[A-Z0-9]|BRAVIA|SMART-TV|SmartTV|\bTV\b|AndroidTV/i.test(ua) ? 'androidtv' : 'android';
        }
        if (/Windows/i.test(ua)) return 'windows';
        if (/Mac OS X|Macintosh/i.test(ua)) return 'macos';
        if (/Linux|X11/i.test(ua)) return 'linux';
      } catch (e) {}
      return 'other';
    },
    sessionGet(key) {
      try {
        return sessionStore.getItem(key);
      } catch (e) {
        return null;
      }
    },
    sessionSet(key, value) {
      try {
        sessionStore.setItem(key, value);
      } catch (e) {}
    },
    // `?tm-offer=...` forces the coffee button, the install offer or the coffee window for a
    // screenshot, on a development server only (localhost); the live site ignores it.
    offerDebug() {
      try {
        const h = location.hostname;
        if (h !== 'localhost' && h !== '127.0.0.1' && h !== '[::1]') return '';
        return new URLSearchParams(location.search).get('tm-offer') || '';
      } catch (e) {
        return '';
      }
    },

    coarsePointer() {
      try {
        return window.matchMedia('(pointer: coarse)').matches;
      } catch (e) {
        return false;
      }
    },
    finePointer() {
      try {
        return window.matchMedia('(any-pointer: fine)').matches;
      } catch (e) {
        return true;
      }
    },
    onPointerChange(handler) {
      try {
        window.matchMedia('(pointer: coarse)').addEventListener('change', () => handler());
        window.matchMedia('(any-pointer: fine)').addEventListener('change', () => handler());
      } catch (e) {}
    },

    registerWorker() {
      if (!('serviceWorker' in navigator)) return;
      navigator.serviceWorker.register('sw.js', { scope: './' }).catch((e) => {
        console.warn('TMPlayer: the service worker did not register', e);
      });
    },

    online() {
      return navigator.onLine !== false;
    },
    onOnline(handler) {
      window.addEventListener('online', () => handler(true));
      window.addEventListener('offline', () => handler(false));
    },
    onVisible(handler) {
      document.addEventListener('visibilitychange', () => handler(document.visibilityState === 'visible'));
    },
  };

  window.TmHost = TmHost;
})();
