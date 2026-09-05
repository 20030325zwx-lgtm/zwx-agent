import axios from 'axios'

const desktopApiBaseUrl = window.zwxDesktop?.apiBaseUrl
const configuredApiBaseUrl = desktopApiBaseUrl || import.meta.env.VITE_API_BASE_URL
export const API_BASE_URL = (configuredApiBaseUrl || (import.meta.env.PROD ? '/api' : 'http://localhost:8123/api')).replace(/\/$/, '')

const TOKEN_KEY = 'zwx_auth_token'
const USER_KEY = 'zwx_auth_user'

const authClient = axios.create({ baseURL: API_BASE_URL, timeout: 30000 })

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ''
export const setSession = ({ token, username, tenantId, role }) => {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  if (username) localStorage.setItem(USER_KEY, JSON.stringify({ username, tenantId, role }))
}
export const getCurrentUser = () => {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}
export const clearSession = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export const login = async (username, password) => {
  const { data } = await authClient.post('/auth/login', { username, password })
  setSession(data)
  return data
}

export const register = async (username, password) => {
  const { data } = await authClient.post('/auth/register', { username, password })
  setSession(data)
  return data
}

export const logout = () => clearSession()
