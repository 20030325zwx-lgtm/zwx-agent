export const isGenerationNode = node => ['image_generate', 'image_edit', 'video_generate'].includes(node?.type)
export function connectionKind(edge, nodes) {
  const source = nodes.find(node => node.id === edge.source)
  if (edge.sourceHandle === 'flow-out' || source?.type === 'start') return 'flow'
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
  if (connectionKind(edge, nodes) === 'flow') return { ...edge, sourceHandle: 'flow-out', targetHandle: edge.targetHandle || 'flow-in', label: '执行顺序' }
  const text = connectionKind(edge, nodes) === 'text'
  return { ...edge, sourceHandle: edge.sourceHandle || (text ? 'text-out' : 'media-out'),
    targetHandle: edge.targetHandle || (text ? 'text-in' : 'image-in'),
    label: text ? '提示词' : nodes.find(node => node.id === edge.target)?.type === 'video_generate' ? '首帧' : '参考图' }
}
export function connectionError(connection, nodes, edges) {
  const source = nodes.find(node => node.id === connection.source)
  const target = nodes.find(node => node.id === connection.target)
  if (!source || !target || target.type === 'start') return '开始节点不能连接上级节点'
  if (source.id === target.id) return '节点不能连接自身'
  const kind = connectionKind(connection, nodes)
  if (kind !== 'flow' && !isGenerationNode(target)) return '素材和提示词只能连接到生成节点'
  if (kind === 'flow' && connection.targetHandle !== 'flow-in') return '执行端口只能连接执行输入'
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

// Upgrade existing drafts once; disconnected nodes created later must be wired explicitly.
export function ensureStart(graph) {
  if (graph.nodes.some(node => node.type === 'start')) return graph
  const nodes = [...graph.nodes], edges = [...graph.edges]
  let id = 'canvas-start'
  while (nodes.some(node => node.id === id)) id += '-1'
  const roots = nodes.filter(node => !edges.some(edge => edge.target === node.id))
  const start = { id, type: 'start', position: {
    x: Math.min(0, ...nodes.map(node => node.position?.x || 0)) - 320,
    y: roots[0]?.position?.y || 140
  }, data: { label: '开始', status: 'IDLE' }, deletable: false }
  return { nodes: [start, ...nodes], edges: [...edges, ...roots.map(node => ({
    id: `${id}-${node.id}`, source: id, target: node.id, sourceHandle: 'flow-out', targetHandle: 'flow-in', label: '执行顺序'
  }))] }
}

export function dependencyIssue(id, nodes, edges, scope = 'node') {
  const start = nodes.find(node => node.type === 'start')
  if (!start) return '缺少开始节点'
  const reachable = new Set([start.id]), queue = [start.id]
  while (queue.length) {
    const current = queue.shift()
    for (const edge of edges.filter(edge => edge.source === current)) {
      if (!reachable.has(edge.target)) { reachable.add(edge.target); queue.push(edge.target) }
    }
  }
  const included = new Set(scope === 'all' ? nodes.filter(isGenerationNode).map(node => node.id) : [id])
  if (scope === 'from_here') {
    const pending = [id]
    while (pending.length) {
      const current = pending.shift()
      for (const edge of edges.filter(edge => edge.source === current)) {
        if (!included.has(edge.target)) { included.add(edge.target); pending.push(edge.target) }
      }
    }
  }
  if (scope === 'up_to') {
    const pending = [id]
    while (pending.length) {
      const current = pending.shift()
      for (const edge of edges.filter(edge => edge.target === current)) {
        if (!included.has(edge.source)) { included.add(edge.source); pending.push(edge.source) }
      }
    }
    for (const key of [...included]) {
      const node = nodes.find(item => item.id === key)
      if (!node) { included.delete(key); continue }
      // Succeeded ancestors are skipped by an up_to run; failed ones re-run; running ones block.
      if (key !== id && isGenerationNode(node) && node.data.status === 'SUCCEEDED') { included.delete(key); continue }
      if (['QUEUED', 'SUBMITTING', 'RUNNING', 'WAITING_EXTERNAL'].includes(node.data.status)) {
        return `请等待「${node.data.label || key}」生成完成后再执行`
      }
      if (node.data.status === 'WAITING_SELECTION') {
        return `「${node.data.label || key}」待选片，请先选择结果再继续执行`
      }
    }
  }
  for (const key of included) if (!reachable.has(key)) return '请先将运行节点连接到开始节点所在流程'
  const visited = new Set(), ancestors = [...included]
  while (ancestors.length) {
    const key = ancestors.pop()
    for (const edge of edges.filter(edge => edge.target === key)) {
      const parent = nodes.find(node => node.id === edge.source)
      if (!parent || visited.has(parent.id)) continue
      visited.add(parent.id); ancestors.push(parent.id)
      if (!included.has(parent.id) && isGenerationNode(parent) && parent.data.status !== 'SUCCEEDED') {
        return `请等待上级「${parent.data.label}」成功完成后再执行`
      }
    }
  }
  return ''
}

// One drag between two generation nodes should carry the full result: prompt plus reference image.
// Returns the sibling edge to auto-add, or null when it would be invalid (duplicate, cycle,
// unsupported port) — `edges` must already contain the freshly added dragged edge.
export function siblingConnection(edge, nodes, edges) {
  const source = nodes.find(node => node.id === edge.source)
  const target = nodes.find(node => node.id === edge.target)
  if (!isGenerationNode(source) || !isGenerationNode(target)) return null
  const kind = connectionKind(edge, nodes)
  if (kind !== 'text' && kind !== 'image') return null
  const sibling = kind === 'text'
    ? { ...edge, sourceHandle: 'media-out', targetHandle: 'image-in',
        label: target.type === 'video_generate' ? '首帧' : '参考图' }
    : { ...edge, sourceHandle: 'text-out', targetHandle: 'text-in', label: '提示词' }
  return connectionError(sibling, nodes, edges) ? null : sibling
}
