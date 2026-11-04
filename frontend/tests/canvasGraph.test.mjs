import test from 'node:test'
import assert from 'node:assert/strict'
import { connectionError, effectivePrompt, normalizeConnection, ensureStart, dependencyIssue, siblingConnection } from '../src/views/creative/project/canvasGraph.js'
const nodes = [
  { id: 'text', type: 'text_input', data: { text: '日落海边' } },
  { id: 'image', type: 'image_generate', data: { prompt: '胶片质感' } },
  { id: 'video', type: 'video_generate', data: { prompt: '' } },
]
const textEdge = (source, target) => ({ source, target, sourceHandle: 'text-out', targetHandle: 'text-in' })
test('downstream prompt reads upstream text and generation prompts recursively', () => {
  assert.equal(effectivePrompt('video', nodes, [textEdge('text', 'image'), textEdge('image', 'video')]), '日落海边\n\n胶片质感')
})
test('typed connections reject mismatches, duplicates and cycles', () => {
  assert.match(connectionError({ ...textEdge('text', 'image'), targetHandle: 'image-in' }, nodes, []), /紫色/)
  assert.match(connectionError(textEdge('image', 'video'), nodes, [textEdge('image', 'video')]), /已经连接/)
  assert.match(connectionError(textEdge('video', 'image'), nodes, [textEdge('image', 'video')]), /循环/)
  assert.equal(connectionError(textEdge('video', 'image'), nodes, []), '')
})
test('legacy text and image edges recover explicit port ids', () => {
  assert.equal(normalizeConnection({ source: 'text', target: 'image' }, nodes).targetHandle, 'text-in')
  assert.equal(normalizeConnection({ source: 'image', target: 'video' }, nodes).targetHandle, 'image-in')
})

test('a legacy draft gets exactly one no-op start, connected to all roots', () => {
  const graph = ensureStart({ nodes, edges: [textEdge('text', 'image')] })
  assert.equal(graph.nodes.filter(node => node.type === 'start').length, 1)
  assert.equal(graph.edges.filter(edge => edge.source === 'canvas-start').length, 2)
  assert.deepEqual(ensureStart(graph), graph)
  assert.match(connectionError({ source: 'image', target: 'canvas-start', sourceHandle: 'flow-out', targetHandle: 'flow-in' }, graph.nodes, graph.edges), /开始节点/)
})
test('all parents must succeed, including text/flow dependencies and transitive ancestors', () => {
  const local = structuredClone(nodes)
  local.push({ id: 'image2', type: 'image_generate', data: { label: '第二张图', status: 'RUNNING' } })
  local.find(node => node.id === 'image').data.status = 'SUCCEEDED'
  const graph = ensureStart({ nodes: local, edges: [textEdge('image', 'video'), { source: 'image2', target: 'video', sourceHandle: 'flow-out', targetHandle: 'flow-in' }] })
  assert.match(dependencyIssue('video', graph.nodes, graph.edges), /第二张图/)
  for (const status of ['IDLE', 'QUEUED', 'RUNNING', 'WAITING_SELECTION', 'FAILED', 'CANCELLED', 'SKIPPED']) {
    local.find(node => node.id === 'image2').data.status = status
    assert.notEqual(dependencyIssue('video', graph.nodes, graph.edges), '')
  }
  local.find(node => node.id === 'image2').data.status = 'SUCCEEDED'
  assert.equal(dependencyIssue('video', graph.nodes, graph.edges), '')
  assert.equal(dependencyIssue('image', graph.nodes, graph.edges, 'all'), '')
  local.find(node => node.id === 'image2').data.status = 'RUNNING'
  assert.match(dependencyIssue('image', graph.nodes, graph.edges, 'from_here'), /第二张图/)
})
test('unconnected new generation nodes cannot execute', () => {
  const graph = ensureStart({ nodes: [], edges: [] })
  graph.nodes.push(...nodes)
  assert.match(dependencyIssue('image', graph.nodes, graph.edges), /连接到开始节点/)
})
test('up_to runs ancestors to the selected node, skipping succeeded ones', () => {
  const local = structuredClone(nodes)
  local.push({ id: 'image2', type: 'image_generate', data: { label: '第二张图', status: 'SUCCEEDED' } })
  const graph = ensureStart({ nodes: local, edges: [textEdge('image', 'video'), textEdge('image2', 'video'),
    { source: 'image', target: 'image2', sourceHandle: 'flow-out', targetHandle: 'flow-in' }] })
  local.find(node => node.id === 'image').data.status = 'SUCCEEDED'
  local.find(node => node.id === 'image2').data.status = 'RUNNING'
  assert.match(dependencyIssue('video', graph.nodes, graph.edges, 'up_to'), /第二张图/)
  local.find(node => node.id === 'image2').data.status = 'FAILED'
  assert.equal(dependencyIssue('video', graph.nodes, graph.edges, 'up_to'), '')
  local.find(node => node.id === 'image2').data.status = 'WAITING_SELECTION'
  assert.match(dependencyIssue('video', graph.nodes, graph.edges, 'up_to'), /待选片/)
  local.find(node => node.id === 'image2').data.status = 'SUCCEEDED'
  local.find(node => node.id === 'video').data.status = 'RUNNING'
  assert.match(dependencyIssue('video', graph.nodes, graph.edges, 'up_to'), /生成完成/)
})
test('generation-to-generation drags auto-carry both prompt and reference image', () => {
  const gen = [
    { id: 'a', type: 'image_generate', data: { label: 'A', prompt: 'x' } },
    { id: 'b', type: 'image_generate', data: { label: 'B', prompt: 'y' } },
    { id: 'v', type: 'video_generate', data: { label: 'V', prompt: 'z' } },
  ]
  const mediaEdge = { source: 'a', target: 'b', sourceHandle: 'media-out', targetHandle: 'image-in', label: '参考图' }
  const sibling = siblingConnection(mediaEdge, gen, [mediaEdge])
  assert.equal(sibling.sourceHandle, 'text-out')
  assert.equal(sibling.targetHandle, 'text-in')
  assert.equal(siblingConnection({ source: 'a', target: 'v', sourceHandle: 'text-out', targetHandle: 'text-in' }, gen, []).label, '首帧')
  assert.equal(siblingConnection({ source: 'v', target: 'b', sourceHandle: 'text-out', targetHandle: 'text-in' }, gen, []), null,
    'video results cannot become reference images')
  assert.equal(siblingConnection(mediaEdge, gen, [mediaEdge, sibling]), null, 'existing sibling kind suppresses the auto edge')
  assert.equal(siblingConnection(textEdge('text', 'image'), nodes, []), null, 'non-generation pairs never dual-connect')
})
