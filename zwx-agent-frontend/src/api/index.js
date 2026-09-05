import axios from 'axios'
import { API_BASE_URL, getToken, clearSession } from './auth'

// 创建axios实例
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})

request.interceptors.request.use(config => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
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
      const response = await fetch(`${API_BASE_URL}${url}?${queryString}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        signal: controller.signal
      })
      if (!response.ok || !response.body) {
        const error = new Error(`SSE request failed with status ${response.status}`)
        error.status = response.status
        throw error
      }
      client.readyState = 1
      client.lastEventAt = Date.now()
      client.onopen?.()
      await parseSseStream(response.body, event => {
        client.lastEventAt = Date.now()
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

// AI恋爱大师聊天
export const chatWithLoveApp = (message, chatId, imageKeys = [], webSearch = false, retryUserMessageId = null, clientRequestId = null, continueFromMessageId = null) =>
  connectSSE('/ai/love_app/chat/sse', { message, chatId, imageKey: imageKeys, webSearch, retryUserMessageId, clientRequestId, continueFromMessageId })

export const uploadLoveImage = async (chatId, file) => {
  const formData = new FormData()
  formData.append('chatId', chatId)
  formData.append('file', file)
  return (await request.post('/ai/love_app/images', formData)).data
}

export const getLoveImageUrl = (chatId, objectKey) =>
  fetchAuthBlobUrl(`/ai/love_app/images?chatId=${encodeURIComponent(chatId)}&objectKey=${encodeURIComponent(objectKey)}`)

export const getLoveKnowledgeReferences = async message =>
  (await request.get('/ai/love_app/knowledge/references', { params: { message } })).data

export const listLoveKnowledgeDocuments = async () =>
  (await request.get('/ai/love_app/knowledge/documents')).data

export const getLoveKnowledgeDocument = async objectKey =>
  (await request.get('/ai/love_app/knowledge/document', { params: { objectKey } })).data

export const createLoveConversation = async () => (await request.post('/ai/love_app/conversations')).data
export const listLoveConversations = async () => (await request.get('/ai/love_app/conversations')).data
export const getLoveConversationMessages = async (conversationId) => (await request.get(`/ai/love_app/conversations/${conversationId}/messages`)).data
export const deleteLoveConversation = (conversationId) => request.delete(`/ai/love_app/conversations/${conversationId}`)
export const deleteLoveAssistantReply = (conversationId, userMessageId) => request.delete(`/ai/love_app/conversations/${conversationId}/messages/${userMessageId}/assistant`).then(response => response.data)

export const uploadAgentKnowledgeDocument = (agentKey, file) => {
  const formData = new FormData()
  formData.append('agentKey', agentKey)
  formData.append('file', file)
  return request.post('/ai/agent-knowledge/documents', formData).then(response => response.data)
}
export const listAgentKnowledgeDocuments = agentKey => request.get('/ai/agent-knowledge/documents', { params: { agentKey } }).then(response => response.data)
export const getAgentKnowledgeDocument = (agentKey, documentId) => request.get(`/ai/agent-knowledge/documents/${documentId}`, { params: { agentKey } }).then(response => response.data)
export const getSkillCatalog = agentKey => request.get('/ai/skills/catalog', { params: { agentKey } }).then(response => response.data)
export const saveSkillConfiguration = (agentKey, enabledSkillIds) => request.post('/ai/skills/config', { agentKey, enabledSkillIds }).then(response => response.data)
export const chatWithTravelPlanner = (conversationId, message, webSearch = false, retryUserMessageId = null, clientRequestId = null) => connectSSE('/ai/travel-planner/chat/sse', { conversationId, message, webSearch, retryUserMessageId, clientRequestId })
export const createTravelConversation = () => request.post('/ai/travel-planner/conversations').then(response => response.data)
export const listTravelConversations = () => request.get('/ai/travel-planner/conversations').then(response => response.data)
export const getTravelConversationMessages = conversationId => request.get(`/ai/travel-planner/conversations/${conversationId}/messages`).then(response => response.data)
export const deleteTravelConversation = conversationId => request.delete(`/ai/travel-planner/conversations/${conversationId}`)
export const deleteTravelAssistantReply = (conversationId, userMessageId) => request.delete(`/ai/travel-planner/conversations/${conversationId}/messages/${userMessageId}/assistant`).then(response => response.data)
export const getTravelExecutionEvents = (conversationId, runId) => request.get(`/ai/travel-planner/conversations/${conversationId}/executions/${runId}`).then(response => response.data)
export const chatWithTestAgent = (conversationId, message, webSearch = false, retryUserMessageId = null, clientRequestId = null) => connectSSE('/ai/test-agent/chat/sse', { conversationId, message, webSearch, retryUserMessageId, clientRequestId })
export const createTestConversation = () => request.post('/ai/test-agent/conversations').then(response => response.data)
export const listTestConversations = () => request.get('/ai/test-agent/conversations').then(response => response.data)
export const getTestConversationMessages = conversationId => request.get(`/ai/test-agent/conversations/${conversationId}/messages`).then(response => response.data)
export const deleteTestConversation = conversationId => request.delete(`/ai/test-agent/conversations/${conversationId}`)
export const deleteTestAssistantReply = (conversationId, userMessageId) => request.delete(`/ai/test-agent/conversations/${conversationId}/messages/${userMessageId}/assistant`).then(response => response.data)

// AI超级智能体聊天
export const chatWithManus = (conversationId, message, knowledgeSearch = false) => connectSSE('/ai/manus/chat', { conversationId, message, knowledgeSearch })
export const createManusConversation = () => request.post('/ai/manus/conversations').then(response => response.data)
export const listManusConversations = () => request.get('/ai/manus/conversations').then(response => response.data)
export const getManusConversationMessages = conversationId => request.get(`/ai/manus/conversations/${conversationId}/messages`).then(response => response.data)
export const deleteManusConversation = conversationId => request.delete(`/ai/manus/conversations/${conversationId}`)
export const getManusFileUrl = (conversationId, path) =>
  fetchAuthBlobUrl(`/ai/manus/files?conversationId=${encodeURIComponent(conversationId)}&path=${encodeURIComponent(path)}`)

export const listMcpServers = () => request.get('/ai/mcp/servers').then(response => response.data)
export const createMcpServer = payload => request.post('/ai/mcp/servers', payload).then(response => response.data)
export const updateMcpServer = (id, payload) => request.put(`/ai/mcp/servers/${id}`, payload).then(response => response.data)
export const deleteMcpServer = id => request.delete(`/ai/mcp/servers/${id}`)
export const testMcpServer = id => request.post(`/ai/mcp/servers/${id}/test`).then(response => response.data)

export default {
  chatWithLoveApp,
  uploadLoveImage,
  getLoveImageUrl,
  createLoveConversation,
  listLoveConversations,
  getLoveConversationMessages,
  deleteLoveConversation,
  chatWithManus,
  createManusConversation,
  listManusConversations,
  getManusConversationMessages,
  deleteManusConversation,
  getManusFileUrl,
  listMcpServers,
  createMcpServer,
  updateMcpServer,
  deleteMcpServer,
  testMcpServer
}
