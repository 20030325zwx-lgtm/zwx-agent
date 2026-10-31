/**
 * 节点契约注册表（Infinite Canvas 评审第一批 · A1）。
 * 每个节点类型的展示信息、默认参数、输入输出 kind 与必填项在此一处声明；
 * 画布节点库、参数表单、连线自动绑定、运行前预检全部由它驱动。
 *
 * 输出/输入 kind 语义：
 * - image_url：图片 URL 文本（图片搜索输出）
 * - asset：创作素材引用（媒体/海报节点输出 JSON，引用取 {{节点.output.assetId}}）
 * - text：自由文本
 * - json：结构化 JSON 文本（如海报文案）
 *
 * 连线即绑定：把绑定表达式写进目标节点 params（{{source.output}} / {{source.output.assetId}}），
 * 与旧模板的手写表达式格式完全一致——旧图无需迁移；applyGraph 会从 params 反推边的绑定 label。
 */

const CATALOG = {
  start: {
    type: 'start', label: '开始', color: '#4B5563', group: null,
    hint: '运行输入入口',
    help: '开始节点：代表一次运行的输入。运行输入（如关键词）用 {{input.keyword}} 引用；{{start.output}} 是全部输入。',
    defaults: {}, outputs: [], inputs: [], required: []
  },
  search_image: {
    type: 'search_image', label: '图片搜索', color: '#2E6BE6', group: '输入',
    hint: '通过图片搜索获取参考图；参数 keyword',
    help: '图片搜索：keyword 填搜索词（可直接写关键词或引用上游）。输出是图片 URL，可被图片理解/海报合成等节点引用。',
    defaults: { keyword: '{{input.keyword}}' },
    outputs: [{ kind: 'image_url', label: '图片 URL' }],
    inputs: [],
    required: [{ params: ['keyword'] }]
  },
  web_search: {
    type: 'web_search', label: '联网搜索', color: '#C77B1E', group: '输入',
    hint: '搜索网页信息；参数 query',
    help: '联网搜索：query 填搜索词。输出为搜索结果文本（需要配置搜索 API Key）。',
    defaults: { query: '{{input.keyword}}' },
    outputs: [{ kind: 'text', label: '文本' }],
    inputs: [],
    required: [{ params: ['query'] }]
  },
  http_request: {
    type: 'http_request', label: 'REST 请求', color: '#3D7A3D', group: '输入',
    hint: '调用外部 API；参数 method / url / headers / body',
    help: 'REST 请求：method 选 GET/POST 等，url 填地址，headers/body 填 JSON。输出为响应体（截断 4000 字）；非 2xx 视为失败。',
    defaults: { method: 'GET', url: 'https://', headers: '', body: '' },
    outputs: [{ kind: 'text', label: '响应文本' }],
    inputs: [],
    required: [{ params: ['url'] }]
  },
  db_query: {
    type: 'db_query', label: '数据库查询', color: '#8A6D3B', group: '输入',
    hint: '只读 SELECT/WITH 查询；参数 sql',
    help: '数据库查询：sql 只能写单条 SELECT/WITH（最多 200 行、10 秒超时）。输出为结果 JSON。',
    defaults: { sql: 'SELECT 1' },
    outputs: [{ kind: 'text', label: '结果 JSON' }],
    inputs: [],
    required: [{ params: ['sql'] }]
  },
  image_understand: {
    type: 'image_understand', label: '图片理解', color: '#7C5CF0', group: '理解与文本',
    hint: '理解图片内容；参数 image / imageAssetId / question',
    help: '图片理解：image 填图片 URL 或引用上游图片节点输出；imageAssetId 引用创作素材（走视觉模型）；question 填想问的问题。输出为图片描述文本（服务不可用时降级为提示文本）。',
    defaults: { image: '{{search_image.output}}', question: '描述这张图片的内容与卖点' },
    outputs: [{ kind: 'text', label: '描述文本' }],
    inputs: [
      { param: 'image', kind: 'image_url', accept: ['image_url'], label: '图片 URL' },
      { param: 'imageAssetId', kind: 'asset', accept: ['asset'], label: '创作素材' }
    ],
    required: [{ anyOf: ['image', 'imageAssetId'] }]
  },
  llm_text: {
    type: 'llm_text', label: '模型对话', color: '#0F9D8A', group: '理解与文本',
    hint: '与对话模型交互；参数 prompt（含引用）/ system（可选）/ scene（可选）',
    help: '模型对话：prompt 写提示词，可嵌入 {{节点.output}} 与 {{input.字段}} 引用上游结果做二次编辑；system 可选，设定角色；scene 可选，填 novel 时使用「设置→模型」中「小说创作」的场景绑定（未绑定回退通用对话模型）。输出为模型回复。',
    defaults: { prompt: '根据以下信息生成内容：{{input.keyword}}' },
    outputs: [{ kind: 'text', label: '回复文本' }],
    inputs: [], // prompt 是自由文本：连线只表达执行依赖，不自动覆盖手写提示词
    required: [{ params: ['prompt'] }]
  },
  text_template: {
    type: 'text_template', label: '文本模板', color: '#5B6B8C', group: '理解与文本',
    hint: '拼接/封装上游结果；参数 template（含引用）',
    help: '文本模板：template 里可混合文字与引用，如“报告：{{llm_text.output}}”。输出为替换后的文本，不消耗模型。',
    defaults: { template: '{{start.output}}' },
    outputs: [{ kind: 'text', label: '拼装文本' }],
    inputs: [],
    required: [{ params: ['template'] }]
  },
  media_image_generate: {
    type: 'media_image_generate', label: '图片生成（创作）', color: '#8C3FA8', group: '图片生成',
    hint: '提交 AI 创作任务并持久等待；参数 prompt / size / projectId',
    help: '图片生成（创作）：提交到 AI 创作队列并持久等待，服务重启后可继续。输出为 JSON：jobId / assetId / mediaType，可用 {{节点.output.assetId}} 引用到图片理解或海报合成。支持 DashScope 与中转站（OpenAI 兼容）生图模型。按张计费。',
    defaults: { prompt: '为「{{input.keyword}}」生成一张简洁的商品宣传图，纯色背景', size: '1024*1024' },
    outputs: [{ kind: 'asset', label: '图片素材' }],
    inputs: [],
    required: [{ params: ['prompt'] }]
  },
  media_image_edit: {
    type: 'media_image_edit', label: '图片编辑（参考图）', color: '#B03FA8', group: '图片生成',
    hint: '带 1–3 张参考素材生成（如模特试穿）；参数 prompt / imageAssetIds / size',
    help: '图片编辑（参考图）：带 1–3 张本人素材作参考生成新图（中转站 OpenAI images/edits 协议）。imageAssetIds 填素材 ID 列表（逗号分隔或数组，支持 {{节点.output.assetId}} 引用），第一个通常是模特图。输出 JSON：jobId / assetId / mediaType。按张计费。作为末级节点时（如多机位中的侧面/背面）产物会自动归入素材库，无需下游连线。',
    defaults: { prompt: '让参考图中的模特穿上参考图中的上衣和裤子，保持模特脸型与姿态不变，白底商品图', imageAssetIds: '{{start.output.modelAssetId}},{{start.output.topAssetId}},{{start.output.pantsAssetId}}', size: '1024*1024' },
    outputs: [{ kind: 'asset', label: '图片素材' }],
    inputs: [{ param: 'imageAssetIds', kind: 'asset', accept: ['asset'], multiple: true, max: 3, label: '参考素材' }],
    required: [{ params: ['prompt'] }, { params: ['imageAssetIds'] }]
  },
  image_generate: {
    type: 'image_generate', label: '图片生成（旧）', color: '#A34FA8', group: '图片生成',
    hint: '同步轮询旧链路；参数 prompt / size / model（保留兼容）',
    help: '图片生成（旧）：仅支持 DashScope 协议绑定，同步轮询约 120 秒；新流程建议改用「图片生成（创作）」——它走 AI 创作任务队列、可持久等待恢复，且能用模型设置里通过中转站接入的生图模型。',
    defaults: { prompt: '为「{{input.keyword}}」生成一张简洁的商品宣传图，纯色背景', size: '1024*1024', model: 'wanx2.1-t2i-turbo' },
    outputs: [{ kind: 'asset', label: '图片素材' }],
    inputs: [],
    required: [{ params: ['prompt'] }]
  },
  poster_render: {
    type: 'poster_render', label: '海报合成', color: '#C2455E', group: '合成与导出',
    hint: '合成海报 PNG；参数 image / imageAssetId / copy',
    help: '海报合成：image 引用商品图 URL，或 imageAssetId 引用创作素材；copy 引用文案 JSON（标题/副标题/卖点/配色）。输出为海报 PNG 并自动归入项目素材库。',
    defaults: { image: '{{search_image.output}}', copy: '{{llm_text.output}}' },
    outputs: [{ kind: 'asset', label: '海报素材' }],
    inputs: [
      { param: 'image', kind: 'image_url', accept: ['image_url'], label: '商品图 URL' },
      { param: 'imageAssetId', kind: 'asset', accept: ['asset'], label: '商品素材' },
      { param: 'copy', kind: 'json', accept: ['json', 'text'], label: '文案' }
    ],
    required: [{ anyOf: ['image', 'imageAssetId'] }]
  },
  file_export: {
    type: 'file_export', label: '文件导出', color: '#B0562A', group: '合成与导出',
    hint: '把文本保存为可下载文件；参数 filename / content',
    help: '文件导出：filename 写文件名（md/txt/json/csv），content 通常引用上游文本。输出为可下载文件。',
    defaults: { filename: 'output.md', content: '{{llm_text.output}}' },
    outputs: [{ kind: 'file', label: '文件' }],
    inputs: [{ param: 'content', kind: 'text', accept: ['text', 'json'], label: '内容' }],
    required: [{ params: ['content'] }]
  },
  novel_context: {
    type: 'novel_context', label: '章节上下文（小说）', color: '#9A5B2E', group: '小说',
    hint: '读取作品设定 + 章节目录 + 近 N 章概要；参数 summaryCount',
    help: '章节上下文（小说）：读取运行绑定项目的作品设定（人物/世界观/文风）、章节目录与近 N 章概要（参数 summaryCount，默认 3）。输出纯文本上下文块，供模型对话节点整体引用 {{节点.output}}；概要随章节确认不断累积——这是创作层与单次画布运行的区别。必须从创作台绑定项目发起运行。',
    defaults: { summaryCount: 3 },
    outputs: [{ kind: 'text', label: '上下文文本' }],
    inputs: [],
    required: []
  },
  novel_chapter_save: {
    type: 'novel_chapter_save', label: '章节入库（小说）', color: '#7A4E2E', group: '小说',
    hint: '把正文/概要存为章节草稿；参数 title / content / summary / plotDirection',
    help: '章节入库（小说）：把上游正文与概要写回绑定项目的章节库（草稿，创作台可编辑/确认）。参数 content（正文，必填）、title（可空，自动按「第N章」命名）、summary、plotDirection。同一运行幂等，崩溃重跑不会产生重复章节。必须从创作台绑定项目发起运行。',
    defaults: { title: '{{input.title}}', content: '{{llm_text.output}}', summary: '{{summarize.output}}', plotDirection: '{{input.plotDirection}}' },
    outputs: [{ kind: 'json', label: '章节 JSON' }],
    inputs: [
      { param: 'content', kind: 'text', accept: ['text'], label: '正文' },
      { param: 'summary', kind: 'text', accept: ['text'], label: '概要' }
    ],
    required: [{ params: ['content'] }]
  }
}

