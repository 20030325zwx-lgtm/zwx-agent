const { app, BrowserWindow, ipcMain } = require('electron')
const http = require('http')
const path = require('path')
const fs = require('fs')

const defaultApiBaseUrl = 'http://127.0.0.1:8123/api'
const configPath = () => path.join(app.getPath('userData'), 'desktop-config.json')
const readConfig = () => {
  try { return JSON.parse(fs.readFileSync(configPath(), 'utf8')) } catch { return {} }
}
const apiBaseUrl = () => readConfig().apiBaseUrl || defaultApiBaseUrl
const staticRoot = path.join(__dirname, '..', 'app')
let staticServer
let staticPort
const isValidApiBaseUrl = value => {
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && url.pathname.replace(/\/$/, '') === '/api'
  } catch { return false }
}

const contentType = filename => ({
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2'
}[path.extname(filename)] || 'application/octet-stream')

const startStaticServer = () => new Promise((resolve, reject) => {
  staticServer = http.createServer((request, response) => {
    const requestPath = decodeURIComponent((request.url || '/').split('?')[0])
    const requestedFile = path.resolve(staticRoot, `.${requestPath}`)
    const isInsideApp = requestedFile === staticRoot || requestedFile.startsWith(`${staticRoot}${path.sep}`)
    const file = isInsideApp && fs.existsSync(requestedFile) && fs.statSync(requestedFile).isFile()
      ? requestedFile
      : path.join(staticRoot, 'index.html')
    fs.readFile(file, (error, data) => {
      if (error) { response.writeHead(500); response.end('Unable to load desktop resources'); return }
      response.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' })
      response.end(data)
    })
  })
  staticServer.once('error', reject)
  staticServer.listen(0, '127.0.0.1', () => resolve(staticServer.address().port))
})

ipcMain.on('desktop:get-api-base-url', event => { event.returnValue = apiBaseUrl() })
ipcMain.handle('desktop:set-api-base-url', (_, value) => {
  if (!isValidApiBaseUrl(value)) throw new Error('服务地址必须是以 /api 结尾的 HTTP(S) 地址。')
  fs.mkdirSync(path.dirname(configPath()), { recursive: true })
  fs.writeFileSync(configPath(), JSON.stringify({ apiBaseUrl: value.replace(/\/$/, '') }, null, 2), { mode: 0o600 })
})

const createWindow = port => {
  const window = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1080,
    minHeight: 700,
    title: 'ZWX Agent',
    backgroundColor: '#ffffff',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })
  window.loadURL(`http://127.0.0.1:${port}`)
}

app.whenReady().then(async () => { staticPort = await startStaticServer(); createWindow(staticPort) })
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) createWindow(staticPort) })
app.on('before-quit', () => staticServer?.close())

module.exports = { apiBaseUrl, configPath }
