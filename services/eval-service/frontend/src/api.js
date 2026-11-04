const BASE = '/api/ai/eval'

function authHeaders() {
  const token = localStorage.getItem('zwx_auth_token') || ''
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function request(path, { method = 'GET', body, text = false } = {}) {
  const response = await fetch(BASE + path, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...authHeaders()
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const raw = await response.text()
  if (text) {
    if (!response.ok) throw new Error(raw || `HTTP ${response.status}`)
    return raw
  }
  let data = null
  try {
    data = raw ? JSON.parse(raw) : null
  } catch {
    data = raw
  }
  if (!response.ok) {
    throw new Error((data && data.error) || `HTTP ${response.status}`)
  }
  return data
}

export const api = {
  get: (path) => request(path),
  post: (path, body) => request(path, { method: 'POST', body: body ?? {} }),
  put: (path, body) => request(path, { method: 'PUT', body: body ?? {} }),
  del: (path) => request(path, { method: 'DELETE' }),
  csv: (path) => request(path, { text: true })
}

export function hasToken() {
  return Boolean(localStorage.getItem('zwx_auth_token'))
}