export const NODE_CATALOG = CATALOG

/** 节点库分组顺序（start 不出现在库中）。 */
export const NODE_GROUPS = ['输入', '理解与文本', '图片生成', '合成与导出', '小说']
  .map(name => ({
    name,
    items: Object.values(CATALOG).filter(spec => spec.group === name)
  }))

/** 平铺清单（label/颜色查询等兼容用法）。 */
export const NODE_TYPES = Object.values(CATALOG)

/** 参数值较长需要 textarea 的 key。 */
export const LONG_PARAM_KEYS = new Set(['prompt', 'body', 'sql', 'template', 'content', 'question', 'summary', 'plotDirection'])

export function nodeSpec(type) {
  return CATALOG[type] || null
}

export function nodeTypeLabel(type) {
  return CATALOG[type]?.label || type
}

export function nodeColor(type) {
  return CATALOG[type]?.color || '#8A90A6'
}

/** 源节点输出 kind（未知类型返回 null）。 */
export function outputKind(type) {
  return CATALOG[type]?.outputs?.[0]?.kind || null
}

/**
 * 连线绑定匹配：返回
 * - { candidates, src }：存在 kind 兼容的可绑定输入（按声明顺序，调用方结合 params 取第一个可用的）
 * - { dependentOnly: true }：目标只有自由文本输入（llm_text/text_template），允许作为执行依赖但不自动绑定
 * - null：类型不兼容，应拒绝连线
 */
