import axios from 'axios'
import { API_BASE_URL, clearSession, getToken, localTokenHeader } from './auth'

// 创建axios实例
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})

request.interceptors.request.use(config => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  const localHeaders = localTokenHeader()
  if (localHeaders['X-Aster-Local-Token']) config.headers['X-Aster-Local-Token'] = localHeaders['X-Aster-Local-Token']
  return config
})

request.interceptors.response.use(
  response => response,
  error => {
    const status = error.response?.status
    const url = error.config?.url || ''
    if (status === 401 && !url.startsWith('/auth/')) {
      clearSession()
      if (!location.pathname.startsWith('/login')) location.href = '/login'
    }
    return Promise.reject(error)
  }
)

// 解析 SSE 文本帧为事件流
const parseSseStream = async (body, emit) => {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let separatorIndex
    while ((separatorIndex = buffer.search(/\r?\n\r?\n/)) !== -1) {
      const rawEvent = buffer.slice(0, separatorIndex)
      buffer = buffer.slice(separatorIndex + (buffer[separatorIndex] === '\r' ? 4 : 2))
      const event = { data: '', type: 'message' }
      rawEvent.split(/\r?\n/).forEach(line => {
        if (line.startsWith('data:')) event.data += (event.data ? '\n' : '') + line.slice(5).trimStart()
        if (line.startsWith('event:')) event.type = line.slice(6).trim()
        if (line.startsWith('id:')) event.id = line.slice(3).trim()
      })
      if (event.data) emit(event)
    }
  }
}

