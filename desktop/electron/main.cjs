const { app, BrowserWindow, dialog, ipcMain, safeStorage } = require('electron')
const { spawn, spawnSync } = require('child_process')
const crypto = require('crypto')
const http = require('http')
const net = require('net')
const path = require('path')
const fs = require('fs')

const defaultApiBaseUrl = 'http://127.0.0.1:8123/api'
const defaultOssEndpoint = 'https://oss-cn-hangzhou.aliyuncs.com'
const defaultOssBucket = 'zwx-agent'
const staticRoot = path.join(__dirname, '..', 'app')
const secretKeys = ['dashscopeApiKey', 'searchApiKey', 'postgresPassword', 'ossAccessKeyId', 'ossAccessKeySecret']
const HOSTED_DB_NAME = 'aster'
const HOSTED_DB_USER = 'aster'
const BACKEND_READY_TIMEOUT_MS = 90_000
let staticServer
let staticPort
let backendProcess
let postgresProcess
let runtimeState = { phase: 'starting', backendPort: null, loopbackToken: '', error: '' }

const configPath = () => path.join(app.getPath('userData'), 'desktop-config.json')
const readConfig = () => { try { return JSON.parse(fs.readFileSync(configPath(), 'utf8')) } catch { return {} } }
const writeConfig = config => {
  fs.mkdirSync(path.dirname(configPath()), { recursive: true })
  fs.writeFileSync(configPath(), JSON.stringify(config, null, 2), { mode: 0o600 })
}
const encryptSecret = value => ({ encrypted: safeStorage.encryptString(value).toString('base64') })
const decryptSecret = record => { try { return record?.encrypted ? safeStorage.decryptString(Buffer.from(record.encrypted, 'base64')) : '' } catch { return '' } }
const readSecret = (config, key) => decryptSecret(config.secrets?.[key])
const backendPort = config => Number(config.backendPort) || 0
// 全新安装无 desktop-config.json：桌面产品默认本机独立运行
const isLocalMode = config => (config.backendMode ?? 'local') === 'local'
const apiBaseUrl = () => {
  const config = readConfig()
  return isLocalMode(config) ? `http://127.0.0.1:${runtimeState.backendPort || backendPort(config) || 8123}/api` : config.apiBaseUrl || defaultApiBaseUrl
}
const isValidApiBaseUrl = value => {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && url.pathname.replace(/\/$/, '') === '/api' } catch { return false }
}
const isValidJdbcUrl = value => !value || /^jdbc:postgresql:\/\/[^\s]+/i.test(value)
const resourcePath = (...segments) => app.isPackaged ? path.join(process.resourcesPath, ...segments) : path.join(__dirname, '..', ...segments)
const settingsView = config => ({
  backendMode: isLocalMode(config) ? 'local' : 'remote',
  apiBaseUrl: config.apiBaseUrl || defaultApiBaseUrl,
  backendPort: backendPort(config),
  postgresUrl: config.postgresUrl || '',
  postgresUsername: config.postgresUsername || '',
  ossEndpoint: config.ossEndpoint || defaultOssEndpoint,
  ossBucket: config.ossBucket || defaultOssBucket,
  tikaBaseUrl: config.tikaBaseUrl || '',
  secrets: Object.fromEntries(secretKeys.map(key => [key, readSecret(config, key) ? '*******' : '']))
})
const logDir = () => path.join(app.getPath('userData'), 'logs')
const logStream = name => {
  fs.mkdirSync(logDir(), { recursive: true })
  return fs.createWriteStream(path.join(logDir(), name), { flags: 'a' })
}
const randomToken = () => crypto.randomBytes(32).toString('hex')
const readGrants = config => Array.isArray(config.grantedDirectories) ? config.grantedDirectories : []

const contentType = filename => ({
  '.css': 'text/css; charset=utf-8', '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'
}[path.extname(filename)] || 'application/octet-stream')
const staticHandler = (request, response) => {
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
}
// 固定端口让 localStorage（主题、导航/任务栏折叠状态）在多次启动之间保持；
// 端口被占用时退回随机端口，避免第二个实例启动失败。
const PREFERRED_STATIC_PORT = 47821
const listenStatic = port => new Promise((resolve, reject) => {
  const server = http.createServer(staticHandler)
  server.once('error', reject)
  server.listen(port, '127.0.0.1', () => resolve(server))
})
const startStaticServer = async () => {
  try {
    staticServer = await listenStatic(PREFERRED_STATIC_PORT)
  } catch (error) {
    if (error.code !== 'EADDRINUSE') throw error
    staticServer = await listenStatic(0)
  }
  return staticServer.address().port
}