export function matchBinding(sourceType, targetType) {
  if (sourceType === 'start') return { dependentOnly: true } // 开始节点是运行输入入口，可连一切作执行依赖
  const src = outputKind(sourceType)
  const spec = CATALOG[targetType]
  if (!src || !spec) return null
  const candidates = (spec.inputs || []).filter(input => (input.accept || [input.kind]).includes(src))
  if (candidates.length) return { candidates, src }
  if (targetType === 'llm_text' || targetType === 'text_template') return { dependentOnly: true, src }
  return null
}

/** 绑定表达式：asset 输出引用取 .assetId，其余整体引用输出。 */
export function bindingExpression(sourceKey, kind) {
  return kind === 'asset' ? `{{${sourceKey}.output.assetId}}` : `{{${sourceKey}.output}}`
}

/** 从目标节点 params 反推边绑定 label（旧图兼容：手写表达式同样识别）。 */
export function inferEdgeLabel(targetParams, sourceKey) {
  if (!targetParams || !sourceKey) return ''
  const token = `{{${sourceKey}.output`
  for (const [key, value] of Object.entries(targetParams)) {
    if (typeof value === 'string' && value.includes(token)) return key
  }
  return ''
}

function isBlank(value) {
  return value == null || (typeof value === 'string' && value.trim() === '')
}