// 封装SSE连接：以 fetch 携带认证头，返回与 EventSource 兼容的最小接口
// 内置看门狗：45 秒未收到任何事件（含心跳）则主动断开并触发 onerror
export const connectSSE = (url, params, onMessage, onError) => {
  const queryString = Object.entries(params)
    .filter(([, value]) => value !== null && value !== undefined)
    .flatMap(([key, value]) => Array.isArray(value)
      ? value.map(item => `${encodeURIComponent(key)}=${encodeURIComponent(item)}`)
      : `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&')

  const controller = new AbortController()
  const listeners = {}
  const client = {
    onmessage: null,
    onerror: null,
    onopen: null,
    readyState: 0,
    lastEventAt: Date.now(),
    lastEventId: null,
    addEventListener: (type, handler) => {
      listeners[type] = listeners[type] || []
      listeners[type].push(handler)
    },
    close: () => {
      client.closed = true
      controller.abort()
      clearInterval(watchdog)
    }
  }

  const watchdog = setInterval(() => {
    if (client.readyState !== 1) return
    if (Date.now() - client.lastEventAt > 45000) {
      console.warn('SSE watchdog: no events for 45s, aborting')
      client.close()
      const error = new Error('连接空闲超时（45 秒无数据）')
      const handled = client.onerror?.(error)
      if (!handled && onError) onError(error)
    }
  }, 10000)

  ;(async () => {
    try {
      const token = getToken()
      const sseHeaders = { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...localTokenHeader() }
      const response = await fetch(`${API_BASE_URL}${url}?${queryString}`, {
        headers: sseHeaders,
        signal: controller.signal
      })
      if (!response.ok || !response.body) {
        let detail = `SSE request failed with status ${response.status}`
        try {
          const node = JSON.parse(await response.text())
          if (node?.message) detail = node.message
        } catch { /* 非 JSON 响应保留默认文案 */ }
        const error = new Error(detail)
        error.status = response.status
        throw error
      }
      client.readyState = 1
      client.lastEventAt = Date.now()
      client.onopen?.()
      await parseSseStream(response.body, event => {
        client.lastEventAt = Date.now()
        if (event.id) client.lastEventId = event.id
        ;(listeners[event.type] || []).forEach(handler => handler(event))
        if (event.type === 'message' && client.onmessage) client.onmessage(event)
        if (onMessage && event.type === 'message') onMessage(event.data)
      })
      client.readyState = 2
      clearInterval(watchdog)
    } catch (error) {
      if (controller.signal.aborted && (client.closed || client.readyState === 2)) return
      client.readyState = 2
      clearInterval(watchdog)
      const handled = client.onerror?.(error)
      if (!handled && onError) onError(error)
    }
  })()

  return client
}

// 受控资源下载：带认证头获取并转为 objectURL
export const fetchAuthBlobUrl = async path => {
  const response = await request.get(path, { responseType: 'blob' })
  return URL.createObjectURL(response.data)
}

export const uploadAgentKnowledgeDocument = (agentKey, file, categoryId = null, autoPublish = true) => {
  const formData = new FormData()
  formData.append('agentKey', agentKey)
  if (categoryId) formData.append('categoryId', categoryId)
  formData.append('autoPublish', String(autoPublish))
  formData.append('file', file)
  return request.post('/ai/agent-knowledge/documents', formData).then(response => response.data)
}
export const listAgentKnowledgeCategories = agentKey => request.get('/ai/agent-knowledge/categories', { params: { agentKey } }).then(response => response.data)
export const createAgentKnowledgeCategory = (agentKey, name) => request.post('/ai/agent-knowledge/categories', { agentKey, name }).then(response => response.data)
export const moveAgentKnowledgeDocument = (documentId, agentKey, categoryId) => request.post(`/ai/agent-knowledge/documents/${encodeURIComponent(documentId)}/category`, null, { params: { agentKey, categoryId } }).then(response => response.data)
export const publishAgentKnowledgeDocument = (documentId, agentKey) => request.post(`/ai/agent-knowledge/documents/${encodeURIComponent(documentId)}/publish`, null, { params: { agentKey } }).then(response => response.data)
export const rejectAgentKnowledgeDocument = (documentId, agentKey, reviewNote) => request.post(`/ai/agent-knowledge/documents/${encodeURIComponent(documentId)}/reject`, { reviewNote }, { params: { agentKey } }).then(response => response.data)
export const getAgentKnowledgeReviewLog = (documentId, agentKey) => request.get(`/ai/agent-knowledge/documents/${encodeURIComponent(documentId)}/review-log`, { params: { agentKey } }).then(response => response.data)
export const reindexAgentKnowledgeDocument = (documentId, agentKey) => request.post(`/ai/agent-knowledge/documents/${encodeURIComponent(documentId)}/reindex`, null, { params: { agentKey } }).then(response => response.data)
export const deleteAgentKnowledgeDocument = (documentId, agentKey) => request.delete(`/ai/agent-knowledge/documents/${encodeURIComponent(documentId)}`, { params: { agentKey } })
export const getAgentKnowledgeChunk = (chunkId, agentKey, scope = {}) => request.get(`/ai/agent-knowledge/chunks/${encodeURIComponent(chunkId)}`, { params: { agentKey, ...scope } }).then(response => response.data)
export const listAgentKnowledgeDocuments = agentKey => request.get('/ai/agent-knowledge/documents', { params: { agentKey } }).then(response => response.data)
export const getAgentKnowledgeDocument = (agentKey, documentId) => request.get(`/ai/agent-knowledge/documents/${documentId}`, { params: { agentKey } }).then(response => response.data)
export const getAgentKnowledgeDocumentFile = (agentKey, documentId) => request.get(`/ai/agent-knowledge/documents/${documentId}/content`, { params: { agentKey }, responseType: 'blob' }).then(response => response.data)
export const getSkillCatalog = agentKey => request.get('/ai/skills/catalog', { params: { agentKey } }).then(response => response.data)
export const saveSkillConfiguration = (agentKey, enabledSkillIds) => request.post('/ai/skills/config', { agentKey, enabledSkillIds }).then(response => response.data)
export const judgeRagCandidate = (messageId, agentKey, candidateId, judgment) => request.post(`/ai/rag-evaluations/${messageId}/judgments`, { agentKey, candidateId, judgment })

// AI超级智能体聊天
export const chatWithManus = (conversationId, message, knowledgeSearch = false, webSearch = true, clientRequestId = null, imageObjectKey = null, permissionMode = null, modelRowId = null) => connectSSE('/ai/manus/chat', { conversationId, message, knowledgeSearch, webSearch, clientRequestId, imageObjectKey, permissionMode, modelRowId })
export const uploadManusImage = async (conversationId, file) => {
  const formData = new FormData()
  formData.append('conversationId', conversationId)
  formData.append('file', file)
  return (await request.post('/ai/manus/images', formData)).data
}
export const getManusImageUrl = (conversationId, objectKey) =>
  fetchAuthBlobUrl(`/ai/manus/images?conversationId=${encodeURIComponent(conversationId)}&objectKey=${encodeURIComponent(objectKey)}`)
export const resumeManusRun = (conversationId, runId, webSearch = true) => connectSSE(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/resume`, { webSearch })

// 权限设置：默认模式与工具 allow/deny 规则
export const getPermissionSettings = () => request.get('/ai/settings/permission').then(response => response.data)
export const savePermissionMode = mode => request.put('/ai/settings/permission', { mode }).then(response => response.data)
export const addPermissionRule = (toolPattern, effect) => request.post('/ai/settings/permission/rules', { toolPattern, effect }).then(response => response.data)
export const deletePermissionRule = id => request.delete(`/ai/settings/permission/rules/${id}`).then(response => response.data)
export const reconnectManusRun = (conversationId, runId, lastEventId = null) => connectSSE(
  `/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/reconnect`,
  { lastEventId })
export const cancelManusRun = (conversationId, runId) => request.post(
  `/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/cancel`
).then(response => response.data)
export const decideManusConfirmation = (conversationId, runId, toolName, decision) => request.post(
  `/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/confirmation`,
  { toolName, decision }
).then(response => response.data)
export const listManusToolCalls = (conversationId, runId, limit = 200) =>
  request.get(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/tool-calls`, { params: { limit } }).then(response => response.data)
export const listManusWebSources = (conversationId, runId) =>
  request.get(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/web-sources`).then(response => response.data)
export const createManusConversation = (projectId = null) => request.post('/ai/manus/conversations', { projectId }).then(response => response.data)
export const listManusConversations = () => request.get('/ai/manus/conversations').then(response => response.data)
export const listProjects = () => request.get('/ai/projects').then(response => response.data)
export const createProject = payload => request.post('/ai/projects', payload).then(response => response.data)
export const browseProjectDirectories = (path = null) => request.get('/ai/projects/directories', { params: { path: path || undefined } }).then(response => response.data)
export const selectProjectDirectory = payload => request.post('/ai/projects/select', payload).then(response => response.data)
export const getManusConversationMessages = conversationId => request.get(`/ai/manus/conversations/${conversationId}/messages`).then(response => response.data)
export const deleteManusConversation = conversationId => request.delete(`/ai/manus/conversations/${conversationId}`)
export const getManusFileUrl = (conversationId, path) =>
  fetchAuthBlobUrl(`/ai/manus/files?conversationId=${encodeURIComponent(conversationId)}&path=${encodeURIComponent(path)}`)
// 桌面端：把会话工作区文件导出到已授权的本机目录（返回 [{from,to}]，仅桌面模式可用）
export const exportManusFiles = (conversationId, paths, targetDir, overwrite = false) =>
  request.post('/ai/manus/files/export', { conversationId, paths, targetDir, overwrite }).then(response => response.data)
export const listManusRuns = conversationId =>
  request.get(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs`).then(response => response.data)
export const listRecentManusRuns = (limit = 8) =>
  request.get('/ai/manus/runs/recent', { params: { limit } }).then(response => response.data)
export const listManusRunOverviews = (limit = 30) =>
  request.get('/ai/manus/runs/overview', { params: { limit } }).then(response => response.data)
export const getManusRun = (conversationId, runId) =>
  request.get(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}`).then(response => response.data)
export const listWorkspaceFiles = (agentKey, conversationId, limit = 200) =>
  request.get('/ai/workspace/files', { params: { agentKey, conversationId, limit } }).then(response => response.data)

export const listMemoryFacts = (agentKey = null, limit = 100) =>
  request.get('/ai/memory/facts', { params: { agentKey: agentKey || undefined, limit } }).then(response => response.data)
export const addMemoryFact = (agentKey, fact) => request.post('/ai/memory/facts', { agentKey, fact }).then(response => response.data)
export const updateMemoryFact = (id, fact) => request.put(`/ai/memory/facts/${id}`, { fact }).then(response => response.data)
export const deleteMemoryFact = id => request.delete(`/ai/memory/facts/${id}`).then(response => response.data)

export const listMcpServers = () => request.get('/ai/mcp/servers').then(response => response.data)
export const createMcpServer = payload => request.post('/ai/mcp/servers', payload).then(response => response.data)
export const updateMcpServer = (id, payload) => request.put(`/ai/mcp/servers/${id}`, payload).then(response => response.data)
export const deleteMcpServer = id => request.delete(`/ai/mcp/servers/${id}`)
export const testMcpServer = id => request.post(`/ai/mcp/servers/${id}/test`).then(response => response.data)

export const listModelSettings = () => request.get('/settings/models').then(response => response.data)
export const saveUserModelSettings = (key, payload) => request.put(`/settings/models/${encodeURIComponent(key)}`, payload).then(response => response.data)
export const resetUserModelSettings = key => request.delete(`/settings/models/${encodeURIComponent(key)}`).then(response => response.data)
export const saveSystemModelSettings = (key, payload) => request.put(`/settings/system/models/${encodeURIComponent(key)}`, payload).then(response => response.data)
export const resetSystemModelSettings = key => request.delete(`/settings/system/models/${encodeURIComponent(key)}`).then(response => response.data)
export const testModelSettings = (key, scope = 'USER') => request.post(`/settings/models/${encodeURIComponent(key)}/test`, null, { params: { scope } }).then(response => response.data)

// 模型供应商目录（Provider/Model/Binding），见 docs/design/2026-10-28-desktop-phase-a-runtime-model-contract.md
export const listModelCatalog = () => request.get('/settings/catalog').then(response => response.data)
// 会话级代码工作区：文件树 / 文件内容 / 保存 / 改动 diff
export const getProjectCodeTree = (conversationId, path = '', runId = null) =>
  request.get(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/code/tree`, {
    params: { path: path || undefined, runId: runId || undefined }
  }).then(response => response.data)
export const getProjectCodeFile = (conversationId, path, runId = null) =>
  request.get(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/code/file`, {
    params: { path, runId: runId || undefined }
  }).then(response => response.data)
export const saveProjectCodeFile = (conversationId, path, content, expectedModifiedAt = null, runId = null) =>
  request.put(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/code/file`, { content, expectedModifiedAt }, {
    params: { path, runId: runId || undefined }
  }).then(response => response.data)
export const getProjectCodeDiff = (conversationId, runId = null) =>
  request.get(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/code/diff`, {
    params: { runId: runId || undefined }
  }).then(response => response.data)
export const revertProjectCodeHunk = (conversationId, path, hunkIndex, runId = null) =>
  request.post(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/code/revert`, { path, hunkIndex }, {
    params: { runId: runId || undefined }
  }).then(response => response.data)
export const deleteProjectCodeFile = (conversationId, path, runId = null) =>
  request.post(`/ai/manus/conversations/${encodeURIComponent(conversationId)}/code/delete-file`, { path }, {
    params: { runId: runId || undefined }
  }).then(response => response.data)

// 工作流试点：定义 + 运行
export const listWorkflows = (limit = 20) => request.get('/ai/workflows', { params: { limit } }).then(response => response.data)
export const createWorkflow = payload => request.post('/ai/workflows', payload).then(response => response.data)
export const deleteWorkflow = workflowKey => request.delete(`/ai/workflows/${encodeURIComponent(workflowKey)}`).then(response => response.data)
export const startWorkflow = (workflowKey, keyword, projectId = null, input = null) =>
  request.post(`/ai/workflows/${encodeURIComponent(workflowKey)}/runs`,
    { keyword, input: input || undefined, projectId: projectId || undefined })
    .then(response => response.data)
export const listWorkflowRuns = (params = {}) =>
  request.get('/ai/workflows/runs', { params }).then(response => response.data)
export const getWorkflowRun = runId => request.get(`/ai/workflows/runs/${encodeURIComponent(runId)}`).then(response => response.data)
// C 批：从指定节点重跑（祖先产物复用，不重复计费）
export const rerunWorkflowFrom = (runId, fromNode) =>
  request.post(`/ai/workflows/runs/${encodeURIComponent(runId)}/rerun`, { fromNode }).then(response => response.data)
// D 批：模板导出 / 导入（脱敏 bundle）
export const exportWorkflow = workflowKey =>
  request.get(`/ai/workflows/${encodeURIComponent(workflowKey)}/export`).then(response => response.data)
export const importWorkflow = bundle =>
  request.post('/ai/workflows/import', bundle).then(response => response.data)
export const getWorkflowDefinition = workflowKey => request.get(`/ai/workflows/${encodeURIComponent(workflowKey)}/definition`).then(response => response.data)
export const saveWorkflowVersion = (workflowKey, payload) => request.post(`/ai/workflows/${encodeURIComponent(workflowKey)}/versions`, payload).then(response => response.data)
// AI 小说：章节库与作品设定（创作项目的有状态内容层）
export const listNovelChapters = projectId =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/novel/chapters`).then(response => response.data)
export const updateNovelChapter = (projectId, chapterId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}/novel/chapters/${encodeURIComponent(chapterId)}`, payload).then(response => response.data)
export const confirmNovelChapter = (projectId, chapterId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/novel/chapters/${encodeURIComponent(chapterId)}/confirm`).then(response => response.data)
export const deleteNovelChapter = (projectId, chapterId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/novel/chapters/${encodeURIComponent(chapterId)}`).then(response => response.data)
export const getNovelSettings = projectId =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/novel/settings`).then(response => response.data)
export const updateNovelSettings = (projectId, payload) =>
  request.put(`/ai/creative/projects/${encodeURIComponent(projectId)}/novel/settings`, payload).then(response => response.data)
