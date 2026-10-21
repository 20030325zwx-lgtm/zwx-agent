const { app, BrowserWindow, ipcMain, safeStorage } = require('electron')
const { spawn } = require('child_process')
const http = require('http')
const path = require('path')
const fs = require('fs')

const defaultApiBaseUrl = 'http://127.0.0.1:8123/api'
const defaultOssEndpoint = 'https://oss-cn-hangzhou.aliyuncs.com'
const defaultOssBucket = 'zwx-agent'
const staticRoot = path.join(__dirname, '..', 'app')
const secretKeys = ['dashscopeApiKey', 'searchApiKey', 'postgresPassword', 'ossAccessKeyId', 'ossAccessKeySecret']
let staticServer
let staticPort
let backendProcess

const configPath = () => path.join(app.getPath('userData'), 'desktop-config.json')
const readConfig = () => { try { return JSON.parse(fs.readFileSync(configPath(), 'utf8')) } catch { return {} } }
const writeConfig = config => {
  fs.mkdirSync(path.dirname(configPath()), { recursive: true })
  fs.writeFileSync(configPath(), JSON.stringify(config, null, 2), { mode: 0o600 })
}
const encryptSecret = value => ({ encrypted: safeStorage.encryptString(value).toString('base64') })
const decryptSecret = record => { try { return record?.encrypted ? safeStorage.decryptString(Buffer.from(record.encrypted, 'base64')) : '' } catch { return '' } }
const readSecret = (config, key) => decryptSecret(config.secrets?.[key])
const backendPort = config => Number(config.backendPort) || 8123
const apiBaseUrl = () => {
  const config = readConfig()
  return config.backendMode === 'local' ? `http://127.0.0.1:${backendPort(config)}/api` : config.apiBaseUrl || defaultApiBaseUrl
}
const isValidApiBaseUrl = value => {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && url.pathname.replace(/\/$/, '') === '/api' } catch { return false }
}
const isValidJdbcUrl = value => !value || /^jdbc:postgresql:\/\/[^\s]+/i.test(value)
const resourcePath = (...segments) => app.isPackaged ? path.join(process.resourcesPath, ...segments) : path.join(__dirname, '..', ...segments)
const settingsView = config => ({
  backendMode: config.backendMode === 'local' ? 'local' : 'remote',
  apiBaseUrl: config.apiBaseUrl || defaultApiBaseUrl,
  backendPort: backendPort(config),
  postgresUrl: config.postgresUrl || '',
  postgresUsername: config.postgresUsername || '',
  ossEndpoint: config.ossEndpoint || defaultOssEndpoint,
  ossBucket: config.ossBucket || defaultOssBucket,
  tikaBaseUrl: config.tikaBaseUrl || '',
  secrets: Object.fromEntries(secretKeys.map(key => [key, readSecret(config, key) ? '*******' : '']))
})

const contentType = filename => ({
  '.css': 'text/css; charset=utf-8', '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'
}[path.extname(filename)] || 'application/octet-stream')
const startStaticServer = () => new Promise((resolve, reject) => {
  staticServer = http.createServer((request, response) => {
    let requestPath
    try { requestPath = decodeURIComponent((request.url || '/').split('?')[0]) } catch { requestPath = '/' }
    const requestedFile = path.resolve(staticRoot, `.${requestPath}`)
    const inside = requestedFile === staticRoot || requestedFile.startsWith(`${staticRoot}${path.sep}`)
    const file = inside && fs.existsSync(requestedFile) && fs.statSync(requestedFile).isFile() ? requestedFile : path.join(staticRoot, 'index.html')
    fs.readFile(file, (error, data) => {
      if (error) { response.writeHead(500); response.end('Unable to load desktop resources'); return }
      response.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' })
      response.end(data)
    })
  })
  staticServer.once('error', reject)
  staticServer.listen(0, '127.0.0.1', () => resolve(staticServer.address().port))
})