const REF_PATTERN = /\{\{([a-zA-Z0-9_.]+)\}\}/g

/**
 * 运行前预检（A1 验收：缺失输入/未知引用在提交前定位到具体节点）。
 * 返回 [{ key, name, message }]；空数组 = 通过。
 * 必填判定：参数存在且非空白即通过（{{引用}} 表达式视为已填，解析交给运行时）。
 */
export function validateGraphForRun(nodes) {
  const errors = []
  const keys = new Set(nodes.map(node => node.id))
  for (const node of nodes) {
    const spec = CATALOG[node.data?.nodeType]
    const name = node.data?.label || node.id
    if (!spec) {
      errors.push({ key: node.id, name, message: `未知节点类型：${node.data?.nodeType || '（空）'}` })
      continue
    }
    const params = node.data?.params || {}
    for (const rule of spec.required || []) {
      if (rule.anyOf) {
        if (rule.anyOf.every(key => isBlank(params[key]))) {
          errors.push({ key: node.id, name, message: `缺少必填输入（${rule.anyOf.join(' 或 ')} 任一）` })
        }
      } else if (rule.params) {
        const missing = rule.params.filter(key => isBlank(params[key]))
        if (missing.length) {
          errors.push({ key: node.id, name, message: `缺少必填参数：${missing.join('、')}` })
        }
      }
    }
    // 引用合法性：{{节点key.output...}} 必须指向图中存在的节点
    for (const value of Object.values(params)) {
      if (typeof value !== 'string') continue
      for (const match of value.matchAll(REF_PATTERN)) {
        const ref = match[1]
        if (ref.startsWith('input.') || ref.startsWith('start.output')) continue
        const sourceKey = ref.split('.')[0]
        if (sourceKey && !keys.has(sourceKey)) {
          errors.push({ key: node.id, name, message: `引用的节点「${sourceKey}」不存在（参数中写了 {{${ref}}}）` })
        }
      }
    }
  }
  return errors
}
