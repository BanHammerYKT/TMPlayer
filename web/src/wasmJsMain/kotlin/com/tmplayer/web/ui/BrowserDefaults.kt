package com.tmplayer.web.ui

/**
 * The browser's own answers to input that the app answers itself, turned off where they would
 * show over it, installed once when the page starts:
 *
 * - A right click (or Ctrl+click on a Mac) is the app's: a poster's or a chat's menu, the
 *   player's menu at the pointer. The browser's own menu (Back, Reload, Save image as) would open
 *   over it, as Compose for Web does not stop it on its canvas, so it is stopped over the app.
 * - The wheel's unit (pixels or lines), which Compose passes on without saying, is kept for the
 *   strips that scroll sideways by it (desktop/ui/DesktopUiWeb.kt, wheelPixels).
 */
@JsFun(
    """() => {
        if (window.__tmBrowserDefaults) return;
        window.__tmBrowserDefaults = true;
        document.addEventListener('contextmenu', (e) => {
            const app = document.getElementById('app');
            const path = e.composedPath ? e.composedPath() : [];
            if (app && (path.includes(app) || app.contains(e.target))) e.preventDefault();
        }, true);
        if (!window.__tmWheelWatch) {
            window.__tmWheelWatch = true;
            window.__tmWheelMode = 0;
            window.addEventListener('wheel', (e) => { window.__tmWheelMode = e.deltaMode; }, { capture: true, passive: true });
        }
    }""",
)
external fun installBrowserDefaults()
