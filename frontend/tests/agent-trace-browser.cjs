const { chromium } = require('playwright')
const assert = require('node:assert/strict')

;(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) })
  const context = await browser.newContext({ viewport: { width: 1280, height: 820 } })
  await context.addInitScript(() => {
    localStorage.setItem('zwx_auth_token', 'isolated-agent-trace-test')
    localStorage.setItem('zwx_auth_user', JSON.stringify({ username: '轨迹测试', role: 'USER' }))
  })
  const startedAt = new Date(Date.now() - 3000).toISOString()
  let calls = []
  const errors = []
  await context.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data = {}
    if (path === '/api/ai/manus/conversations') data = [{ id: 'conv-1', title: '轨迹测试', updatedAt: startedAt }]
    else if (path.endsWith('/conversations/conv-1/messages')) data = [
      { id: 1, role: 'USER', content: '检查服务', createdAt: startedAt }
    ]
    else if (path.endsWith('/conversations/conv-1/runs')) data = [{ id: 'run-1', status: 'RUNNING' }]
    else if (path.endsWith('/conversations/conv-1/runs/run-1')) data = {
      id: 'run-1', status: 'RUNNING', userMessageId: 1, createdAt: startedAt,
      events: [{ phase: 'work', agent: '调研员', summary: '开始执行：检查服务', workerKey: 'worker-1-researcher' }]
    }
    else if (path.endsWith('/conversations/conv-1/runs/run-1/tool-calls')) data = calls
    else if (path.endsWith('/projects') || path.endsWith('/workspace/files')) data = []
    else if (path.endsWith('/settings/catalog')) data = { providers: [], userBindings: {} }
    else if (path.endsWith('/settings/permission')) data = { mode: 'EDIT' }
    else if (path.endsWith('/runs/run-1/web-sources')) data = []
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) })
  })
  const page = await context.newPage()
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('requestfailed', request => errors.push(`${request.url()} ${request.failure()?.errorText}`))
  try {
    await page.goto('http://127.0.0.1:3000/super-agent')
    await page.locator('.trace-toggle').waitFor()
    assert.match(await page.locator('.trace-toggle').innerText(), /工作中/)
    assert.equal(await page.locator('.thinking-state').count(), 0)
    if (await page.locator('.trace-toggle').getAttribute('aria-expanded') === 'false') await page.locator('.trace-toggle').click()
    calls = [{ id: 11, worker_key: 'worker-1-researcher', tool_name: 'searchWeb',
      arguments: '{"query":"Spring Boot MCP"}', status: 'PENDING', result_preview: '', created_at: new Date().toISOString() }]
    await page.getByText('调用中', { exact: true }).waitFor({ timeout: 8000 })
    assert.equal(await page.getByRole('button', { name: /调研员 #1/ }).count() > 0, true)
    assert.match(await page.locator('.chain-entries').innerText(), /Spring Boot MCP/)
    await page.getByRole('button', { name: '调研员 #1', exact: true }).click()
    assert.match(await page.locator('.chain-entries').innerText(), /搜索网页/)
    calls = [{ ...calls[0], status: 'SUCCEEDED', result_preview: '找到服务说明' }]
    await page.getByText('已返回', { exact: true }).waitFor({ timeout: 8000 })
    await page.screenshot({ path: '/tmp/aster-agent-trace-desktop.png' })
    await page.setViewportSize({ width: 375, height: 812 })
    await page.waitForTimeout(350)
    assert.equal(await page.locator('.trace-toggle').evaluate(el => getComputedStyle(el).whiteSpace), 'nowrap')
    await page.screenshot({ path: '/tmp/aster-agent-trace-mobile.png' })
    assert.deepEqual(errors, [])
    console.log('Agent trace live polling, worker filter and mobile status passed')
  } catch (error) {
    console.error('Page:', page.url(), (await page.locator('body').innerText()).slice(0, 900), errors)
    throw error
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
