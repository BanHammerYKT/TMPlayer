// The picture under the app's own player controls (CP92). The controls, the menus and the keys are
// the desktop app's, drawn by Compose on the page's canvas (desktop/.../player/PlayerOverlay.kt,
// through web/.../WebPlaybackEngine.kt); this file only shows the video under that canvas, in one
// of player.js's two engines, and tells Kotlin where it is.
//
// window.TmVideo.open(specJson, onState) starts a video. onState is called with a JSON object of
// the playback state at every change worth drawing, and with {"type":"error","kind":...} when no
// engine can play the file ("codec", "heavy") or the bytes stopped coming ("stopped").
(function () {
  'use strict';

  const E = window.TmEngines;
  let current = null;

  class Video {
    constructor(spec, onState) {
      this.spec = spec;
      this.onState = onState;
      this.tried = new Set();
      this.engine = null;
      this.closed = false;
      this.want = true;
      this.subtitle = null;
      this.shape = 'fit';
      this.stage = document.createElement('div');
      this.stage.id = 'tm-video';
      document.body.insertBefore(this.stage, document.body.firstChild);
      document.documentElement.classList.add('tm-video-open');
      this.timer = 0;
      this.start(spec.engine || E.choose(spec), spec.startSec || 0);
    }

    async start(kind, at, software) {
      if (this.closed) return;
      this.tried.add(kind + (software ? '-sw' : ''));
      if (this.engine) {
        this.engine.dispose();
        this.engine.el.remove();
        this.engine = null;
      }
      this.failed = null;
      this.send();
      let engine;
      if (kind === 'movi') {
        try {
          await E.loadMovi();
        } catch (e) {
          if (!this.tried.has('native')) return this.start('native', at);
          return this.fail('codec');
        }
        if (this.closed) return;
        engine = new E.MoviEngine(software);
      } else {
        engine = new E.NativeEngine();
      }
      this.engine = engine;
      this.stage.append(engine.el);
      this.wire(engine);
      engine.load(Object.assign({}, this.spec, { startSec: at }));
      this.applyPrefs();
      if (this.want) this.play();
    }

    wire(engine) {
      const el = engine.el;
      const on = (name, fn) => el.addEventListener(name, (e) => {
        if (this.engine === engine) fn(e);
      });
      ['playing', 'pause', 'play', 'waiting', 'seeking', 'seeked', 'canplay', 'durationchange', 'loadedmetadata',
        'trackschange', 'volumechange', 'ended', 'ratechange', 'resize'].forEach((n) => on(n, () => this.send()));
      on('timeupdate', () => this.sendSoon());
      on('progress', () => this.sendSoon());
      on('loadedmetadata', () => {
        if (engine.kind === 'movi') this.checkSmooth(engine);
      });
      if (engine.kind === 'native') {
        on('error', () => {
          const code = el.error ? el.error.code : 0;
          if (code === 2) return this.fail('stopped');
          if (!this.tried.has('movi')) this.start('movi', this.time() || this.spec.startSec || 0);
          else this.fail('codec');
        });
        on('playing', () => {
          if (this.silenceCheck) return;
          this.silenceCheck = setTimeout(() => {
            if (this.engine !== engine || el.paused || el.muted) return;
            const silent = el.webkitAudioDecodedByteCount === 0 || el.mozHasAudio === false;
            if (silent && (this.spec.durationSec || 0) > 60 && !this.tried.has('movi')) this.start('movi', this.time());
          }, 4000);
        });
      } else {
        on('errordisplay', (e) => {
          const d = e.detail || {};
          if (d.canTrySoftware && !engine.software && !this.tried.has('movi-sw')) {
            this.start('movi', this.time() || this.spec.startSec || 0, true);
            return;
          }
          const src = engine.el.sourceAdapter;
          if (src && src.getFatalError && src.getFatalError()) return this.fail('stopped');
          this.fail('codec');
        });
      }
    }

    /** Software HEVC 10 bit at 1080p and up cannot keep up: said, rather than a spinner forever. */
    async checkSmooth(engine) {
      try {
        if (!engine.el.canPlaySmoothly) return;
        const a = await engine.el.canPlaySmoothly({ rate: this.media().playbackRate || 1 });
        if (this.engine !== engine || !a || a.smooth !== false) return;
        const heavy = engine.software || (a.reasons || []).some((r) => /software|hardware/i.test(String(r)));
        if (heavy) this.fail('heavy');
      } catch (e) {}
    }

    fail(kind) {
      this.failed = kind;
      this.onState(JSON.stringify({ type: 'error', kind: kind }));
      this.send();
    }

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

    buffered() {
      const m = this.media();
      try {
        const b = m && m.buffered;
        if (!b || !b.length) return 0;
        const t = this.time();
        for (let i = 0; i < b.length; i++) if (b.start(i) <= t + 0.5 && b.end(i) >= t) return b.end(i);
        return b.end(b.length - 1);
      } catch (e) {
        return 0;
      }
    }

    sendSoon() {
      if (this.timer) return;
      this.timer = setTimeout(() => {
        this.timer = 0;
        this.send();
      }, 250);
    }

    send() {
      if (this.closed) return;
      const m = this.media();
      const e = this.engine;
      const audio = e ? e.audioTracks() : [];
      const subs = e ? e.subtitleTracks() : [];
      const state = {
        type: 'state',
        engine: e ? e.kind + (e.software ? '-sw' : '') : '',
        opened: !!(m && m.readyState >= 1),
        pos: this.time(),
        dur: this.duration(),
        buf: this.buffered(),
        playing: this.want,
        buffering: !!(m && !this.failed && (m.readyState < 3 || m.seeking) && this.want && !m.ended),
        seeking: !!(m && m.seeking),
        ended: !!(m && m.ended),
        w: m ? m.videoWidth || 0 : 0,
        h: m ? m.videoHeight || 0 : 0,
        volume: m ? m.volume : 1,
        muted: !!(m && m.muted),
        speed: m ? m.playbackRate || 1 : 1,
        failed: this.failed,
        audio: audio,
        subtitles: subs,
        chapters: e ? e.chapters() : [],
      };
      this.onState(JSON.stringify(state));
    }

    play() {
      this.want = true;
      const m = this.media();
      if (!m) return;
      const p = m.play();
      if (p && p.catch) {
        p.catch(() => {
          // Autoplay with sound refused: shown paused, the play button starts it.
          this.want = false;
          this.send();
        });
      }
      this.send();
    }

    pause() {
      this.want = false;
      const m = this.media();
      if (m) m.pause();
      this.send();
    }

    seek(sec) {
      const m = this.media();
      if (!m) return;
      const d = this.duration();
      const t = Math.max(0, d > 0 ? Math.min(sec, d - 0.5) : sec);
      try {
        m.currentTime = t;
      } catch (e) {}
      this.send();
    }

    setSpeed(s) {
      const m = this.media();
      if (m) m.playbackRate = s;
      this.speed = s;
    }

    setVolume(v) {
      const m = this.media();
      this.volume = Math.max(0, Math.min(1, v));
      if (m) m.volume = this.volume;
    }

    setMuted(on) {
      const m = this.media();
      this.muted = !!on;
      if (m) m.muted = this.muted;
    }

    setShape(shape) {
      this.shape = shape;
      this.stage.dataset.shape = shape;
      const m = this.media();
      if (m && this.engine.kind === 'movi') m.setAttribute('objectfit', shape === 'crop' ? 'cover' : shape === 'stretch' ? 'fill' : 'contain');
    }

    setStable(on) {
      const m = this.media();
      this.stable = !!on;
      if (m && this.engine.kind === 'movi') m.stableVolume = this.stable;
    }

    selectAudio(id) {
      if (this.engine) this.engine.selectAudio(id);
      this.send();
    }

    selectSubtitle(id) {
      this.subtitle = id;
      if (this.engine) this.engine.selectSubtitle(id);
      this.send();
    }

    setSubtitleDelay(sec) {
      this.subtitleDelay = sec;
      if (this.engine) this.engine.setSubtitleDelay(sec);
    }

    applyPrefs() {
      const m = this.media();
      if (!m) return;
      if (this.volume != null) m.volume = this.volume;
      if (this.muted != null) m.muted = this.muted;
      if (this.speed) m.playbackRate = this.speed;
      if (this.engine.kind === 'movi') {
        m.stableVolume = !!this.stable;
        if (this.subtitleDelay) this.engine.setSubtitleDelay(this.subtitleDelay);
      }
      this.setShape(this.shape);
    }

    retry() {
      this.tried.clear();
      this.start(this.spec.engine || E.choose(this.spec), this.time() || this.spec.startSec || 0);
    }

    close() {
      if (this.closed) return;
      this.closed = true;
      clearTimeout(this.timer);
      clearTimeout(this.silenceCheck);
      if (this.engine) {
        this.engine.dispose();
        this.engine.el.remove();
      }
      this.stage.remove();
      document.documentElement.classList.remove('tm-video-open');
      try {
        if (window.TmTd && window.TmTd.stop && this.spec.source) this.spec.source.ids.forEach((id) => window.TmTd.stop(id));
      } catch (e) {}
      if (current === this) current = null;
    }
  }

  const call = (fn) => (...args) => {
    if (current) return fn(current, ...args);
  };

  window.TmVideo = {
    open(specJson, onState) {
      if (current) current.close();
      const spec = typeof specJson === 'string' ? JSON.parse(specJson) : specJson;
      current = new Video(spec, onState || (() => {}));
    },
    close: call((v) => v.close()),
    play: call((v) => v.play()),
    pause: call((v) => v.pause()),
    seek: call((v, sec) => v.seek(sec)),
    setSpeed: call((v, s) => v.setSpeed(s)),
    setVolume: call((v, x) => v.setVolume(x)),
    setMuted: call((v, on) => v.setMuted(on)),
    setShape: call((v, s) => v.setShape(s)),
    setStable: call((v, on) => v.setStable(on)),
    selectAudio: call((v, id) => v.selectAudio(id)),
    selectSubtitle: call((v, id) => v.selectSubtitle(id === '' ? null : id)),
    setSubtitleDelay: call((v, s) => v.setSubtitleDelay(s)),
    retry: call((v) => v.retry()),
    frameStep: call((v, forward) => {
      const m = v.media();
      if (!m) return;
      m.pause();
      v.want = false;
      v.seek(v.time() + (forward ? 1 / 24 : -1 / 24));
    }),
    canPip: () => !!(current && current.engine && current.engine.canPip()),
    pip: call((v) => v.engine && v.engine.pip()),
    fullscreen(on) {
      const d = document;
      const el = d.documentElement;
      try {
        if (on && !(d.fullscreenElement || d.webkitFullscreenElement)) (el.requestFullscreen || el.webkitRequestFullscreen).call(el);
        if (!on && (d.fullscreenElement || d.webkitFullscreenElement)) (d.exitFullscreen || d.webkitExitFullscreen).call(d);
      } catch (e) {}
    },
    get isOpen() {
      return current != null;
    },
  };
})();