// 启动失败/服务不可用时展示诊断页：原因 + 日志位置 + 重试
const errorPageHtml = message => `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><body style="font-family:-apple-system,sans-serif;background:#0f111a;color:#e9ebf5;display:grid;place-items:center;height:100vh;margin:0"><div style="max-width:560px;text-align:center"><h1 style="font-size:20px">Aster 本机服务未能就绪</h1><p style="color:#a9aec4;line-height:1.7">${message}</p><p style="color:#71778e;font-size:13px">日志位置：${logDir()}</p><button onclick="location.reload()" style="padding:8px 20px;border-radius:10px;border:0;background:#2E6BE6;color:#fff;font-size:14px;cursor:pointer">重试</button></div></body></html>`

const fetchHealth = port => new Promise(resolve => {
  const request = http.get({ host: '127.0.0.1', port, path: '/api/health', timeout: 2000 }, response => {
    response.resume()
    resolve(response.statusCode === 200)
  })
  request.on('error', () => resolve(false))
  request.on('timeout', () => { request.destroy(); resolve(false) })
})

const getFreePort = () => new Promise((resolve, reject) => {
  const server = net.createServer()
  server.once('error', reject)
  server.listen(0, '127.0.0.1', () => {
    const { port } = server.address()
    server.close(() => resolve(port))
  })
})

// ---------------------------------------------------------------- 托管 PostgreSQL

const bundledPostgresRoot = () => {
  const root = resourcePath('pgserver')
  return fs.existsSync(path.join(root, 'bin', 'postgres')) ? root : null
}

const ensureHostedDatabase = config => {
  const pgRoot = bundledPostgresRoot()
  if (!pgRoot) return null // 未捆绑或用户自备数据库
  if (config.postgresUrl) return null // 用户显式指定外部数据库
  const dataDir = path.join(app.getPath('userData'), 'pgdata')
  const bin = name => path.join(pgRoot, 'bin', name)
  const env = { ...process.env, LC_ALL: 'C', DYLD_LIBRARY_PATH: path.join(pgRoot, 'lib') }
  fs.mkdirSync(dataDir, { recursive: true })
  const versionFile = path.join(dataDir, 'PG_VERSION')
  if (!fs.existsSync(versionFile)) {
    const init = spawnSync(bin('initdb'), ['-D', dataDir, '-U', HOSTED_DB_USER, '-A', 'trust', '-E', 'UTF8'],
      { env, encoding: 'utf8' })
    if (init.status !== 0) throw new Error(`数据库初始化失败：${(init.stderr || init.stdout || '').slice(-400)}`)
    // 服务器启动前用 single-user 模式建库（精简包没有 createdb/psql；运行中无法使用 --single）
    const createDb = spawnSync(bin('postgres'), ['--single', '-D', dataDir, '-E', 'postgres'],
      { env, encoding: 'utf8', input: `CREATE DATABASE ${HOSTED_DB_NAME} OWNER ${HOSTED_DB_USER};\n` })
    if (createDb.status !== 0) {
      const combined = `${createDb.stdout || ''}${createDb.stderr || ''}`
      if (!/already exists/.test(combined)) {
        throw new Error(`数据库创建失败：${combined.slice(-400)}`)
      }
    }
  }
  const port = Number(config.hostedPostgresPort) || 0
  const effectivePort = port || 54329
  // unix socket 目录必须先于服务器启动存在
  fs.mkdirSync(path.join(app.getPath('userData'), 'tmp'), { recursive: true })
  fs.mkdirSync(logDir(), { recursive: true })
  const started = spawnSync(bin('pg_ctl'), ['-D', dataDir, '-l', path.join(logDir(), 'postgres.log'),
    '-o', `-p ${effectivePort} -c listen_addresses=127.0.0.1`,
    '-w', '-t', '30', 'start'], { env, encoding: 'utf8' })
  if (started.status !== 0) {
    // 已在运行（例如上次未正常退出）：先停再起
    spawnSync(bin('pg_ctl'), ['-D', dataDir, '-m', 'fast', 'stop'], { env, encoding: 'utf8' })
    const retry = spawnSync(bin('pg_ctl'), ['-D', dataDir, '-l', path.join(logDir(), 'postgres.log'),
      '-o', `-p ${effectivePort} -c listen_addresses=127.0.0.1`,
      '-w', '-t', '30', 'start'], { env, encoding: 'utf8' })
    if (retry.status !== 0) throw new Error(`数据库启动失败：${(retry.stderr || retry.stdout || '').slice(-400)}`)
  }
  postgresProcess = { dataDir, env }
  return { port: effectivePort, dataDir, env }
}

