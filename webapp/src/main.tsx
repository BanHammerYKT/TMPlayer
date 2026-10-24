import { render } from 'solid-js/web'
import { App } from './App'
import { serveStreams } from './stream/reader'
import { markEntry } from './session'
import { registerWorker } from './install'
import './styles.css'

markEntry()
serveStreams()
registerWorker()

const root = document.getElementById('app')!
root.textContent = ''
render(() => <App />, root)
