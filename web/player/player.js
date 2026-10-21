// TMPlayer Web's player (CP92). One overlay with TMPlayer's controls over one of two engines:
//
//  - native: the browser's own <video>, fed by the Service Worker's byte ranges (sw.js). Used when
//    the browser says it plays the container and the codecs: MP4 and WebM almost everywhere, and on
//    Apple devices also HEVC and AC-3 / E-AC-3 in MP4. It is the lightest on battery and the only
//    one with the system's picture in picture, AirPlay, Cast, the lock screen and, on an iPhone,
//    full screen.
//  - movi: movi-player (FFmpeg in WebAssembly with WebCodecs, drawing on a canvas, the sound
//    through Web Audio), fed straight from TDLib by TgSource. Plays what the browser cannot: MKV,
//    AVI, MPEG-TS, HEVC without a hardware decoder, AC-3, E-AC-3, DTS and TrueHD sound. When
//    WebCodecs cannot decode the picture it switches to FFmpeg's software decoders and carries on
//    from the same moment.
//
// The order is native when canPlayType says so, else movi with WebCodecs, else movi in software;
// "needs the app" only for what neither can show (picture subtitles, a picture too heavy to
// decode in software). Strings come from the app's catalog through window.TmStrings.format, keys
// from desktop/.../PlayerKeys.kt.
//
//   TmPlayer.open(specJson, onEvent)   onEvent(jsonText): progress, close, ended, next, previous,
//                                      prefs, error
//   TmPlayer.close()
//   TmPlayer.isOpen
(function () {
  'use strict';

  const ua = navigator.userAgent;
  const IOS = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const IPHONE = /iPhone|iPod/.test(ua);
  const STANDALONE = (window.matchMedia && matchMedia('(display-mode: standalone)').matches) || navigator.standalone === true;
  const MAC = /Mac/.test(navigator.platform || ua);
  const coarse = () => window.matchMedia && matchMedia('(pointer: coarse)').matches;

  const SPEEDS = [0.25, 0.5, 0.75, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3];
  const SEEK_SHORT = 5;
  const SEEK_MEDIUM = 10;
  const SEEK_LONG = 60;
  const SEEK_HUGE = 300;

  function T(key, args) {
    try {
      if (window.TmStrings) return window.TmStrings.format(key, args ? JSON.stringify(args) : '');
    } catch (e) {}
    return key;
  }

  function clock(seconds) {
    if (!isFinite(seconds) || seconds < 0) seconds = 0;
    const s = Math.floor(seconds % 60);
    const m = Math.floor(seconds / 60) % 60;
    const h = Math.floor(seconds / 3600);
    const two = (n) => String(n).padStart(2, '0');
    return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${m}:${two(s)}`;
  }

  function shapeKey(shape) {
    return shape === 'crop' ? 'player.scale_crop' : shape === 'stretch' ? 'player.scale_stretch' : 'player.scale_fit';
  }

  const ICONS = {
    back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    play: 'M8 5v14l11-7z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    replay10: 'M11.99 5V1l-5 5 5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6h-2c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8zm-1.1 11h-.85v-3.26l-1.01.31v-.69l1.77-.63h.09V16zm4.28-1.76c0 .32-.03.6-.1.82s-.17.42-.29.57-.28.26-.45.33-.37.1-.59.1-.41-.03-.59-.1-.33-.18-.46-.33-.23-.34-.3-.57-.11-.5-.11-.82v-.74c0-.32.03-.6.1-.82s.17-.42.29-.57.28-.26.45-.33.37-.1.59-.1.41.03.59.1.33.18.46.33.23.34.3.57.11.5.11.82v.74zm-.85-.86c0-.19-.01-.35-.04-.48s-.07-.23-.12-.31-.11-.14-.19-.17-.16-.05-.25-.05-.18.02-.25.05-.14.09-.19.17-.09.18-.12.31-.04.29-.04.48v.97c0 .19.01.35.04.48s.07.24.12.32.11.14.19.17.16.05.25.05.18-.02.25-.05.14-.09.19-.17.09-.19.11-.32.04-.29.04-.48v-.97z',
    forward10: 'M18 13c0 3.31-2.69 6-6 6s-6-2.69-6-6 2.69-6 6-6v4l5-5-5-5v4c-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8h-2zm-7.46 2.22c-.06.05-.12.09-.2.12s-.17.04-.27.04c-.09 0-.17-.01-.25-.04s-.14-.06-.2-.11-.1-.1-.13-.17-.05-.14-.05-.22h-.85c0 .21.04.39.12.55s.19.28.33.38.29.18.46.23.35.07.53.07c.21 0 .41-.03.6-.08s.34-.14.48-.24.24-.24.32-.39.12-.33.12-.53c0-.23-.06-.44-.18-.61s-.3-.3-.54-.39c.1-.05.2-.1.28-.17s.15-.14.2-.22.1-.16.13-.25.04-.18.04-.27c0-.2-.04-.37-.11-.53s-.17-.28-.3-.38-.28-.18-.46-.23-.37-.08-.59-.08c-.19 0-.38.03-.54.08s-.32.13-.44.23-.23.21-.3.34-.11.29-.11.45h.85c0-.07.02-.14.05-.2s.07-.11.12-.15.11-.07.18-.1.14-.03.22-.03c.1 0 .18.01.25.04s.13.06.18.11.08.11.11.17.04.14.04.22c0 .18-.05.32-.16.43s-.26.16-.48.16h-.43v.66h.45c.11 0 .2.01.29.04s.16.06.22.11.11.12.14.2.05.18.05.29c0 .09-.01.17-.04.24s-.08.11-.13.17zm3.9-3.44c-.18-.07-.37-.1-.59-.1s-.41.03-.59.1-.33.18-.45.33-.23.34-.29.57-.1.5-.1.82v.74c0 .32.04.6.11.82s.17.42.3.57.28.26.46.33.37.1.59.1.41-.03.59-.1.33-.18.45-.33.22-.34.29-.57.1-.5.1-.82v-.74c0-.32-.04-.6-.11-.82s-.17-.42-.3-.57-.29-.26-.46-.33zm.01 2.57c0 .19-.01.35-.04.48s-.06.24-.11.32-.11.14-.19.17-.16.05-.25.05-.18-.02-.25-.05-.14-.09-.19-.17-.09-.19-.12-.32-.04-.29-.04-.48v-.97c0-.19.01-.35.04-.48s.06-.23.12-.31.11-.14.19-.17.16-.05.25-.05.18.02.25.05.14.09.19.17.09.18.12.31.04.29.04.48v.97z',
    next: 'M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z',
    prev: 'M6 6h2v12H6zm3.5 6l8.5 6V6z',
    volume: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z',
    muted: 'M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z',
    subtitles: 'M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z',
    audio: 'M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z',
    settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
    fullscreen: 'M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z',
    fullscreenExit: 'M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z',
    pip: 'M19 7h-8v6h8V7zm2-4H3c-1.1 0-2 .9-2 2v14c0 1.1.9 1.98 2 1.98h18c1.1 0 2-.88 2-1.98V5c0-1.1-.9-2-2-2zm0 16.01H3V4.98h18v14.03z',
    cast: 'M21 3H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11z',
    speed: 'M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z',
  };

  function icon(name) {
    return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICONS[name]}"/></svg>`;
  }

  function button(name, label, onClick, extraClass) {
    const b = document.createElement('button');
    b.className = 'tm-btn' + (extraClass ? ' ' + extraClass : '');
    b.type = 'button';
    b.innerHTML = icon(name);
    b.setAttribute('aria-label', label);
    if (!coarse()) b.title = label;
    b.addEventListener('click', (e) => {
      e.stopPropagation();
      onClick(e);
    });
    return b;
  }

  // --- deciding the engine ---------------------------------------------------------------

  const probe = document.createElement('video');
  function can(type) {
    try {
      return probe.canPlayType(type);
    } catch (e) {
      return '';
    }
  }

  /** What the file name says about its codecs: release names state them. */
  function markers(name) {
    const n = name.toLowerCase();
    return {
      hevc: /(hevc|x265|h\.?265)/.test(n),
      av1: /\bav1\b/.test(n),
      ac3: /(\bac-?3\b|\beac-?3\b|\bddp?\b|dd\+|ddp?\d|dd5|dd2|dolby|atmos)/.test(n),
      dts: /\bdts\b|dts-?hd|dts-?x/.test(n),
      truehd: /truehd|true-hd/.test(n),
      tenBit: /10-?bit|hdr|hdr10|dv\b|dolby.?vision/.test(n),
      uhd: /2160p|\b4k\b|uhd/.test(n),
    };
  }

  /** 'native' or 'movi', from the container, the browser's own answers and the name's markers. */
  function choose(spec) {
    const name = spec.name || '';
    const ext = (name.split('.').pop() || '').toLowerCase();
    const mime = spec.mime || '';
    const mp4 = ext === 'mp4' || ext === 'm4v' || ext === 'mov' || mime === 'video/mp4' || mime === 'video/quicktime';
    const webm = ext === 'webm' || mime === 'video/webm';
    if (spec.source.ids.length > 1 && !mp4 && !webm) return 'movi';
    if (!mp4 && !webm) return 'movi';
    const type = webm ? 'video/webm' : (mime === 'video/quicktime' ? 'video/quicktime' : 'video/mp4');
    if (!can(type)) return 'movi';
    const m = markers(name);
    if (m.hevc && !can('video/mp4; codecs="hvc1.1.6.L120.90"') && !can('video/mp4; codecs="hev1.1.6.L120.90"')) return 'movi';
    if (m.av1 && !can('video/mp4; codecs="av01.0.08M.08"')) return 'movi';
    if ((m.ac3 && !can('audio/mp4; codecs="ec-3"') && !can('audio/mp4; codecs="ac-3"')) || m.dts || m.truehd) return 'movi';
    return 'native';
  }

  // --- engines ----------------------------------------------------------------------------

  // Where this script was served from: movi-player sits beside it, under movi/.
  const BASE = (document.currentScript && document.currentScript.src) || new URL('player/player.js', document.baseURI).href;

  let moviLoading = null;
  function loadMovi() {
    if (!moviLoading) {
      const url = new URL('movi/element.slim.js', BASE).href;
      moviLoading = import(url).then(() => customElements.whenDefined('movi-player'));
    }
    return moviLoading;
  }

  class NativeEngine {
    constructor() {
      const v = document.createElement('video');
      v.playsInline = true;
      v.setAttribute('playsinline', '');
      v.setAttribute('webkit-playsinline', '');
      v.preload = 'auto';
      v.crossOrigin = null;
      this.el = v;
      this.kind = 'native';
    }

    load(spec) {
      const s = spec.source;
      const mime = encodeURIComponent(spec.mime || 'video/mp4');
      this.el.src = s.ids.length === 1
        ? `stream/${s.ids[0]}?size=${s.sizes[0]}&mime=${mime}`
        : `stream/j?ids=${s.ids.join(',')}&sizes=${s.sizes.join(',')}&mime=${mime}`;
      if (spec.startSec > 0) {
        const at = spec.startSec;
        this.el.addEventListener('loadedmetadata', () => {
          try {
            this.el.currentTime = at;
          } catch (e) {}
        }, { once: true });
      }
      this.el.load();
    }

    audioTracks() {
      const list = this.el.audioTracks;
      if (!list || list.length === undefined) return [];
      const out = [];
      for (let i = 0; i < list.length; i++) {
        const t = list[i];
        out.push({ id: t.id || String(i), label: t.label, lang: t.language, enabled: t.enabled });
      }
      return out;
    }

    selectAudio(id) {
      const list = this.el.audioTracks;
      if (!list) return;
      for (let i = 0; i < list.length; i++) list[i].enabled = (list[i].id || String(i)) === id;
    }

    subtitleTracks() {
      const list = this.el.textTracks;
      const out = [];
      if (!list) return out;
      for (let i = 0; i < list.length; i++) {
        const t = list[i];
        if (t.kind !== 'subtitles' && t.kind !== 'captions') continue;
        out.push({ id: String(i), label: t.label, lang: t.language, enabled: t.mode === 'showing', text: true });
      }
      return out;
    }

    selectSubtitle(id) {
      const list = this.el.textTracks;
      if (!list) return;
      for (let i = 0; i < list.length; i++) list[i].mode = String(i) === id ? 'showing' : 'disabled';
    }

    setSubtitleDelay() {}

    canPip() {
      const v = this.el;
      // Picture in picture is broken in a Home Screen web app on iOS (WebKit bug 303885).
      if (IOS && STANDALONE) return false;
      if (v.webkitSupportsPresentationMode) return v.webkitSupportsPresentationMode('picture-in-picture');
      return !!document.pictureInPictureEnabled;
    }

    pip() {
      const v = this.el;
      if (document.pictureInPictureElement) return document.exitPictureInPicture();
      if (v.requestPictureInPicture) return v.requestPictureInPicture();
      if (v.webkitSetPresentationMode) v.webkitSetPresentationMode(v.webkitPresentationMode === 'picture-in-picture' ? 'inline' : 'picture-in-picture');
    }

    canCast() {
      return !!(this.el.remote && this.el.remote.prompt) || !!window.WebKitPlaybackTargetAvailabilityEvent;
    }

    cast() {
      const v = this.el;
      if (v.webkitShowPlaybackTargetPicker) return v.webkitShowPlaybackTargetPicker();
      if (v.remote) return v.remote.prompt().catch(() => {});
    }

    chapters() {
      return [];
    }

    dispose() {
      try {
        this.el.pause();
        this.el.removeAttribute('src');
        this.el.load();
      } catch (e) {}
    }
  }

  class MoviEngine {
    constructor(software) {
      const el = document.createElement('movi-player');
      el.setAttribute('wasmurl', new URL('movi/movi.wasm', BASE).href);
      el.setAttribute('nohotkeys', '');
      el.setAttribute('playsinline', '');
      el.setAttribute('noerrorscreen', '');
      el.setAttribute('controlslist', 'nospinner nocenterplay');
      el.setAttribute('spinnerdelay', '0.5');
      el.setAttribute('subtitleedge', 'outline');
      if (software) el.setAttribute('sw', '');
      this.el = el;
      this.kind = 'movi';
      this.software = !!software;
    }

    load(spec) {
      this.spec = spec;
      if (spec.startSec > 0) this.el.setAttribute('startat', String(spec.startSec));
      this.el.sourceAdapter = new window.TgSource(spec.source, 'tg:' + spec.key);
    }

    get player() {
      return this.el.player || null;
    }

    audioTracks() {
      const p = this.player;
      if (!p || !p.getAudioTracks) return [];
      const enabled = new Set();
      try {
        for (const t of this.el.audioTracks || []) if (t.enabled) enabled.add(String(t.trackId != null ? t.trackId : t.id));
      } catch (e) {}
      return p.getAudioTracks().map((t, i) => ({
        id: String(t.id),
        label: t.label || t.title || '',
        lang: t.language || '',
        codec: t.codec,
        channels: t.channels,
        enabled: enabled.size ? enabled.has(String(t.id)) : i === 0,
      }));
    }

    selectAudio(id) {
      const p = this.player;
      if (p && p.selectAudioTrack) p.selectAudioTrack(Number(id));
    }

    subtitleTracks() {
      const p = this.player;
      if (!p || !p.getSubtitleTracks) return [];
      return p.getSubtitleTracks().map((t) => ({
        id: String(t.id),
        label: t.label || t.title || '',
        lang: t.language || '',
        enabled: !!this.subtitle && this.subtitle === String(t.id),
        text: t.subtitleType !== 'image',
      }));
    }

    selectSubtitle(id) {
      const p = this.player;
      this.subtitle = id;
      if (p && p.selectSubtitleTrack) p.selectSubtitleTrack(id == null ? null : Number(id));
    }

    setSubtitleDelay(seconds) {
      try {
        this.el.setSubtitleDelay(seconds);
      } catch (e) {}
    }

    canPip() {
      if (IOS && STANDALONE) return false;
      return !!document.pictureInPictureEnabled || 'documentPictureInPicture' in window;
    }

    pip() {
      if (document.pictureInPictureElement) return document.exitPictureInPicture();
      return this.el.requestPictureInPicture().catch(() => {});
    }

    canCast() {
      return false;
    }

    cast() {}

    chapters() {
      try {
        const p = this.player;
        return (p && p.getChapters ? p.getChapters() : []).map((c) => ({ title: c.title || '', start: c.start || 0 }));
      } catch (e) {
        return [];
      }
    }

    dispose() {
      try {
        this.el.pause();
      } catch (e) {}
      try {
        this.el.dispose();
      } catch (e) {}
    }
  }

  // --- the player -------------------------------------------------------------------------

  class Player {
    constructor(spec, onEvent) {
      this.spec = spec;
      this.emit = (o) => {
        try {
          onEvent(JSON.stringify(o));
        } catch (e) {}
      };
      this.prefs = Object.assign({ speed: 1, volume: 1, muted: false, subtitleDelay: 0, boost: false, night: false, shape: 'fit' }, spec.prefs || {});
      this.engine = null;
      this.closed = false;
      this.showRemaining = false;
      this.idleTimer = 0;
      this.progressTimer = 0;
      this.wakeLock = null;
      this.tried = new Set();
      this.build();
      this.bindKeys();
      this.mediaSession();
      const first = spec.engine || choose(spec);
      this.start(first, spec.startSec || 0);
    }

    // DOM ---------------------------------------------------------------------------------

    build() {
      const root = document.createElement('div');
      root.id = 'tm-player';
      root.setAttribute('role', 'dialog');
      root.setAttribute('aria-label', this.spec.title || '');
      root.tabIndex = -1;
      if (coarse()) root.classList.add('tm-touch');
      root.innerHTML = `
        <div class="tm-stage"></div>
        <div class="tm-dim"></div>
        <div class="tm-subs" aria-live="polite"></div>
        <div class="tm-ripple tm-left"></div><div class="tm-ripple tm-right"></div>
        <div class="tm-spinner" role="progressbar"></div>
        <div class="tm-status"></div>
        <div class="tm-osd" role="status" aria-live="polite"></div>
        <div class="tm-chrome">
          <div class="tm-top">
            <div class="tm-slot-back"></div>
            <div class="tm-titles"><div class="tm-title"></div><div class="tm-subtitle"></div></div>
            <div class="tm-slot-top"></div>
          </div>
          <div class="tm-center"></div>
          <div class="tm-bottom">
            <div class="tm-seek" role="slider" tabindex="0">
              <div class="tm-track"></div><div class="tm-buffered"></div><div class="tm-played"></div>
              <div class="tm-ticks"></div><div class="tm-handle"></div><div class="tm-hover-time"></div>
            </div>
            <div class="tm-row tm-controls"></div>
          </div>
        </div>
        <div class="tm-skip"></div>
        <div class="tm-menu" role="menu"></div>
        <div class="tm-error" role="alert"><h2></h2><p></p><div class="tm-actions"></div></div>`;
      this.root = root;
      const q = (s) => root.querySelector(s);
      this.stage = q('.tm-stage');
      this.dim = q('.tm-dim');
      this.subs = q('.tm-subs');
      this.status = q('.tm-status');
      this.osdEl = q('.tm-osd');
      this.menu = q('.tm-menu');
      this.seek = q('.tm-seek');
      this.played = q('.tm-played');
      this.bufferedBar = q('.tm-buffered');
      this.handle = q('.tm-handle');
      this.hoverTime = q('.tm-hover-time');
      this.ticks = q('.tm-ticks');
      this.errorBox = q('.tm-error');
      this.skipBox = q('.tm-skip');
      q('.tm-title').textContent = this.spec.title || '';
      q('.tm-subtitle').textContent = this.spec.subtitle || '';

      q('.tm-slot-back').append(button('back', T('common.back'), () => this.close()));
      const top = q('.tm-slot-top');
      this.pipBtn = button('pip', T('player.picture_in_picture'), () => this.engine && this.engine.pip());
      this.castBtn = button('cast', T('cast.button'), () => this.engine && this.engine.cast());
      this.settingsBtn = button('settings', T('player.playback_options'), () => this.openMenu('main'));
      top.append(this.pipBtn, this.castBtn, this.settingsBtn);

      const center = q('.tm-center');
      this.backBtn = button('replay10', T('player.seek_seconds', { seconds: SEEK_MEDIUM }), () => this.seekBy(-SEEK_MEDIUM));
      this.bigPlay = button('play', T('player.play'), () => this.togglePlay());
      this.fwdBtn = button('forward10', T('player.seek_seconds', { seconds: SEEK_MEDIUM }), () => this.seekBy(SEEK_MEDIUM));
      center.append(this.backBtn, this.bigPlay, this.fwdBtn);

      const row = q('.tm-controls');
      this.playBtn = button('play', T('player.play'), () => this.togglePlay());
      this.prevBtn = button('prev', T('player.previous_episode'), () => this.emit({ type: 'previous', pos: this.time(), dur: this.duration() }));
      this.nextBtn = button('next', T('player.next_episode'), () => this.emit({ type: 'next', pos: this.time(), dur: this.duration() }));
      this.prevBtn.hidden = !this.spec.hasPrevious;
      this.nextBtn.hidden = !this.spec.hasNext;
      this.muteBtn = button('volume', T('player.mute_hint'), () => this.toggleMute());
      this.volume = document.createElement('input');
      this.volume.type = 'range';
      this.volume.min = '0';
      this.volume.max = '1';
      this.volume.step = '0.05';
      this.volume.className = 'tm-volume';
      this.volume.setAttribute('aria-label', T('keys.row_volume'));
      this.volume.addEventListener('input', () => this.setVolume(Number(this.volume.value)));
      this.volume.addEventListener('click', (e) => e.stopPropagation());
      // A phone's volume is its own buttons'; a slider there only fights them.
      if (coarse()) {
        this.volume.hidden = true;
        this.muteBtn.hidden = IOS;
      }
      this.timeEl = document.createElement('div');
      this.timeEl.className = 'tm-time';
      this.timeEl.addEventListener('click', (e) => {
        e.stopPropagation();
        this.showRemaining = !this.showRemaining;
        this.paintTime();
      });
      const spacer = document.createElement('div');
      spacer.className = 'tm-spacer';
      this.speedBtn = button('speed', T('player.speed'), () => this.openMenu('speed'));
      this.audioBtn = button('audio', T('player.audio'), () => this.openMenu('audio'));
      this.subsBtn = button('subtitles', T('player.subtitles'), () => this.openMenu('subtitles'));
      this.fsBtn = button('fullscreen', T('player.fullscreen_hint'), () => this.toggleFullscreen());
      row.append(this.playBtn, this.prevBtn, this.nextBtn, this.muteBtn, this.volume, this.timeEl, spacer,
        this.speedBtn, this.audioBtn, this.subsBtn, this.fsBtn);

      this.bindSeek();
      this.bindPointer();
      document.body.append(root);
      root.focus({ preventScroll: true });
      history.pushState({ tmPlayer: true }, '');
      this.onPop = () => {
        if (!this.closed) this.close(true);
      };
      window.addEventListener('popstate', this.onPop);
      this.onFs = () => this.paintFullscreen();
      document.addEventListener('fullscreenchange', this.onFs);
      document.addEventListener('webkitfullscreenchange', this.onFs);
      this.onVisibility = () => {
        if (document.visibilityState === 'hidden') this.report('progress');
      };
      document.addEventListener('visibilitychange', this.onVisibility);
      this.wake();
    }

    // Engines -------------------------------------------------------------------------------

    async start(kind, at, software) {
      const spec = Object.assign({}, this.spec, { startSec: at });
      this.tried.add(kind + (software ? '-sw' : ''));
      this.root.classList.add('tm-waiting');
      this.root.classList.remove('tm-failed');
      this.setStatus(T('player.opening_video'));
      if (this.engine) {
        this.engine.dispose();
        this.engine.el.remove();
        this.engine = null;
      }
      let engine;
      if (kind === 'movi') {
        try {
          this.setStatus(T('web.loading_player'));
          await loadMovi();
        } catch (e) {
          // The decoder could not load (an old browser, no WebAssembly memory left): the
          // browser's own player is the last try.
          if (!this.tried.has('native')) return this.start('native', at);
          return this.fail('codec');
        }
        if (this.closed) return;
        engine = new MoviEngine(software);
      } else {
        engine = new NativeEngine();
      }
      this.engine = engine;
      this.stage.append(engine.el);
      this.wire(engine);
      engine.load(spec);
      this.applyPrefs();
      this.paintButtons();
      const p = engine.el.play();
      if (p && p.catch) {
        p.catch(() => {
          // Autoplay with sound refused (no gesture reached the element yet): show the play button.
          this.root.classList.remove('tm-waiting');
          this.paintPlay();
        });
      }
    }

    wire(engine) {
      const el = engine.el;
      const on = (name, fn) => el.addEventListener(name, (e) => {
        if (this.engine === engine) fn(e);
      });
      on('playing', () => {
        this.root.classList.remove('tm-waiting');
        this.setStatus('');
        this.paintPlay();
        this.idleSoon();
        this.wake();
      });
      on('pause', () => {
        this.paintPlay();
        this.showChrome();
        this.report('progress');
      });
      on('play', () => this.paintPlay());
      on('waiting', () => this.root.classList.add('tm-waiting'));
      on('seeking', () => this.root.classList.add('tm-waiting'));
      on('seeked', () => this.root.classList.remove('tm-waiting'));
      on('canplay', () => this.root.classList.remove('tm-waiting'));
      on('timeupdate', () => this.paintTime());
      on('progress', () => this.paintBuffered());
      on('durationchange', () => this.paintTime());
      on('loadedmetadata', () => {
        this.paintButtons();
        this.paintChapters();
        this.applyTrackPrefs();
      });
      on('trackschange', () => {
        this.paintButtons();
        this.paintChapters();
        this.applyTrackPrefs();
      });
      on('volumechange', () => this.paintVolume());
      on('ended', () => {
        this.report('ended');
        this.paintPlay();
        this.showChrome();
      });
      on('enterpictureinpicture', () => this.paintButtons());
      on('leavepictureinpicture', () => this.paintButtons());
      if (engine.kind === 'native') {
        on('error', () => {
          const code = el.error ? el.error.code : 0;
          // 2 is the network: the bytes stopped coming, which another engine cannot fix.
          if (code === 2) return this.fail('stopped');
          if (!this.tried.has('movi')) this.start('movi', this.time() || this.spec.startSec || 0);
          else this.fail('codec');
        });
        // Chrome and Firefox play an MP4 whose sound they cannot decode (AC-3 there) in silence.
        // A file that should have sound and has decoded none after a few seconds goes to movi.
        on('playing', () => {
          if (this.silenceCheck) return;
          this.silenceCheck = setTimeout(() => {
            if (this.engine !== engine || el.paused || el.muted) return;
            const decoded = el.webkitAudioDecodedByteCount;
            const hasAudio = el.mozHasAudio;
            const silent = decoded === 0 || hasAudio === false;
            if (silent && (this.spec.durationSec || 0) > 60 && !this.tried.has('movi')) {
              this.osd(T('web.switch_decoder'));
              this.start('movi', this.time());
            }
          }, 4000);
        });
      } else {
        on('errordisplay', (e) => {
          const d = e.detail || {};
          if (d.canTrySoftware && !engine.software && !this.tried.has('movi-sw')) {
            this.osd(T('web.switch_decoder'));
            this.start('movi', this.time() || this.spec.startSec || 0, true);
            return;
          }
          const src = engine.el.sourceAdapter;
          if (src && src.getFatalError && src.getFatalError()) return this.fail('stopped');
          this.fail('codec');
        });
      }
    }

    fail(kind) {
      this.root.classList.remove('tm-waiting');
      this.root.classList.add('tm-failed');
      this.setStatus('');
      const h = this.errorBox.querySelector('h2');
      const p = this.errorBox.querySelector('p');
      const actions = this.errorBox.querySelector('.tm-actions');
      actions.innerHTML = '';
      const retry = button('play', T('common.try_again'), () => {
        this.tried.clear();
        this.start(this.spec.engine || choose(this.spec), this.time() || this.spec.startSec || 0);
      });
      retry.innerHTML = '';
      retry.textContent = T('common.try_again');
      const back = document.createElement('button');
      back.className = 'tm-btn';
      back.type = 'button';
      back.textContent = T('common.back');
      back.addEventListener('click', () => this.close());
      if (kind === 'stopped') {
        h.textContent = T('player.stalled_mid_title');
        p.textContent = T('web.stopped_loading');
        retry.classList.add('tm-primary');
        actions.append(retry, back);
      } else {
        h.textContent = T('web.needs_app_title');
        p.textContent = T('web.needs_app_codec');
        const get = document.createElement('button');
        get.className = 'tm-btn tm-primary';
        get.type = 'button';
        get.textContent = T('web.needs_app_get');
        get.addEventListener('click', () => window.open('https://tmplayer.org/download/', '_blank', 'noopener'));
        actions.append(get, retry, back);
      }
      this.emit({ type: 'error', kind: kind, pos: this.time(), dur: this.duration() });
    }

    // State ---------------------------------------------------------------------------------

    media() {
      return this.engine ? this.engine.el : null;
    }

    time() {
      const m = this.media();
      return m ? m.currentTime || 0 : 0;
    }

    duration() {
      const m = this.media();
      const d = m ? m.duration : 0;
      return isFinite(d) && d > 0 ? d : this.spec.durationSec || 0;
    }

    report(type) {
      this.emit({ type: type, pos: this.time(), dur: this.duration() });
    }

    togglePlay() {
      const m = this.media();
      if (!m) return;
      if (m.paused || m.ended) {
        const p = m.play();
        if (p && p.catch) p.catch(() => {});
      } else {
        m.pause();
      }
      this.showChrome();
    }

    seekTo(t) {
      const m = this.media();
      if (!m) return;
      const d = this.duration();
      t = Math.max(0, d > 0 ? Math.min(t, d - 0.5) : t);
      try {
        m.currentTime = t;
      } catch (e) {}
      this.paintTime(t);
    }

    seekBy(delta) {
      this.seekTo(this.time() + delta);
      this.osd((delta > 0 ? '+' : '-') + T('player.seek_seconds', { seconds: Math.abs(delta) }));
    }

    setVolume(v) {
      const m = this.media();
      v = Math.max(0, Math.min(1, v));
      this.prefs.volume = v;
      if (m) {
        m.volume = v;
        if (v > 0 && m.muted) m.muted = false;
      }
      this.savePrefs();
    }

    toggleMute() {
      const m = this.media();
      if (!m) return;
      m.muted = !m.muted;
      this.prefs.muted = m.muted;
      this.osd(m.muted ? T('player.muted') : T('keys.row_volume') + ' ' + Math.round(m.volume * 100) + '%');
      this.savePrefs();
    }

    setSpeed(s) {
      const m = this.media();
      this.prefs.speed = s;
      if (m) m.playbackRate = s;
      this.osd(T('player.speed') + ': ' + s + 'x');
      this.savePrefs();
    }

    stepSpeed(dir) {
      const i = SPEEDS.indexOf(this.prefs.speed);
      const next = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, (i < 0 ? 3 : i) + dir))];
      this.setSpeed(next);
    }

    setShape(shape) {
      this.prefs.shape = shape;
      this.root.classList.toggle('tm-shape-crop', shape === 'crop');
      this.root.classList.toggle('tm-shape-stretch', shape === 'stretch');
      const m = this.media();
      if (m && this.engine.kind === 'movi') m.setAttribute('objectfit', shape === 'crop' ? 'cover' : shape === 'stretch' ? 'fill' : 'contain');
      this.osd(T('player.picture_shape_now', { shape: T(shapeKey(shape)) }));
      this.savePrefs();
    }

    setNight(on) {
      this.prefs.night = on;
      const m = this.media();
      if (m && this.engine.kind === 'movi') m.stableVolume = on || this.prefs.boost;
      this.audioGraph();
      this.osd(on ? T('player.night_mode_on') : T('player.night_mode_off'));
      this.savePrefs();
    }

    setBoost(on) {
      this.prefs.boost = on;
      const m = this.media();
      if (m && this.engine.kind === 'movi') m.stableVolume = on || this.prefs.night;
      this.audioGraph();
      this.osd(on ? T('player.volume_boost_on') : T('player.volume_boost_off'));
      this.savePrefs();
    }

    /**
     * The volume boost and night mode for the browser's own player: a compressor that holds loud
     * scenes back, then gain that lifts quiet speech. Built only once one of them is switched on,
     * since routing a <video> through Web Audio is what the lock screen's audio does not like.
     */
    audioGraph() {
      if (!this.engine || this.engine.kind !== 'native') return;
      const wanted = this.prefs.boost || this.prefs.night;
      if (!wanted && !this.graph) return;
      try {
        if (!this.graph) {
          const ctx = new (window.AudioContext || window.webkitAudioContext)();
          const src = ctx.createMediaElementSource(this.engine.el);
          const comp = ctx.createDynamicsCompressor();
          const gain = ctx.createGain();
          src.connect(comp);
          comp.connect(gain);
          gain.connect(ctx.destination);
          this.graph = { ctx: ctx, comp: comp, gain: gain, el: this.engine.el };
        }
        const g = this.graph;
        g.comp.threshold.value = wanted ? -28 : 0;
        g.comp.ratio.value = wanted ? (this.prefs.night ? 6 : 3) : 1;
        g.comp.knee.value = 12;
        g.gain.gain.value = this.prefs.boost ? 2.2 : (this.prefs.night ? 1.6 : 1);
        if (g.ctx.state === 'suspended') g.ctx.resume();
      } catch (e) {}
    }

    applyPrefs() {
      const m = this.media();
      if (!m) return;
      m.volume = this.prefs.volume;
      m.muted = !!this.prefs.muted;
      m.playbackRate = this.prefs.speed || 1;
      if (this.engine.kind === 'movi') {
        m.stableVolume = !!(this.prefs.boost || this.prefs.night);
        this.engine.setSubtitleDelay(this.prefs.subtitleDelay || 0);
      }
      this.setShapeQuiet(this.prefs.shape || 'fit');
    }

    setShapeQuiet(shape) {
      this.root.classList.toggle('tm-shape-crop', shape === 'crop');
      this.root.classList.toggle('tm-shape-stretch', shape === 'stretch');
    }

    /** The viewer's language for sound and subtitles, as the apps keep it. */
    applyTrackPrefs() {
      if (!this.engine || this.trackPrefsApplied) return;
      const audio = this.engine.audioTracks();
      if (!audio.length && !this.engine.subtitleTracks().length) return;
      this.trackPrefsApplied = true;
      const want = (this.prefs.audioLang || '').toLowerCase();
      if (want && audio.length > 1) {
        const hit = audio.find((t) => (t.lang || '').toLowerCase().startsWith(want));
        if (hit && !hit.enabled) this.engine.selectAudio(hit.id);
      }
      const subWant = (this.prefs.subtitleLang || '').toLowerCase();
      if (subWant) {
        const hit = this.engine.subtitleTracks().find((t) => t.text && (t.lang || '').toLowerCase().startsWith(subWant));
        if (hit) this.engine.selectSubtitle(hit.id);
      }
    }

    savePrefs() {
      this.emit({ type: 'prefs', prefs: this.prefs });
    }

    // Painting ------------------------------------------------------------------------------

    setStatus(text) {
      this.status.textContent = text || '';
    }

    paintPlay() {
      const m = this.media();
      const paused = !m || m.paused || m.ended;
      const name = paused ? 'play' : 'pause';
      const label = paused ? T('player.play') : T('player.pause');
      for (const b of [this.playBtn, this.bigPlay]) {
        b.innerHTML = icon(name);
        b.setAttribute('aria-label', label);
        if (!coarse()) b.title = label + ' (K)';
      }
      if ('mediaSession' in navigator) navigator.mediaSession.playbackState = paused ? 'paused' : 'playing';
    }

    paintVolume() {
      const m = this.media();
      if (!m) return;
      this.volume.value = String(m.muted ? 0 : m.volume);
      this.muteBtn.innerHTML = icon(m.muted || m.volume === 0 ? 'muted' : 'volume');
    }

    paintTime(at) {
      const t = at != null ? at : this.time();
      const d = this.duration();
      const right = this.showRemaining && d > 0 ? '-' + clock(d - t) : clock(d);
      this.timeEl.textContent = `${clock(t)} / ${right}`;
      const f = d > 0 ? Math.min(1, t / d) : 0;
      if (!this.dragging) {
        this.played.style.width = (f * 100) + '%';
        this.handle.style.left = (f * 100) + '%';
      }
      this.seek.setAttribute('aria-valuenow', String(Math.round(t)));
      this.seek.setAttribute('aria-valuemax', String(Math.round(d)));
      this.seek.setAttribute('aria-valuetext', clock(t));
      if (!this.progressTimer) {
        this.progressTimer = setInterval(() => {
          const m = this.media();
          if (m && !m.paused) this.report('progress');
        }, 5000);
      }
      if ('mediaSession' in navigator && d > 0 && navigator.mediaSession.setPositionState) {
        try {
          navigator.mediaSession.setPositionState({ duration: d, position: Math.min(t, d), playbackRate: this.prefs.speed || 1 });
        } catch (e) {}
      }
    }

    paintBuffered() {
      const m = this.media();
      const d = this.duration();
      if (!m || !m.buffered || d <= 0) return;
      const t = this.time();
      let end = t;
      for (let i = 0; i < m.buffered.length; i++) {
        if (m.buffered.start(i) <= t + 1 && m.buffered.end(i) > end) end = m.buffered.end(i);
      }
      this.bufferedBar.style.width = Math.min(100, (end / d) * 100) + '%';
    }

    paintChapters() {
      this.ticks.innerHTML = '';
      const d = this.duration();
      if (!this.engine || d <= 0) return;
      this.chapterList = this.engine.chapters();
      for (const c of this.chapterList) {
        if (c.start <= 0) continue;
        const tick = document.createElement('div');
        tick.className = 'tm-chapter-tick';
        tick.style.left = (c.start / d * 100) + '%';
        this.ticks.append(tick);
      }
    }

    paintButtons() {
      if (!this.engine) return;
      this.pipBtn.hidden = !this.engine.canPip();
      this.castBtn.hidden = !this.engine.canCast();
      this.audioBtn.hidden = this.engine.audioTracks().length < 2;
      this.subsBtn.hidden = this.engine.subtitleTracks().length === 0;
      // On an iPhone only a <video> goes full screen; movi's canvas fills the window already.
      this.fsBtn.hidden = IPHONE && (this.engine.kind !== 'native' || STANDALONE);
      this.paintFullscreen();
    }

    paintFullscreen() {
      const fs = !!(document.fullscreenElement || document.webkitFullscreenElement);
      this.fsBtn.innerHTML = icon(fs ? 'fullscreenExit' : 'fullscreen');
      this.fsBtn.setAttribute('aria-label', fs ? T('player.exit_fullscreen_hint') : T('player.fullscreen_hint'));
    }

    osd(text) {
      this.osdEl.textContent = text;
      this.osdEl.classList.add('tm-show');
      clearTimeout(this.osdTimer);
      this.osdTimer = setTimeout(() => this.osdEl.classList.remove('tm-show'), 1200);
    }

    showChrome() {
      this.root.classList.remove('tm-idle');
      this.idleSoon();
    }

    idleSoon() {
      clearTimeout(this.idleTimer);
      this.idleTimer = setTimeout(() => {
        const m = this.media();
        if (m && !m.paused && !this.menu.classList.contains('tm-open') && !this.dragging) this.root.classList.add('tm-idle');
      }, coarse() ? 3500 : 2500);
    }

    // Menus ---------------------------------------------------------------------------------

    openMenu(which) {
      const menu = this.menu;
      menu.innerHTML = '';
      const add = (label, detail, checked, onPick) => {
        const b = document.createElement('button');
        b.className = 'tm-item';
        b.type = 'button';
        b.setAttribute('role', 'menuitemradio');
        b.setAttribute('aria-checked', checked ? 'true' : 'false');
        b.innerHTML = `<span class="tm-check">${checked ? '&#10003;' : ''}</span><span></span><span class="tm-detail"></span>`;
        b.children[1].textContent = label;
        b.children[2].textContent = detail || '';
        b.addEventListener('click', (e) => {
          e.stopPropagation();
          onPick();
        });
        menu.append(b);
        return b;
      };
      const heading = (text) => {
        const h = document.createElement('h3');
        h.textContent = text;
        menu.append(h);
      };
      const e = this.engine;
      if (which === 'main') {
        heading(T('player.playback_options'));
        add(T('player.speed'), (this.prefs.speed || 1) + 'x', false, () => this.openMenu('speed'));
        if (e && e.audioTracks().length > 1) add(T('player.audio'), '', false, () => this.openMenu('audio'));
        if (e && e.subtitleTracks().length) add(T('player.subtitles'), '', false, () => this.openMenu('subtitles'));
        add(T('player.picture_shape'), T(shapeKey(this.prefs.shape)), false, () => {
          const order = ['fit', 'crop', 'stretch'];
          this.setShape(order[(order.indexOf(this.prefs.shape || 'fit') + 1) % 3]);
          this.openMenu('main');
        });
        add(T('player.volume_boost'), '', !!this.prefs.boost, () => {
          this.setBoost(!this.prefs.boost);
          this.openMenu('main');
        });
        add(T('player.night_mode'), '', !!this.prefs.night, () => {
          this.setNight(!this.prefs.night);
          this.openMenu('main');
        });
        if (this.chapterList && this.chapterList.length > 1) add(T('player.chapter_next'), '', false, () => this.chapterStep(1));
        if (!coarse()) add(T('player.shortcuts'), '?', false, () => this.openMenu('keys'));
      } else if (which === 'speed') {
        heading(T('player.playback_speed'));
        for (const s of SPEEDS) {
          add(s === 1 ? '1x' : s + 'x', '', s === (this.prefs.speed || 1), () => {
            this.setSpeed(s);
            this.closeMenu();
          });
        }
      } else if (which === 'audio' && e) {
        heading(T('player.audio'));
        const tracks = e.audioTracks();
        tracks.forEach((t, i) => {
          const name = t.label || t.lang || T('player.track_number', { number: i + 1 });
          const detail = [t.lang && t.label ? t.lang : '', t.codec || '', t.channels ? t.channels + 'ch' : ''].filter(Boolean).join(' ');
          add(name, detail, t.enabled, () => {
            e.selectAudio(t.id);
            this.prefs.audioLang = t.lang || this.prefs.audioLang;
            this.savePrefs();
            this.osd(T('player.audio_track', { track: name }));
            this.closeMenu();
          });
        });
      } else if (which === 'subtitles' && e) {
        heading(T('player.subtitles'));
        const tracks = e.subtitleTracks();
        add(T('player.subtitles_off'), '', !tracks.some((t) => t.enabled), () => {
          e.selectSubtitle(null);
          this.osd(T('player.subtitles_off'));
          this.closeMenu();
        });
        tracks.forEach((t, i) => {
          const name = t.label || t.lang || T('player.track_number', { number: i + 1 });
          const b = add(name, t.text ? '' : T('web.needs_app_picture_subtitles'), t.enabled, () => {
            e.selectSubtitle(t.id);
            this.prefs.subtitleLang = t.lang || this.prefs.subtitleLang;
            this.savePrefs();
            this.osd(T('player.subtitles_track', { track: name }));
            this.closeMenu();
          });
          if (!t.text) b.disabled = true;
        });
        heading(T('player.subtitle_delay', { delay: (this.prefs.subtitleDelay || 0).toFixed(1) + ' s' }));
        add(T('player.earlier'), '-0.1 s', false, () => {
          this.subtitleDelay(-0.1);
          this.openMenu('subtitles');
        });
        add(T('player.later'), '+0.1 s', false, () => {
          this.subtitleDelay(0.1);
          this.openMenu('subtitles');
        });
        add(T('player.reset_delay'), '', false, () => {
          this.prefs.subtitleDelay = 0;
          this.engine.setSubtitleDelay(0);
          this.openMenu('subtitles');
        });
      } else if (which === 'keys') {
        heading(T('player.shortcuts'));
        const cmd = MAC ? T('keys.cmd') : T('keys.ctrl');
        const rows = [
          [T('keys.row_play_pause'), T('keys.space_k')],
          [T('keys.row_seek_5s'), T('keys.left_right')],
          [T('keys.row_seek_10s'), 'J, L'],
          [T('keys.row_seek_1min'), T('keys.shift_left_right')],
          [T('keys.row_speed'), T('keys.speed_keys')],
          [T('keys.row_episodes'), T('keys.episodes_keys')],
          [T('keys.row_jump'), T('keys.digits')],
          [T('keys.row_start_end'), T('keys.home_end')],
          [T('keys.row_volume'), T('keys.volume_wheel')],
          [T('keys.row_mute'), 'M'],
          [T('keys.row_subtitles'), T('keys.subtitles_keys')],
          [T('keys.row_audio'), T('keys.audio_keys')],
          [T('keys.row_subtitle_delay'), 'Z, X'],
          [T('keys.row_fullscreen'), T('keys.fullscreen_keys')],
          [T('player.picture_shape'), 'W'],
          [T('keys.row_chapter'), T('keys.chapter', { cmd: cmd })],
          [T('keys.row_frame_step'), T('keys.comma_period')],
          [T('keys.row_back'), T('keys.back_keys')],
          [T('keys.row_this_sheet'), '?'],
        ];
        for (const [what, keys] of rows) add(what, keys, false, () => {});
      }
      menu.classList.add('tm-open');
      this.showChrome();
      const first = menu.querySelector('.tm-item');
      if (first) first.focus({ preventScroll: true });
    }

    closeMenu() {
      this.menu.classList.remove('tm-open');
      this.idleSoon();
      this.root.focus({ preventScroll: true });
    }

    subtitleDelay(delta) {
      this.prefs.subtitleDelay = Math.round(((this.prefs.subtitleDelay || 0) + delta) * 10) / 10;
      if (this.engine) this.engine.setSubtitleDelay(this.prefs.subtitleDelay);
      this.osd(T('player.subtitle_delay', { delay: this.prefs.subtitleDelay.toFixed(1) + ' s' }));
      this.savePrefs();
    }

    cycleSubtitles(dir) {
      const e = this.engine;
      if (!e) return;
      const tracks = e.subtitleTracks().filter((t) => t.text);
      if (!tracks.length) return this.osd(T('player.no_subtitles'));
      const i = tracks.findIndex((t) => t.enabled);
      const next = i + dir;
      if (next < 0 || next >= tracks.length) {
        e.selectSubtitle(null);
        this.osd(T('player.subtitles_off'));
      } else {
        e.selectSubtitle(tracks[next].id);
        this.osd(T('player.subtitles_track', { track: tracks[next].label || tracks[next].lang || String(next + 1) }));
      }
    }

    cycleAudio(dir) {
      const e = this.engine;
      if (!e) return;
      const tracks = e.audioTracks();
      if (tracks.length < 2) return this.osd(T('player.no_other_audio'));
      const i = Math.max(0, tracks.findIndex((t) => t.enabled));
      const t = tracks[(i + dir + tracks.length) % tracks.length];
      e.selectAudio(t.id);
      this.osd(T('player.audio_track', { track: t.label || t.lang || '' }));
    }

    chapterStep(dir) {
      const list = (this.chapterList || []).slice().sort((a, b) => a.start - b.start);
      if (!list.length) return this.seekBy(dir * SEEK_LONG);
      const t = this.time();
      if (dir > 0) {
        const next = list.find((c) => c.start > t + 1);
        if (next) this.seekTo(next.start);
        else this.osd(T('player.last_chapter'));
      } else {
        const before = list.filter((c) => c.start < t - 3);
        this.seekTo(before.length ? before[before.length - 1].start : 0);
      }
    }

    frameStep(dir) {
      const m = this.media();
      if (!m) return;
      m.pause();
      this.seekTo(this.time() + dir / 25);
    }

    toggleFullscreen() {
      const m = this.media();
      if (document.fullscreenElement || document.webkitFullscreenElement) {
        (document.exitFullscreen || document.webkitExitFullscreen).call(document);
        return;
      }
      if (IPHONE && m && m.webkitEnterFullscreen) {
        m.webkitEnterFullscreen();
        return;
      }
      const r = this.root;
      const req = r.requestFullscreen || r.webkitRequestFullscreen;
      if (req) req.call(r).catch && req.call(r).catch(() => {});
    }

    // Input ---------------------------------------------------------------------------------

    bindSeek() {
      const seek = this.seek;
      const at = (clientX) => {
        const r = seek.getBoundingClientRect();
        let f = (clientX - r.left) / r.width;
        if (getComputedStyle(seek).direction === 'rtl') f = 1 - f;
        return Math.max(0, Math.min(1, f));
      };
      seek.addEventListener('pointerdown', (e) => {
        e.stopPropagation();
        seek.setPointerCapture(e.pointerId);
        this.dragging = true;
        seek.classList.add('tm-dragging');
        const f = at(e.clientX);
        this.played.style.width = (f * 100) + '%';
        this.handle.style.left = (f * 100) + '%';
        this.dragTo = f * this.duration();
        this.showChrome();
      });
      seek.addEventListener('pointermove', (e) => {
        const f = at(e.clientX);
        const d = this.duration();
        this.hoverTime.style.display = 'block';
        this.hoverTime.style.left = (f * 100) + '%';
        this.hoverTime.textContent = clock(f * d);
        if (this.dragging) {
          this.played.style.width = (f * 100) + '%';
          this.handle.style.left = (f * 100) + '%';
          this.dragTo = f * d;
        }
      });
      const end = () => {
        if (!this.dragging) return;
        this.dragging = false;
        seek.classList.remove('tm-dragging');
        this.hoverTime.style.display = 'none';
        this.seekTo(this.dragTo || 0);
        this.idleSoon();
      };
      seek.addEventListener('pointerup', end);
      seek.addEventListener('pointercancel', end);
      seek.addEventListener('pointerleave', () => {
        if (!this.dragging) this.hoverTime.style.display = 'none';
      });
      seek.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
          e.preventDefault();
          e.stopPropagation();
          this.seekBy(e.key === 'ArrowRight' ? SEEK_SHORT : -SEEK_SHORT);
        }
      });
    }

    /**
     * The phone's gestures (TouchPrefs in :core): a tap shows or hides the controls, a double tap
     * on either side jumps 10 seconds (and more for each further tap), holding plays at 2x, a
     * vertical swipe on the right sets the volume and on the left the brightness (a dimming layer,
     * the nearest a page has), a horizontal swipe scrubs. A mouse: a click plays and pauses, a
     * double click is full screen, the wheel is the volume.
     */
    bindPointer() {
      const stage = this.root;
      let down = null;
      let lastTap = 0;
      let tapTimer = 0;
      let holdTimer = 0;
      let taps = 0;
      const side = (x) => {
        const w = window.innerWidth;
        return x < w * 0.35 ? -1 : x > w * 0.65 ? 1 : 0;
      };
      stage.addEventListener('pointermove', (e) => {
        if (e.pointerType === 'mouse') this.showChrome();
        if (!down || down.id !== e.pointerId) return;
        const dx = e.clientX - down.x;
        const dy = e.clientY - down.y;
        if (!down.mode && Math.hypot(dx, dy) > 16 && e.pointerType !== 'mouse') {
          clearTimeout(holdTimer);
          down.mode = Math.abs(dx) > Math.abs(dy) ? 'scrub' : (down.x > window.innerWidth / 2 ? 'volume' : 'bright');
          down.t0 = this.time();
          down.v0 = this.media() ? this.media().volume : 1;
          down.b0 = Number(this.dim.style.opacity || 0);
        }
        if (down.mode === 'scrub') {
          const d = this.duration();
          const t = Math.max(0, Math.min(d, down.t0 + dx / window.innerWidth * Math.min(d, 600)));
          down.target = t;
          this.osd(clock(t) + ' / ' + clock(d));
        } else if (down.mode === 'volume' && !IOS) {
          const v = Math.max(0, Math.min(1, down.v0 - dy / (window.innerHeight * 0.6)));
          this.setVolume(v);
          this.osd(T('keys.row_volume') + ' ' + Math.round(v * 100) + '%');
        } else if (down.mode === 'bright') {
          const b = Math.max(0, Math.min(0.85, down.b0 + dy / (window.innerHeight * 0.6)));
          this.dim.style.opacity = String(b);
          this.osd(Math.round((1 - b) * 100) + '%');
        }
      });
      stage.addEventListener('pointerdown', (e) => {
        if (e.target.closest('button, input, .tm-menu, .tm-seek')) return;
        if (this.menu.classList.contains('tm-open')) {
          this.closeMenu();
          return;
        }
        down = { id: e.pointerId, x: e.clientX, y: e.clientY, mode: null };
        if (e.pointerType !== 'mouse') {
          holdTimer = setTimeout(() => {
            const m = this.media();
            if (!down || down.mode || !m || m.paused) return;
            down.mode = 'hold';
            down.rate = m.playbackRate;
            m.playbackRate = 2;
            this.osd('2x');
          }, 500);
        }
      });
      const up = (e) => {
        clearTimeout(holdTimer);
        if (!down || down.id !== e.pointerId) return;
        const d = down;
        down = null;
        const m = this.media();
        if (d.mode === 'hold') {
          if (m) m.playbackRate = d.rate || this.prefs.speed || 1;
          return;
        }
        if (d.mode === 'scrub') {
          if (d.target != null) this.seekTo(d.target);
          return;
        }
        if (d.mode) return;
        if (e.pointerType === 'mouse') {
          const now = Date.now();
          if (now - lastTap < 300) {
            clearTimeout(tapTimer);
            this.toggleFullscreen();
            lastTap = 0;
          } else {
            lastTap = now;
            tapTimer = setTimeout(() => this.togglePlay(), 220);
          }
          return;
        }
        const now = Date.now();
        const s = side(d.x);
        if (now - lastTap < 300 && s !== 0) {
          clearTimeout(tapTimer);
          taps++;
          this.seekBy(s * SEEK_MEDIUM);
          const ripple = this.root.querySelector(s < 0 ? '.tm-ripple.tm-left' : '.tm-ripple.tm-right');
          ripple.textContent = (s < 0 ? '-' : '+') + T('player.seek_seconds', { seconds: SEEK_MEDIUM * taps });
          ripple.classList.add('tm-show');
          clearTimeout(this.rippleTimer);
          this.rippleTimer = setTimeout(() => {
            ripple.classList.remove('tm-show');
            taps = 0;
          }, 700);
          lastTap = now;
          return;
        }
        lastTap = now;
        taps = 1;
        tapTimer = setTimeout(() => {
          taps = 0;
          if (this.root.classList.contains('tm-idle')) this.showChrome();
          else this.root.classList.add('tm-idle');
        }, 260);
      };
      stage.addEventListener('pointerup', up);
      stage.addEventListener('pointercancel', up);
      stage.addEventListener('wheel', (e) => {
        if (e.target.closest('.tm-menu')) return;
        e.preventDefault();
        const m = this.media();
        if (!m) return;
        this.setVolume(m.volume + (e.deltaY < 0 ? 0.05 : -0.05));
        this.osd(T('keys.row_volume') + ' ' + Math.round(m.volume * 100) + '%');
      }, { passive: false });
    }

    bindKeys() {
      this.onKey = (e) => {
        if (this.closed) return;
        const t = e.target;
        if (t && (t.tagName === 'INPUT' && t.type !== 'range')) return;
        const shift = e.shiftKey;
        const ctrl = e.ctrlKey;
        const alt = e.altKey;
        const meta = e.metaKey;
        const command = MAC ? meta : ctrl;
        const plain = !shift && !ctrl && !alt && !meta;
        const k = e.key;
        let handled = true;
        if (this.menu.classList.contains('tm-open')) {
          if (k === 'Escape' || k === 'Backspace') this.closeMenu();
          else handled = false;
          if (handled) e.preventDefault();
          return;
        }
        if (k === 'ArrowLeft' || k === 'ArrowRight') {
          const sign = k === 'ArrowRight' ? 1 : -1;
          if (command && !shift && !alt) this.chapterStep(sign);
          else {
            const huge = MAC ? meta && shift && alt : ctrl && alt;
            this.seekBy(sign * (huge ? SEEK_HUGE : shift || ctrl ? SEEK_LONG : alt ? SEEK_MEDIUM : SEEK_SHORT));
          }
        } else if (k === 'ArrowUp' || k === 'ArrowDown') {
          const m = this.media();
          if (m) {
            this.setVolume(m.volume + (k === 'ArrowUp' ? 0.05 : -0.05));
            this.osd(T('keys.row_volume') + ' ' + Math.round(m.volume * 100) + '%');
          }
        } else if ((k === ' ' || k === 'k' || k === 'K' || k === 'Enter') && plain) {
          this.togglePlay();
        } else if (k === 'MediaPlayPause') {
          this.togglePlay();
        } else if (k === 'j' || k === 'J') {
          this.seekBy(-SEEK_MEDIUM);
        } else if (k === 'l' || k === 'L') {
          this.seekBy(SEEK_MEDIUM);
        } else if ((k === 'm' || k === 'M') && plain) {
          this.toggleMute();
        } else if ((k === 'f' || k === 'F' || k === 'F11') && !command) {
          this.toggleFullscreen();
        } else if (k === 'Escape') {
          if (document.fullscreenElement) document.exitFullscreen();
          else this.close();
        } else if (k === 'Backspace') {
          if (shift) handled = false;
          else if ((this.prefs.speed || 1) !== 1) this.setSpeed(1);
          else this.close();
        } else if (k === 'Home') {
          this.seekTo(0);
        } else if (k === 'End') {
          this.seekTo(this.duration());
        } else if (/^[0-9]$/.test(k) && !ctrl && !alt && !meta) {
          this.seekTo(this.duration() * Number(k) / 10);
        } else if (k === '#' || ((k === 'a' || k === 'A') && !command)) {
          this.cycleAudio(k === 'A' ? -1 : 1);
        } else if ((k === 'v' || k === 'V') && !command) {
          this.cycleSubtitles(k === 'V' ? -1 : 1);
        } else if ((k === 'c' || k === 'C') && plain) {
          const tracks = this.engine ? this.engine.subtitleTracks().filter((x) => x.text) : [];
          if (tracks.some((x) => x.enabled)) {
            this.engine.selectSubtitle(null);
            this.osd(T('player.subtitles_off'));
          } else this.cycleSubtitles(1);
        } else if (k === 'z' || k === 'Z' || k === 'x' || k === 'X') {
          this.subtitleDelay(k === 'z' ? -0.1 : 0.1);
        } else if (k === ']' || k === '=' || k === '+' || k === '>') {
          this.stepSpeed(1);
        } else if (k === '[' || k === '-' || k === '<') {
          this.stepSpeed(-1);
        } else if (k === ',' || k === '.') {
          this.frameStep(k === '.' ? 1 : -1);
        } else if ((k === 'w' || k === 'W') && plain) {
          const order = ['fit', 'crop', 'stretch'];
          this.setShape(order[(order.indexOf(this.prefs.shape || 'fit') + 1) % 3]);
        } else if ((k === 'N' && shift) || k === 'PageDown' || k === 'MediaTrackNext') {
          if (this.spec.hasNext) this.emit({ type: 'next', pos: this.time(), dur: this.duration() });
          else this.osd(T('player.no_next_episode'));
        } else if ((k === 'P' && shift) || k === 'PageUp' || k === 'MediaTrackPrevious') {
          if (this.spec.hasPrevious) this.emit({ type: 'previous', pos: this.time(), dur: this.duration() });
          else this.osd(T('player.no_previous_episode'));
        } else if ((k === 'p') && plain) {
          this.togglePlay();
        } else if (k === '?') {
          this.openMenu('keys');
        } else if ((k === 'i' || k === 'I') && plain) {
          this.osd([this.engine ? this.engine.kind : '', clock(this.time())].join('  '));
        } else {
          handled = false;
        }
        if (handled) {
          e.preventDefault();
          e.stopPropagation();
          this.showChrome();
        }
      };
      window.addEventListener('keydown', this.onKey, true);
    }

    mediaSession() {
      if (!('mediaSession' in navigator)) return;
      const ms = navigator.mediaSession;
      try {
        ms.metadata = new MediaMetadata({
          title: this.spec.title || '',
          artist: this.spec.subtitle || '',
          album: this.spec.chat || 'TMPlayer',
          artwork: this.spec.artwork ? [{ src: this.spec.artwork, sizes: '512x512' }] : [{ src: 'icon-512.png', sizes: '512x512', type: 'image/png' }],
        });
      } catch (e) {}
      const set = (action, fn) => {
        try {
          ms.setActionHandler(action, fn);
        } catch (e) {}
      };
      set('play', () => this.media() && this.media().play());
      set('pause', () => this.media() && this.media().pause());
      set('seekbackward', (d) => this.seekBy(-((d && d.seekOffset) || SEEK_MEDIUM)));
      set('seekforward', (d) => this.seekBy((d && d.seekOffset) || SEEK_MEDIUM));
      set('seekto', (d) => d && d.seekTime != null && this.seekTo(d.seekTime));
      set('previoustrack', this.spec.hasPrevious ? () => this.emit({ type: 'previous', pos: this.time(), dur: this.duration() }) : null);
      set('nexttrack', this.spec.hasNext ? () => this.emit({ type: 'next', pos: this.time(), dur: this.duration() }) : null);
    }

    async wake() {
      try {
        if (navigator.wakeLock && !this.wakeLock) {
          this.wakeLock = await navigator.wakeLock.request('screen');
          this.wakeLock.addEventListener('release', () => {
            this.wakeLock = null;
          });
        }
      } catch (e) {}
    }

    close(fromHistory) {
      if (this.closed) return;
      this.report('close');
      this.closed = true;
      clearInterval(this.progressTimer);
      clearTimeout(this.idleTimer);
      clearTimeout(this.silenceCheck);
      window.removeEventListener('keydown', this.onKey, true);
      window.removeEventListener('popstate', this.onPop);
      document.removeEventListener('fullscreenchange', this.onFs);
      document.removeEventListener('webkitfullscreenchange', this.onFs);
      document.removeEventListener('visibilitychange', this.onVisibility);
      if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
      if (this.engine) this.engine.dispose();
      if (this.graph) this.graph.ctx.close().catch(() => {});
      if (this.wakeLock) this.wakeLock.release().catch(() => {});
      if ('mediaSession' in navigator) {
        try {
          navigator.mediaSession.metadata = null;
          navigator.mediaSession.playbackState = 'none';
        } catch (e) {}
      }
      this.root.remove();
      if (!fromHistory && history.state && history.state.tmPlayer) {
        // The app's own back handling (web-host.js) is told this step is the player's.
        window.__tmSkipPop = true;
        history.back();
      }
      if (current === this) current = null;
    }
  }

  let current = null;

  window.TmPlayer = {
    open(specJson, onEvent) {
      if (current) current.close();
      const spec = typeof specJson === 'string' ? JSON.parse(specJson) : specJson;
      window.TmPlayer.lastSpec = spec;
      current = new Player(spec, onEvent || (() => {}));
    },
    close() {
      if (current) current.close();
    },
    get isOpen() {
      return current != null;
    },
    /** The engine a file would start in, for the grid's "Needs the app" badge and tests. */
    choose(specJson) {
      return choose(typeof specJson === 'string' ? JSON.parse(specJson) : specJson);
    },
    /** Starts movi-player's download in the background (a card hovered, or the app idle). */
    preload() {
      loadMovi().catch(() => {});
    },
  };
})();