const stopHostedDatabase = () => {
  if (!postgresProcess) return
  try {
    spawnSync(path.join(bundledPostgresRoot() || '', 'bin', 'pg_ctl'),
      ['-D', postgresProcess.dataDir, '-m', 'fast', 'stop'], { encoding: 'utf8' })
  } catch { /* 退出时尽力而为 */ }
  postgresProcess = null
}

// ---------------------------------------------------------------- 本机后端

const waitForBackend = async port => {
  const deadline = Date.now() + BACKEND_READY_TIMEOUT_MS
  while (Date.now() < deadline) {
    if (await fetchHealth(port)) return true
    if (!backendProcess || backendProcess.exitCode !== null) return false
    await new Promise(resolve => setTimeout(resolve, 700))
  }
  return false
}

// 把用户授权的目录清单同步给本机后端（回环令牌认证）；后端以此校验所有目录绑定
const pushDirectoryGrants = async () => {
  const port = runtimeState.backendPort
  const token = runtimeState.loopbackToken
  if (!port || !token) return
  try {
    const response = await fetch(`http://127.0.0.1:${port}/api/ai/desktop/grants`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Aster-Local-Token': token },
      body: JSON.stringify({ paths: readGrants(readConfig()) })
    })
    console.log('[aster-desktop] 目录授权同步:', response.status, '目录数', readGrants(readConfig()).length)
  } catch (error) {
    console.error('[aster-desktop] 目录授权同步失败:', error.message)
  }
}

const startLocalBackend = async () => {
  const config = readConfig()
  if (!isLocalMode(config)) {
    runtimeState = { phase: 'remote', backendPort: null, loopbackToken: '', error: '' }
    return
  }
  runtimeState = { phase: 'starting', backendPort: null, loopbackToken: '', error: '' }
  const jar = resourcePath('server', 'aster-agent.jar')
  const bundledJava = resourcePath('runtime', 'bin', 'java')
  if (!fs.existsSync(jar)) {
    runtimeState.error = '内置服务缺失（server/aster-agent.jar），安装包可能不完整。'
    return
  }
  try {
    const requestedPort = backendPort(config)
    const port = requestedPort || await getFreePort()
    const managementPort = await getFreePort()
    let database = null
    if (!config.postgresUrl) {
      database = ensureHostedDatabase(config)
      if (database) {
        runtimeState.hostedPostgresPort = database.port
        writeConfig({ ...config, hostedPostgresPort: database.port })
      }
    }
    const loopbackToken = randomToken()
    const adminPassword = readSecret(config, 'adminPassword') || randomToken().slice(0, 32)
    const tempDir = path.join(app.getPath('userData'), 'temp')
    fs.mkdirSync(tempDir, { recursive: true })
    const env = {
      ...process.env,
      SERVER_PORT: String(port),
      MANAGEMENT_SERVER_PORT: String(managementPort),
      SPRING_PROFILES_ACTIVE: 'desktop',
      APP_TEMP_DIR: tempDir,
      APP_DESKTOP_LOOPBACK_TOKEN: loopbackToken,
      APP_SECURITY_ADMIN_PASSWORD: adminPassword,
      APP_SECURITY_ADMIN_USERNAME: 'admin',
      SPRING_DATASOURCE_URL: database
        ? `jdbc:postgresql://127.0.0.1:${database.port}/${HOSTED_DB_NAME}`
        : (config.postgresUrl || ''),
      SPRING_DATASOURCE_USERNAME: database ? HOSTED_DB_USER : (config.postgresUsername || '')
    }
    if (!database && adminPassword) env.SPRING_DATASOURCE_PASSWORD = readSecret(config, 'postgresPassword') || ''
    if (config.tikaBaseUrl) env.APP_TIKA_BASE_URL = config.tikaBaseUrl
    const dashscopeApiKey = readSecret(config, 'dashscopeApiKey')
    const searchApiKey = readSecret(config, 'searchApiKey')
    const ossAccessKeyId = readSecret(config, 'ossAccessKeyId')
    const ossAccessKeySecret = readSecret(config, 'ossAccessKeySecret')
    // 供应商密钥已支持在应用内「模型设置」配置；此处环境变量仅作首次引导的兜底
    if (dashscopeApiKey) env.SPRING_AI_DASHSCOPE_API_KEY = dashscopeApiKey
    if (searchApiKey) env.SEARCH_API_API_KEY = searchApiKey
    if (ossAccessKeyId) env.ALIYUN_OSS_ACCESS_KEY_ID = ossAccessKeyId
    if (ossAccessKeySecret) env.ALIYUN_OSS_ACCESS_KEY_SECRET = ossAccessKeySecret
    if (database) {
      // trust 认证下仍传占位密码，避免 Spring 因空密码告警
      env.SPRING_DATASOURCE_PASSWORD = 'hosted'
      writeConfig({ ...readConfig(), secrets: { ...(readConfig().secrets || {}), adminPassword: encryptSecret(adminPassword) } })
    }
    runtimeState.loopbackToken = loopbackToken
    runtimeState.backendPort = port
    const out = logStream('backend.log')
    const err = logStream('backend.err.log')
    backendProcess = spawn(fs.existsSync(bundledJava) ? bundledJava : 'java', ['-jar', jar],
      { env, stdio: ['ignore', 'pipe', 'pipe'] })
    backendProcess.stdout.pipe(out)
    backendProcess.stderr.pipe(err)
    backendProcess.once('exit', (code) => {
      backendProcess = undefined
      if (runtimeState.phase === 'starting') {
        runtimeState.error = `本机服务进程退出（代码 ${code}），请查看日志。`
      }
    })
    const ready = await waitForBackend(port)
    if (!ready && !runtimeState.error) runtimeState.error = '本机服务在 90 秒内未就绪，请查看日志。'
    runtimeState.phase = ready ? 'ready' : 'failed'
    if (ready) await pushDirectoryGrants()
  } catch (error) {
    console.error('[aster-desktop] 本机服务启动失败:', error.message || error)
    runtimeState.phase = 'failed'
    runtimeState.error = String(error.message || error)
  }
}

