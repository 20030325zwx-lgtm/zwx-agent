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
export const startWorkflow = (workflowKey, keyword) => request.post(`/ai/workflows/${encodeURIComponent(workflowKey)}/runs`, { keyword }).then(response => response.data)
export const getWorkflowRun = runId => request.get(`/ai/workflows/runs/${encodeURIComponent(runId)}`).then(response => response.data)
export const getWorkflowDefinition = workflowKey => request.get(`/ai/workflows/${encodeURIComponent(workflowKey)}/definition`).then(response => response.data)
export const saveWorkflowVersion = (workflowKey, payload) => request.post(`/ai/workflows/${encodeURIComponent(workflowKey)}/versions`, payload).then(response => response.data)
export const createModelProvider = payload => request.post('/settings/providers', payload).then(response => response.data)
export const updateModelProvider = (providerId, payload) => request.put(`/settings/providers/${providerId}`, payload).then(response => response.data)
export const deleteModelProvider = providerId => request.delete(`/settings/providers/${providerId}`).then(response => response.data)
export const addProviderModel = (providerId, payload) => request.post(`/settings/providers/${providerId}/models`, payload).then(response => response.data)
export const updateProviderModel = (providerId, modelRowId, payload) => request.put(`/settings/providers/${providerId}/models/${modelRowId}`, payload).then(response => response.data)
export const deleteProviderModel = (providerId, modelRowId) => request.delete(`/settings/providers/${providerId}/models/${modelRowId}`).then(response => response.data)
export const testModelProvider = (providerId, draft) => request.post(`/settings/providers/${providerId}/test`, draft || {}).then(response => response.data)
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
export const listCreativeProjects = (params = {}) =>
  request.get('/ai/creative/projects', { params }).then(response => response.data)
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
export const listCreativeAssets = (params = {}) =>
  request.get('/ai/creative/assets', { params }).then(response => response.data)
export const uploadCreativeAsset = (projectId, file) => {
  const form = new FormData()
  form.append('projectId', projectId)
  form.append('file', file)
  return request.post('/ai/creative/assets/uploads', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }).then(response => response.data)
}
export const getCreativeAssetThumbnail = assetId =>
  request.get(`/ai/creative/assets/${encodeURIComponent(assetId)}/thumbnail`, { responseType: 'blob' })
    .then(response => response.data)
export const getCreativeAssetBlob = assetId =>
  request.get(`/ai/creative/assets/${encodeURIComponent(assetId)}/content`, { responseType: 'blob' })
    .then(response => response.data)
export const updateCreativeAsset = (assetId, payload) =>
  request.patch(`/ai/creative/assets/${encodeURIComponent(assetId)}`, payload).then(response => response.data)
export const deleteCreativeAsset = assetId =>
  request.delete(`/ai/creative/assets/${encodeURIComponent(assetId)}`).then(response => response.data)