const startLocalBackend = () => {
  const config = readConfig()
  if (config.backendMode !== 'local') return
  const jar = resourcePath('server', 'zwx-agent.jar')
  const bundledJava = resourcePath('runtime', 'bin', 'java')
  if (!fs.existsSync(jar)) return
  const dashscopeApiKey = readSecret(config, 'dashscopeApiKey')
  const searchApiKey = readSecret(config, 'searchApiKey')
  const postgresPassword = readSecret(config, 'postgresPassword')
  const ossAccessKeyId = readSecret(config, 'ossAccessKeyId')
  const ossAccessKeySecret = readSecret(config, 'ossAccessKeySecret')
  const env = {
    ...process.env,
    SERVER_PORT: String(backendPort(config)),
    SPRING_PROFILES_ACTIVE: 'prod', SPRING_SQL_INIT_MODE: 'always',
    SPRING_DATASOURCE_URL: config.postgresUrl || '', SPRING_DATASOURCE_USERNAME: config.postgresUsername || '',
    APP_TIKA_BASE_URL: config.tikaBaseUrl || '', APP_TEMP_DIR: path.join(app.getPath('userData'), 'temp')
  }
  // Leave unset values to Spring's local defaults instead of overriding them with empty strings.
  if (config.ossEndpoint) env.ALIYUN_OSS_ENDPOINT = config.ossEndpoint
  if (config.ossBucket) env.ALIYUN_OSS_BUCKET = config.ossBucket
  // Empty credentials must not override Spring defaults and block application startup.
  if (postgresPassword) env.SPRING_DATASOURCE_PASSWORD = postgresPassword
  if (dashscopeApiKey) env.SPRING_AI_DASHSCOPE_API_KEY = dashscopeApiKey
  if (searchApiKey) env.SEARCH_API_API_KEY = searchApiKey
  if (ossAccessKeyId) env.ALIYUN_OSS_ACCESS_KEY_ID = ossAccessKeyId
  if (ossAccessKeySecret) env.ALIYUN_OSS_ACCESS_KEY_SECRET = ossAccessKeySecret
  fs.mkdirSync(env.APP_TEMP_DIR, { recursive: true })
  backendProcess = spawn(fs.existsSync(bundledJava) ? bundledJava : 'java', ['-jar', jar], { env, stdio: 'ignore' })
  backendProcess.once('exit', () => { backendProcess = undefined })
}

ipcMain.on('desktop:get-api-base-url', event => { event.returnValue = apiBaseUrl() })
ipcMain.on('desktop:get-settings', event => { event.returnValue = settingsView(readConfig()) })
ipcMain.handle('desktop:save-settings', (_, payload) => {
  if (!payload || !['local', 'remote'].includes(payload.backendMode)) throw new Error('无效的运行模式。')
  if (!isValidApiBaseUrl(payload.apiBaseUrl || '')) throw new Error('服务地址必须是以 /api 结尾的 HTTP(S) 地址。')
  if (!isValidJdbcUrl(payload.postgresUrl || '')) throw new Error('PostgreSQL 地址必须是 JDBC URL。')
  const current = readConfig()
  const secrets = { ...(current.secrets || {}) }
  for (const key of secretKeys) {
    const value = String(payload.secrets?.[key] || '').trim()
    if (value && value !== '*******') secrets[key] = encryptSecret(value)
  }
  writeConfig({
    backendMode: payload.backendMode, apiBaseUrl: payload.apiBaseUrl.trim().replace(/\/$/, ''), backendPort: backendPort(payload),
    postgresUrl: String(payload.postgresUrl || '').trim(), postgresUsername: String(payload.postgresUsername || '').trim(),
    ossEndpoint: String(payload.ossEndpoint || '').trim(), ossBucket: String(payload.ossBucket || '').trim(), tikaBaseUrl: String(payload.tikaBaseUrl || '').trim(), secrets
  })
  app.relaunch()
  app.exit(0)
})

const createWindow = port => {
  const window = new BrowserWindow({
    width: 1440, height: 920, minWidth: 1080, minHeight: 700, title: 'ZWX Agent', backgroundColor: '#ffffff',
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  })
  window.loadURL(`http://127.0.0.1:${port}`)
}

app.whenReady().then(async () => { startLocalBackend(); staticPort = await startStaticServer(); createWindow(staticPort) })
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) createWindow(staticPort) })
app.on('before-quit', () => { staticServer?.close(); backendProcess?.kill() })
