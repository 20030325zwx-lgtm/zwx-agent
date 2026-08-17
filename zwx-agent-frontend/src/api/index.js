import axios from 'axios'

// 根据环境变量设置 API 基础 URL
const API_BASE_URL = process.env.NODE_ENV === 'production' 
 ? '/api' // 生产环境使用相对路径，适用于前后端部署在同一域名下
 : 'http://localhost:8123/api' // 开发环境指向本地后端服务
const TENANT_ID = import.meta.env.VITE_TENANT_ID || 'default'

// 创建axios实例
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})
request.defaults.headers.common['X-Tenant-Id'] = TENANT_ID

// 封装SSE连接
export const connectSSE = (url, params, onMessage, onError) => {
  // 构建带参数的URL
  const queryString = Object.entries(params)
    .filter(([, value]) => value !== null && value !== undefined)
    .flatMap(([key, value]) => Array.isArray(value)
      ? value.map(item => `${encodeURIComponent(key)}=${encodeURIComponent(item)}`)
      : `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&')
  
  const fullUrl = `${API_BASE_URL}${url}?${queryString}`
  
  // 创建EventSource
  const eventSource = new EventSource(fullUrl)
  
  eventSource.onmessage = event => {
    let data = event.data
    
    // 检查是否是特殊标记
    if (data === '[DONE]') {
      if (onMessage) onMessage('[DONE]')
    } else {
      // 处理普通消息
      if (onMessage) onMessage(data)
    }
  }
  
  eventSource.onerror = error => {
    if (onError) onError(error)
    eventSource.close()
  }
  
  // 返回eventSource实例，以便后续可以关闭连接
  return eventSource
}

// AI恋爱大师聊天
export const chatWithLoveApp = (message, chatId, imageKeys = [], retryUserMessageId = null) => {
  return connectSSE('/ai/love_app/chat/sse', { message, chatId, imageKey: imageKeys, retryUserMessageId, tenantId: TENANT_ID })
}

export const uploadLoveImage = async (chatId, file) => {
  const formData = new FormData()
  formData.append('chatId', chatId)
  formData.append('file', file)
  return (await request.post('/ai/love_app/images', formData)).data
}

export const getLoveImageUrl = (chatId, objectKey) =>
    `${API_BASE_URL}/ai/love_app/images?chatId=${encodeURIComponent(chatId)}&objectKey=${encodeURIComponent(objectKey)}`

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
export const chatWithTravelPlanner = (conversationId, message, retryUserMessageId = null) => connectSSE('/ai/travel-planner/chat/sse', { conversationId, message, retryUserMessageId, tenantId: TENANT_ID })
export const createTravelConversation = () => request.post('/ai/travel-planner/conversations').then(response => response.data)
export const listTravelConversations = () => request.get('/ai/travel-planner/conversations').then(response => response.data)
export const getTravelConversationMessages = conversationId => request.get(`/ai/travel-planner/conversations/${conversationId}/messages`).then(response => response.data)
export const deleteTravelConversation = conversationId => request.delete(`/ai/travel-planner/conversations/${conversationId}`)
export const deleteTravelAssistantReply = (conversationId, userMessageId) => request.delete(`/ai/travel-planner/conversations/${conversationId}/messages/${userMessageId}/assistant`).then(response => response.data)
export const getTravelExecutionEvents = (conversationId, runId) => request.get(`/ai/travel-planner/conversations/${conversationId}/executions/${runId}`).then(response => response.data)

// AI超级智能体聊天
export const chatWithManus = (message) => {
  return connectSSE('/ai/manus/chat', { message })
}

export default {
  chatWithLoveApp,
  uploadLoveImage,
  getLoveImageUrl,
  createLoveConversation,
  listLoveConversations,
  getLoveConversationMessages,
  deleteLoveConversation,
  chatWithManus
}
