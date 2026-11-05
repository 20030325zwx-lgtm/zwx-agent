// Isolated browser regression: all API traffic is mocked; no real generation is submitted.
const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')

;(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) })
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
  await context.addInitScript(() => {
    localStorage.setItem('zwx_auth_token', 'isolated-canvas-test')
    localStorage.setItem('zwx_auth_user', JSON.stringify({ username: '画布测试', role: 'USER' }))
  })
  const page = await context.newPage()
  page.setDefaultTimeout(15000)
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const projectId = '11111111-1111-4111-8111-111111111111'
  let draft = { revision: 1, viewport: { x: 360, y: 140, zoom: 0.9 }, graph: { nodes: [
    { id: 'text-1', type: 'text_input', position: { x: 0, y: 0 }, data: { label: '场景描述', text: '日落时分的海边，暖金色光线，胶片质感。' } },
    { id: 'image-1', type: 'image_generate', position: { x: 360, y: 0 }, data: { label: '生成首帧', prompt: '', modelRowId: 7, size: '1024*1024', status: 'IDLE' } },
    { id: 'video-1', type: 'video_generate', position: { x: 720, y: 0 }, data: { label: '镜头动画', prompt: '镜头缓缓推进，海浪轻轻涌动。', modelRowId: 9, seconds: 5, status: 'IDLE' } }
  ], edges: [] } }
  let submitted = null
  let optimizePayload = null
  let runStatus = 'RUNNING'
  const mockRun = () => ({ id: 'test-run', run: { status: runStatus === 'RUNNING' ? 'WAITING_EXTERNAL' : 'SUCCEEDED', steps: [
    { key: 'image-1', status: runStatus, output: '' },
    { key: 'video-1', status: submitted?.scope === 'up_to' ? runStatus : 'IDLE' }
  ] }, candidates: [] })
  await context.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), pathname = url.pathname
    if (!pathname.startsWith('/api/')) return route.continue()
    let data = {}
    if (pathname.endsWith('/settings/catalog')) data = { providers: [{ name: '测试模型', models: [
      { id: 7, displayName: '图像生成 · 测试', capabilities: ['image_generation'] },
      { id: 9, displayName: '视频生成 · 测试', capabilities: ['video_generation'] }
    ] }], systemBindings: {}, userBindings: {} }
    else if (pathname.endsWith('/canvas')) {
      if (req.method() === 'PUT') draft = { ...req.postDataJSON(), revision: draft.revision + 1 }
      data = draft
    } else if (pathname.endsWith('/canvas/validate')) data = { executableNodes: 1, warnings: [] }
    else if (pathname.endsWith('/canvas/runs')) {
      if (req.method() === 'POST') { submitted = req.postDataJSON(); data = mockRun() }
      else data = submitted ? [mockRun()] : []
    } else if (pathname.endsWith('/canvas/runs/test-run')) data = mockRun()
    else if (pathname.endsWith('/prompts/image-styles')) data = [
      { code: 'PHOTO_MATCH', label: '跟随原片', group: 'photo', hint: '读取参考照片的镜头、光线与调色', needsReference: true },
      { code: 'REALISTIC', label: '自然写实', group: 'photo', hint: '通用真实照片质感', needsReference: false },
      { code: 'CINEMATIC', label: '电影感', group: 'photo', hint: '宽银幕构图与低调光', needsReference: false },
      { code: 'COMIC', label: '漫画插画', group: 'illustration', hint: '墨线描边＋赛璐璐平涂', needsReference: false }
    ]
    else if (pathname.endsWith('/prompts/optimize')) {
      optimizePayload = req.postDataJSON()
      data = { originalPrompt: optimizePayload.prompt, optimizedPrompt: '优化后的提示词', modelName: 'mock' }
    }
    else if (pathname.endsWith('/projects/' + projectId)) data = { id: projectId, name: '海边日落 · 创作画布', kind: 'VIDEO', status: 'ACTIVE' }
    else if (pathname.endsWith('/jobs') || pathname.endsWith('/assets')) data = { items: [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) })
  })
  const out = process.env.CANVAS_TEST_OUTPUT || '/tmp/aster-canvas-verification'
  fs.mkdirSync(out, { recursive: true })
  try {
    await page.goto('http://127.0.0.1:3000/creative/projects/' + projectId + '/canvas')
    await page.locator('.graph-card').first().waitFor()
    await page.getByTitle('适应画布', { exact: true }).click()
    console.log('Canvas mounted')
    assert.equal(await page.locator('.graph-card.kind-start').count(), 1)
    await page.locator('.graph-card.kind-start .card-head').click()
    assert.equal(await page.getByTitle('删除节点', { exact: true }).count(), 0)
    assert.equal(await page.locator('.canvas-inspector textarea').count(), 0)
    const baseEdges = await page.locator('.vue-flow__edge').count()
    assert.equal(baseEdges, 3, 'legacy roots connect to start')
    async function connect(source, sourcePort, target, targetPort) {
      const a = await page.locator(`[data-id="${source}"] [data-handleid="${sourcePort}"]`).boundingBox()
      const b = await page.locator(`[data-id="${target}"] [data-handleid="${targetPort}"]`).boundingBox()
      assert.ok(a && b, 'both connection handles must be visible')
      await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2)
      await page.mouse.down()
      await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 24 })
      await page.mouse.up()
    }
    await page.locator('[data-id="image-1"] .card-head').click()
    await page.getByRole('button', { name: '断开 开始', exact: true }).click()
    assert.equal(await page.locator('.vue-flow__edge').count(), baseEdges - 1)
    await connect('canvas-start', 'flow-out', 'image-1', 'flow-in')
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges)
    await connect('text-1', 'text-out', 'image-1', 'text-in')
    console.log('Dragged first text connection')
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 1)
    assert.match(await page.locator('[aria-label="上游输入"]').innerText(), /日落时分的海边/)
    assert.equal(await page.locator('.canvas-run').isEnabled(), true, 'upstream-only prompt should enable generation')
    await page.locator('.canvas-run').click()
    // 运行开始后按钮必须显示「正在执行」并禁用，防止重复提交与重复计费
    await page.waitForFunction(() => {
      const button = document.querySelector('.canvas-run')
      return button?.textContent.includes('正在执行') && button.disabled
    })
    assert.match(await page.locator('.canvas-run').getAttribute('title'), /正在执行/)
    assert.equal(submitted?.nodeId, 'image-1')
    await connect('image-1', 'media-out', 'video-1', 'image-in')
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 3)
    assert.match(await page.locator('.canvas-notice.info').innerText(), /已同时连接提示词与图片素材/,
      'generation-to-generation drag should auto-add the prompt sibling edge')
    await page.getByLabel('关闭提示', { exact: true }).click()
    assert.equal(await page.locator('.canvas-run').isDisabled(), true, 'running parent must block downstream')
    assert.match(await page.locator('.canvas-run').getAttribute('title'), /正在执行/)
    assert.match(await page.locator('[aria-label="上游输入"]').innerText(), /上游生成并选定结果后/)
    await connect('image-1', 'text-out', 'video-1', 'text-in')
    assert.match(await page.locator('.canvas-notice').innerText(), /已经连接/, 'duplicate sibling edge stays rejected')
    assert.equal(await page.locator('.vue-flow__edge').count(), baseEdges + 3)
    await page.getByLabel('关闭提示', { exact: true }).click()
    runStatus = 'SUCCEEDED'
    await page.waitForFunction(() => !document.querySelector('.canvas-run').disabled)
    assert.match(await page.locator('.canvas-run').innerText(), /生成当前节点|执行到此节点|从此继续|运行整图/,
      'run finished should restore the normal label')
    await connect('video-1', 'text-out', 'image-1', 'text-in')
    assert.match(await page.locator('.canvas-notice').innerText(), /循环/)
    assert.equal(await page.locator('.vue-flow__edge').count(), baseEdges + 3)
    await page.getByLabel('关闭提示', { exact: true }).click()
    await page.locator('[data-id="video-1"] .card-head').click()
    await page.locator('.effective-prompt summary').click()
    assert.match(await page.locator('.effective-prompt p').innerText(), /日落时分.*海边[\s\S]*镜头缓缓/)
    await page.waitForFunction(() => document.querySelector('.canvas-save')?.textContent.includes('已保存'))
    assert.equal(draft.graph.edges.length, baseEdges + 3)
    runStatus = 'RUNNING'
    await page.locator('.inspector-run .run-to-here').click()
    await page.waitForFunction(() => document.querySelector('[data-id="video-1"] .state-badge')?.textContent.includes('生成中'))
    assert.equal(submitted?.scope, 'up_to', 'run-to-here submits the up_to scope')
    assert.equal(submitted?.nodeId, 'video-1')
    runStatus = 'SUCCEEDED'
    await page.waitForFunction(() => document.querySelector('[data-id="video-1"] .state-badge')?.textContent.includes('已完成'))
    assert.ok(draft.graph.edges.every(edge => edge.sourceHandle && edge.targetHandle))
    await page.reload()
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 3)
    assert.equal(await page.locator('.graph-card.kind-start').count(), 1)
    await page.locator('[data-id="video-1"] .card-head').click()
    await page.locator('.effective-prompt summary').click()
    const layout = await page.locator('.canvas-stage').boundingBox()
    const main = await page.locator('.wb-content').boundingBox()
    assert.ok(layout.width > 1200 && Math.abs(layout.x - main.x) < 2 && Math.abs(layout.width - main.width) < 2, JSON.stringify(layout))
    await page.getByRole('button', { name: '断开 生成首帧', exact: true }).last().click()
    assert.equal(await page.locator('.vue-flow__edge').count(), baseEdges + 2)
    await page.getByTitle('撤销', { exact: true }).click()
    assert.equal(await page.locator('.vue-flow__edge').count(), baseEdges + 3)
    const before = draft.graph.nodes.find(node => node.id === 'text-1').position.y
    const head = await page.locator('[data-id="text-1"] .card-head').boundingBox()
    await page.mouse.move(head.x + 60, head.y + 20)
    await page.mouse.down()
    await page.mouse.move(head.x + 60, head.y + 70, { steps: 12 })
    await page.mouse.up()
    await page.waitForFunction(() => document.querySelector('.canvas-save')?.textContent.includes('已保存'))
    assert.ok(draft.graph.nodes.find(node => node.id === 'text-1').position.y > before + 20, 'node drag must persist')
    // 视频目标的一次拖线双连需确认：拒绝时只连提示词，接受时同时设首帧
    await page.locator('[data-id="video-1"] .card-head').click()
    const videoBindings = page.locator('.canvas-inspector').getByRole('button', { name: '断开 生成首帧', exact: true })
    await videoBindings.first().click()
    await videoBindings.click()
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 1)
    page.once('dialog', dialog => dialog.dismiss())
    await connect('image-1', 'text-out', 'video-1', 'text-in')
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 2)
    assert.equal(await page.locator('.canvas-notice.info').count(), 0, 'declining the confirm adds only the dragged edge')
    await page.locator('.canvas-inspector').getByRole('button', { name: '断开 生成首帧', exact: true }).click()
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 1)
    page.once('dialog', dialog => dialog.accept())
    await connect('image-1', 'text-out', 'video-1', 'text-in')
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 3)
    assert.match(await page.locator('.canvas-notice.info').innerText(), /首帧/)
    await page.getByLabel('关闭提示', { exact: true }).click()
    // 连线删除：双击、或选中后按 Backspace；节点同样支持键盘删除，开始节点受保护
    await page.locator('.vue-flow__edge[data-id*="text-1text-out"]').dblclick()
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 2)
    await page.locator('.vue-flow__edge[data-id*="canvas-start-text-1"]').click()
    await page.keyboard.press('Backspace')
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 1)
    await page.getByTitle('撤销', { exact: true }).click()
    await page.getByTitle('撤销', { exact: true }).click()
    await page.waitForFunction(count => document.querySelectorAll('.vue-flow__edge').length === count, baseEdges + 3)
    await page.locator('[data-id="video-1"] .card-head').click()
    await page.keyboard.press('Backspace')
    await page.waitForFunction(() => document.querySelectorAll('.vue-flow__node').length === 3)
    await page.getByTitle('撤销', { exact: true }).click()
    await page.waitForFunction(() => document.querySelectorAll('.vue-flow__node').length === 4)
    await page.locator('.graph-card.kind-start .card-head').click()
    await page.keyboard.press('Backspace')
    assert.equal(await page.locator('.vue-flow__node').count(), 4, 'start node must survive Backspace')
    // 图片风格：目录驱动分组下拉、无参考图时禁用「跟随原片」、按节点记忆并随请求提交
    await page.locator('[data-id="image-1"] .card-head').click()
    const styleSelect = page.locator('.canvas-inspector select[aria-label="图片风格"]')
    assert.equal(await styleSelect.count(), 1)
    assert.equal(await styleSelect.locator('optgroup').count(), 2, '风格按摄影/插画分组')
    assert.equal(await styleSelect.inputValue(), 'REALISTIC', '无参考图默认自然写实')
    assert.equal(await styleSelect.locator('option[value="PHOTO_MATCH"]').isDisabled(), true,
      '跟随原片需要参考图，未连接时禁用')
    await styleSelect.selectOption('CINEMATIC')
    assert.match(await page.locator('.style-hint').innerText(), /宽银幕/)
    await page.locator('.canvas-inspector textarea').fill('黄昏的城市天台，风把外套吹起')
    await page.getByRole('button', { name: /优化提示词/ }).click()
    await page.waitForFunction(() => document.querySelector('.optimized-preview'))
    assert.equal(optimizePayload?.imageStyle, 'CINEMATIC')
    assert.deepEqual(optimizePayload?.referenceAssetIds, [], '非跟随原片不上传参考图')
    await page.locator('[data-id="video-1"] .card-head').click()
    await page.screenshot({ path: path.join(out, 'canvas-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 1024, height: 768 })
    await page.getByTitle('适应画布', { exact: true }).click()
    await page.screenshot({ path: path.join(out, 'canvas-compact.png'), fullPage: true })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ passed: true, checks: ['single protected default start', 'drag flow/text/image links', 'dual-connect prompt+image between generation nodes', 'video first-frame confirm', 'edge delete via double-click and Backspace, node via Backspace, start protected', 'unfinished parent blocks execution', 'completed parent unblocks execution', 'run-to-here up_to skips succeeded ancestors', 'upstream-only generation', 'recursive prompt preview', 'cycle rejection', 'save and reload', 'disconnect and undo', 'drag node and persist', 'central layout', 'compact viewport', 'image style catalog select'], edges: draft.graph.edges, screenshots: out }, null, 2))
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true })
    console.error('Page errors:', errors)
    throw error
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