export const suggestNovelPlot = (projectId, direction) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/novel/plot-suggestions`,
    { direction: direction || undefined }).then(response => response.data)
export const createModelProvider = payload => request.post('/settings/providers', payload).then(response => response.data)
export const updateModelProvider = (providerId, payload) => request.put(`/settings/providers/${providerId}`, payload).then(response => response.data)
export const deleteModelProvider = providerId => request.delete(`/settings/providers/${providerId}`).then(response => response.data)
export const addProviderModel = (providerId, payload) => request.post(`/settings/providers/${providerId}/models`, payload).then(response => response.data)
export const updateProviderModel = (providerId, modelRowId, payload) => request.put(`/settings/providers/${providerId}/models/${modelRowId}`, payload).then(response => response.data)
export const deleteProviderModel = (providerId, modelRowId) => request.delete(`/settings/providers/${providerId}/models/${modelRowId}`).then(response => response.data)
export const testModelProvider = (providerId, draft) => request.post(`/settings/providers/${providerId}/test`, draft || {}).then(response => response.data)
// 从中转站（OpenAI 兼容）拉取 /v1/models 模型列表并增量入库
export const fetchProviderModels = providerId =>
  request.post(`/settings/providers/${providerId}/fetch-models`).then(response => response.data)
export const setModelBinding = (purpose, payload) => request.put(`/settings/bindings/${encodeURIComponent(purpose)}`, payload).then(response => response.data)
export const clearModelBinding = (purpose, scope = 'USER') => request.delete(`/settings/bindings/${encodeURIComponent(purpose)}`, { params: { scope } }).then(response => response.data)


export default {
  chatWithManus,
  resumeManusRun,
  reconnectManusRun,
  cancelManusRun,
  createManusConversation,
  listManusConversations,
  getManusConversationMessages,
  deleteManusConversation,
  getManusFileUrl,
  listManusRuns,
  getManusRun,
  listWorkspaceFiles,
  listMcpServers,
  createMcpServer,
  updateMcpServer,
  deleteMcpServer,
  testMcpServer
}

// AI 创作（P1）：项目、草稿、任务、素材、提示词优化
// 列表支持 kind/q/sort（updated|created）/cursor；返回项含 coverAssetId/coverMediaType
export const listCreativeProjects = (params = {}) =>
  request.get('/ai/creative/projects', { params }).then(response => response.data)
// 创作健康：提交/调度开关、存储后端、图片模型绑定与当前用户任务计数（首页待处理）
export const getCreativeHealth = () =>
  request.get('/ai/creative/health').then(response => response.data)
export const createCreativeProject = payload =>
  request.post('/ai/creative/projects', payload).then(response => response.data)
export const getCreativeProject = projectId =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}`).then(response => response.data)
export const updateCreativeProject = (projectId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}`, payload).then(response => response.data)
export const listCreativeDrafts = (projectId, limit = 20) =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/drafts`, { params: { limit } }).then(response => response.data)
export const createCreativeDraft = (projectId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/drafts`, payload).then(response => response.data)
export const updateCreativeDraft = (draftId, payload) =>
  request.patch(`/ai/creative/drafts/${encodeURIComponent(draftId)}`, payload).then(response => response.data)
export const optimizeCreativePrompt = payload =>
  request.post('/ai/creative/prompts/optimize', payload).then(response => response.data)
// 视频剧情域（P1/P2）：剧集/场次/镜头/角色/地点/连续性 + 候选选片
export const getStoryWorkspace = projectId =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/workspace`).then(r => r.data)
export const createStoryEpisode = (projectId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/episodes`, payload).then(r => r.data)
export const updateStoryEpisode = (projectId, episodeId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/episodes/${episodeId}`, payload).then(r => r.data)
export const confirmStoryEpisode = (projectId, episodeId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/episodes/${episodeId}/confirm`).then(r => r.data)
export const moveStoryEpisode = (projectId, episodeId, direction) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/episodes/${episodeId}/move`, { direction }).then(r => r.data)
export const deleteStoryEpisode = (projectId, episodeId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/episodes/${episodeId}`).then(r => r.data)
export const createStoryScene = (projectId, episodeId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/episodes/${episodeId}/scenes`, payload).then(r => r.data)
export const updateStoryScene = (projectId, sceneId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/scenes/${sceneId}`, payload).then(r => r.data)
export const confirmStoryScene = (projectId, sceneId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/scenes/${sceneId}/confirm`).then(r => r.data)
export const deleteStoryScene = (projectId, sceneId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/scenes/${sceneId}`).then(r => r.data)
export const createStoryShot = (projectId, sceneId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/scenes/${sceneId}/shots`, payload).then(r => r.data)
export const getStoryShot = (projectId, shotId) =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}`).then(r => r.data)
export const updateStoryShot = (projectId, shotId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}`, payload).then(r => r.data)
export const deleteStoryShot = (projectId, shotId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}`).then(r => r.data)
export const listShotCandidates = (projectId, shotId) =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}/candidates`).then(r => r.data)
export const generateShotVideo = (projectId, shotId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}/generate`, payload).then(r => r.data)
export const uploadShotCandidate = (projectId, shotId, assetId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}/candidates/upload`, { assetId }).then(r => r.data)
export const selectShotCandidate = (projectId, shotId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}/select`, payload).then(r => r.data)
export const unselectShotCandidate = (projectId, shotId, candidateId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}/unselect`, { candidateId }).then(r => r.data)
export const deleteShotCandidate = (projectId, shotId, candidateId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/shots/${shotId}/candidates/${candidateId}`).then(r => r.data)
export const createStoryCharacter = (projectId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/characters`, payload).then(r => r.data)
export const updateStoryCharacter = (projectId, characterId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/characters/${characterId}`, payload).then(r => r.data)
export const deleteStoryCharacter = (projectId, characterId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/characters/${characterId}`).then(r => r.data)
export const createStoryLocation = (projectId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/locations`, payload).then(r => r.data)
export const updateStoryLocation = (projectId, locationId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/locations/${locationId}`, payload).then(r => r.data)
export const deleteStoryLocation = (projectId, locationId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/locations/${locationId}`).then(r => r.data)
export const attachStoryRef = (projectId, entityType, entityId, assetId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/refs`, { entityType, entityId, assetId }).then(r => r.data)
export const listStoryNotes = projectId =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/notes`).then(r => r.data)
export const createStoryNote = (projectId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/notes`, payload).then(r => r.data)
export const updateStoryNote = (projectId, noteId, payload) =>
  request.patch(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/notes/${noteId}`, payload).then(r => r.data)
export const deleteStoryNote = (projectId, noteId) =>
  request.delete(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/notes/${noteId}`).then(r => r.data)