ipcMain.on('desktop:get-api-base-url', event => { event.returnValue = apiBaseUrl() })
ipcMain.on('desktop:get-settings', event => { event.returnValue = settingsView(readConfig()) })
ipcMain.on('desktop:get-local-token', event => { event.returnValue = runtimeState.loopbackToken || '' })
ipcMain.on('desktop:get-runtime-state', event => { event.returnValue = { ...runtimeState, apiBaseUrl: apiBaseUrl() } })
ipcMain.handle('desktop:choose-directory', async () => {
  const window = BrowserWindow.getAllWindows()[0]
  const result = await dialog.showOpenDialog(window, {
    title: '选择要授权给 Aster 的目录',
    properties: ['openDirectory']
  })
  if (result.canceled || !result.filePaths.length) return null
  const path = result.filePaths[0]
  const config = readConfig()
  const grants = readGrants(config)
  if (!grants.includes(path)) writeConfig({ ...config, grantedDirectories: [...grants, path] })
  await pushDirectoryGrants()
  return path
})
ipcMain.on('desktop:get-directory-grants', event => { event.returnValue = readGrants(readConfig()) })
ipcMain.handle('desktop:revoke-directory-grant', async (_, path) => {
  const config = readConfig()
  const grants = readGrants(config).filter(grant => grant !== path)
  writeConfig({ ...config, grantedDirectories: grants })
  await pushDirectoryGrants()
  return true
})
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

const createWindow = async port => {
  if (runtimeState.phase === 'failed') {
    const window = new BrowserWindow({
      width: 720, height: 480, title: 'Aster',
      webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true }
    })
    window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(errorPageHtml(runtimeState.error))}`)
    return
  }
  const window = new BrowserWindow({
    width: 1440, height: 920, minWidth: 1080, minHeight: 700, title: 'Aster', backgroundColor: '#0f111a',
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  })
  window.loadURL(`http://127.0.0.1:${port}`)
}

app.whenReady().then(async () => {
  await startLocalBackend()
  staticPort = await startStaticServer()
  await createWindow(staticPort)
})
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) createWindow(staticPort) })
app.on('before-quit', () => {
  staticServer?.close()
  backendProcess?.kill()
  stopHostedDatabase()
})
