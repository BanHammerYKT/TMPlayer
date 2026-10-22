import { render } from 'solid-js/web'
import { App } from './App'
import { serveStreams } from './stream/reader'
import './styles.css'

serveStreams()
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch((e) => console.warn('[sw]', e))

const root = document.getElementById('app')!
root.textContent = ''
render(() => <App />, root)