// 批次生产（P3）
export const createStoryBatch = (projectId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/batches`, payload).then(r => r.data)
export const listStoryBatches = projectId =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/batches`).then(r => r.data)
export const getStoryBatch = (projectId, batchId) =>
  request.get(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/batches/${batchId}`).then(r => r.data)
export const stopStoryBatch = (projectId, batchId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/batches/${batchId}/stop`).then(r => r.data)
export const retryStoryBatch = (projectId, batchId) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/story/batches/${batchId}/retry-failed`).then(r => r.data)
export const listCreativeJobs = (params = {}) =>
  request.get('/ai/creative/jobs', { params }).then(response => response.data)
export const getCreativeJob = jobId =>
  request.get(`/ai/creative/jobs/${encodeURIComponent(jobId)}`).then(response => response.data)
export const createCreativeJob = (idempotencyKey, payload) =>
  request.post('/ai/creative/jobs', payload, { headers: { 'Idempotency-Key': idempotencyKey } })
    .then(response => response.data)
export const cancelCreativeJob = jobId =>
  request.post(`/ai/creative/jobs/${encodeURIComponent(jobId)}/cancel`).then(response => response.data)
export const retryCreativeJob = (jobId, payload = {}) =>
  request.post(`/ai/creative/jobs/${encodeURIComponent(jobId)}/retry`, payload).then(response => response.data)
export const resumeCreativeJobIngest = jobId =>
  request.post(`/ai/creative/jobs/${encodeURIComponent(jobId)}/resume-ingest`).then(response => response.data)
export const listCreativeJobEvents = (jobId, afterSeq = 0) =>
  request.get(`/ai/creative/jobs/${encodeURIComponent(jobId)}/events`, { params: { afterSeq } })
    .then(response => response.data)
// 素材列表：支持 projectId/mediaType/origin/starred/businessType/trashed
export const listCreativeAssets = (params = {}) =>
  request.get('/ai/creative/assets', { params }).then(response => response.data)
export const getCreativeAsset = assetId =>
  request.get(`/ai/creative/assets/${encodeURIComponent(assetId)}`).then(response => response.data)
export const uploadCreativeAsset = (projectId, file, onProgress) => {
  const form = new FormData()
  form.append('projectId', projectId)
  form.append('file', file)
  return request.post('/ai/creative/assets/uploads', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    // 逐文件进度（redesign §5.3 多项上传）：onProgress 可选，百分比来自浏览器上传事件
    onUploadProgress: onProgress
  }).then(response => response.data)
}
export const getCreativeAssetThumbnail = assetId =>
  request.get(`/ai/creative/assets/${encodeURIComponent(assetId)}/thumbnail`, { responseType: 'blob' })
    .then(response => response.data)
export const getCreativeAssetBlob = assetId =>
  request.get(`/ai/creative/assets/${encodeURIComponent(assetId)}/content`, { responseType: 'blob' })
    .then(response => response.data)
// 视频播放签名 URL（V1-A）：<video> 标签带不了 Authorization，换取短时效免头播放地址。
// 后端返回相对 /api 路径；开发环境 API_BASE_URL 是跨端口绝对地址，需拼前缀（生产同源已是 /api）。
export const getCreativeAssetPlaybackUrl = assetId =>
  request.get(`/ai/creative/assets/${encodeURIComponent(assetId)}/playback-url`)
    .then(response => {
      const data = response.data
      if (data?.url?.startsWith('/')) data.url = API_BASE_URL + data.url
      return data
    })
// 素材使用位置（跨项目）：镜头候选 + 角色/地点参考图
export const getCreativeAssetUsage = assetId =>
  request.get(`/ai/creative/assets/${encodeURIComponent(assetId)}/usage`).then(r => r.data)
// 回收站恢复：DELETED → READY，不重新生成
export const restoreCreativeAsset = assetId =>
  request.post(`/ai/creative/assets/${encodeURIComponent(assetId)}/restore`).then(r => r.data)
// 跨项目待处理聚合：进行中/失败/保存失败/结果待确认/待选片 + 最近失败条目
export const getCreativeSummary = () =>
  request.get('/ai/creative/jobs/summary').then(r => r.data)

// 创作 Skill（skill 设计 §8.4）：目录/详情/运行；方案生成不计费，确认后才创建媒体任务
export const listCreativeSkills = (params = {}) =>
  request.get('/ai/creative/skills', { params }).then(r => r.data)
export const getCreativeSkill = slug =>
  request.get(`/ai/creative/skills/${encodeURIComponent(slug)}`).then(r => r.data)
export const createCreativeSkillRun = (projectId, payload) =>
  request.post(`/ai/creative/projects/${encodeURIComponent(projectId)}/skill-runs`, payload)
    .then(r => r.data)
export const getCreativeSkillRun = runId =>
  request.get(`/ai/creative/skill-runs/${encodeURIComponent(runId)}`).then(r => r.data)
export const retryCreativeSkillPlan = runId =>
  request.post(`/ai/creative/skill-runs/${encodeURIComponent(runId)}/retry-plan`).then(r => r.data)
export const confirmCreativeSkillRun = (runId, payload) =>
  request.post(`/ai/creative/skill-runs/${encodeURIComponent(runId)}/confirm`, payload).then(r => r.data)
export const confirmCreativeSkillShots = runId =>
  request.post(`/ai/creative/skill-runs/${encodeURIComponent(runId)}/confirm-shots`).then(r => r.data)
export const previewCreativeSkillImport = payload =>
  request.post('/ai/creative/skills/imports', payload).then(r => r.data)
export const confirmCreativeSkillImport = (importId, payload) =>
  request.post(`/ai/creative/skills/imports/${encodeURIComponent(importId)}/confirm`, payload).then(r => r.data)
export const updateCreativeAsset = (assetId, payload) =>
  request.patch(`/ai/creative/assets/${encodeURIComponent(assetId)}`, payload).then(response => response.data)
export const deleteCreativeAsset = assetId =>
  request.delete(`/ai/creative/assets/${encodeURIComponent(assetId)}`).then(response => response.data)

// 数据中台（独立服务）：回答反馈走独立 base（开发环境用 VITE_DATA_API_URL 指向 8141；
// 生产同域下即 API_BASE_URL，由 nginx 把 /api/ai/feedback 路由到 data-service）。
const DATA_API_BASE = import.meta.env.VITE_DATA_API_URL
  || (import.meta.env.DEV ? 'http://localhost:8141/api' : API_BASE_URL)

function dataHeaders() {
  const headers = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  const localHeaders = localTokenHeader()
  if (localHeaders['X-Aster-Local-Token']) headers['X-Aster-Local-Token'] = localHeaders['X-Aster-Local-Token']
  return headers
}

export const createUserFeedback = payload =>
  axios.post(`${DATA_API_BASE}/ai/feedback`, payload, { headers: dataHeaders() }).then(r => r.data)

/** 点踩后的可选第二步：补齐快捷标签（不阻塞对话）。 */
export const updateUserFeedbackTags = (feedbackId, tags) =>
  axios.patch(`${DATA_API_BASE}/ai/feedback/${encodeURIComponent(feedbackId)}/tags`, { tags },
    { headers: dataHeaders() }).then(r => r.data)

/**
 * 隐式信号上报：fire-and-forget。失败只落 debug 日志，绝不打断用户操作，
 * 用 keepalive 保证「复制后立刻切页」这类场景也能送达。
 */
export const reportSignal = (event, subjectType, subjectId, props = {}) => {
  if (!subjectId) return Promise.resolve()
  return fetch(`${DATA_API_BASE}/ai/feedback/signals`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...dataHeaders() },
    keepalive: true,
    body: JSON.stringify({ event, subjectType, subjectId, props })
  }).catch(exception => {
    console.debug('信号上报失败（忽略）', event, exception)
  })
}
