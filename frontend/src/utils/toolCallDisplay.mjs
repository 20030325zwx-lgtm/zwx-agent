const labels = {
  executeTerminalCommand: '终端命令', runProjectTask: '运行项目任务',
  readProjectFile: '读取项目文件', readFile: '读取文件', writeFile: '写入文件',
  searchProjectCode: '搜索代码', listProjectTree: '查看目录',
  searchWeb: '搜索网页', scrapeWebPage: '读取网页',
  applyProjectPatch: '修改项目文件', getProjectGitStatus: '查看 Git 状态',
  getProjectDiff: '查看改动'
}
const safeKeys = ['command', 'taskId', 'fileName', 'filePath', 'path', 'query', 'url', 'keyword']
const sensitive = /(?:password|passwd|secret|token|api[_-]?key|authorization|credential)/i

export const toolCallLabel = name => labels[name] || name || '工具调用'

export const redactToolText = value => String(value || '')
  .replace(/\b(Bearer\s+)\S+/gi, '$1[已隐藏]')
  .replace(/\b([\w.-]*(?:password|passwd|secret|token|api[_-]?key|authorization|credential)[\w.-]*\s*[=:]\s*)[^\s,;"']+/gi, '$1[已隐藏]')
  .replace(/((?:--?)(?:password|passwd|secret|token|api[_-]?key)\s+)\S+/gi, '$1[已隐藏]')

export const toolCallArguments = call => {
  let args
  try { args = typeof call?.arguments === 'string' ? JSON.parse(call.arguments) : call?.arguments }
  catch { return '' }
  if (!args || typeof args !== 'object' || Array.isArray(args)) return ''
  return safeKeys.filter(key => key in args && !sensitive.test(key))
    .map(key => `${key}: ${redactToolText(String(args[key]).slice(0, 200))}`)
    .join('\n')
}

export const toolCallResult = call => redactToolText(String(call?.result_preview || '').slice(0, 300))

export const toolCallStatus = call => {
  const status = call?.status
  if (status === 'PENDING') return '调用中'
  if (status === 'SUCCEEDED') return '已返回'
  if (status === 'FAILED') return '调用失败'
  if (status === 'UNKNOWN') return '结果未知'
  return '状态未知'
}
