export const isGenerationNode = node => ['image_generate', 'image_edit', 'video_generate'].includes(node?.type)
export function connectionKind(edge, nodes) {
  const source = nodes.find(node => node.id === edge.source)
  return edge.sourceHandle === 'text-out' || (!edge.sourceHandle && source?.type === 'text_input') ? 'text' : 'image'
}
export function effectivePrompt(id, nodes, edges, visited = new Set()) {
  if (visited.has(id)) return ''
  const node = nodes.find(item => item.id === id)
  if (!node) return ''
  const seen = new Set([...visited, id])
  const parts = edges.filter(edge => edge.target === id && connectionKind(edge, nodes) === 'text')
    .map(edge => effectivePrompt(edge.source, nodes, edges, seen)).filter(Boolean)
  const local = node.type === 'text_input' ? node.data.text : node.data.prompt
  if (local?.trim()) parts.push(local.trim())
  return parts.join('\n\n')
}
export function normalizeConnection(edge, nodes) {
  const text = connectionKind(edge, nodes) === 'text'
  return { ...edge, sourceHandle: edge.sourceHandle || (text ? 'text-out' : 'media-out'),
    targetHandle: edge.targetHandle || (text ? 'text-in' : 'image-in'),
    label: text ? '提示词' : nodes.find(node => node.id === edge.target)?.type === 'video_generate' ? '首帧' : '参考图' }
}
export function connectionError(connection, nodes, edges) {
  const source = nodes.find(node => node.id === connection.source)
  const target = nodes.find(node => node.id === connection.target)
  if (!source || !isGenerationNode(target)) return '请连接到生成节点的左侧输入端口'
  if (source.id === target.id) return '节点不能连接自身'
  const kind = connectionKind(connection, nodes)
  if (kind === 'text' && connection.targetHandle !== 'text-in') return '紫色提示词输出只能连接紫色提示词输入'
  if (kind === 'image' && connection.targetHandle !== 'image-in') return '绿色素材输出只能连接绿色图片输入'
  if (kind === 'image' && source.type === 'video_generate') return '当前模型不支持参考视频；可通过紫色端口传递提示词'
  if (edges.some(edge => edge.source === source.id && edge.target === target.id && connectionKind(edge, nodes) === kind)) return '这两个端口已经连接'
  const queue = [target.id], seen = new Set()
  while (queue.length) {
    const id = queue.pop()
    if (id === source.id) return '该连接会形成循环，请选择其他节点'
    if (seen.has(id)) continue
    seen.add(id)
    queue.push(...edges.filter(edge => edge.source === id).map(edge => edge.target))
  }
  return ''
}
