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

  // Installing: Chromium offers its own prompt, kept here until the app's Install button asks
  // for it; iOS has none, so the app shows how to add it to the Home Screen instead.
  let installPrompt = null;
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    installPrompt = e;
  });
  window.addEventListener('appinstalled', () => {
    installPrompt = null;
  });
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
    setItem(key, value) {
      try {
        store.setItem(key, value);
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
      const m = document.querySelector('meta[name="theme-color"]');
      if (m) m.setAttribute('content', color);
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
        if (navigator.storage && navigator.storage.persist) {
          return await navigator.storage.persist();
        }
      } catch (e) {}
      return false;
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
