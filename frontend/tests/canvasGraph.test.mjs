import test from 'node:test'
import assert from 'node:assert/strict'
import { connectionError, effectivePrompt, normalizeConnection } from '../src/views/creative/project/canvasGraph.js'
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
