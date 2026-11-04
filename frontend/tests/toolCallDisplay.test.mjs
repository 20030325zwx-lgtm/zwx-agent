import test from 'node:test'
import assert from 'node:assert/strict'
import { redactToolText, toolCallArguments, toolCallStatus } from '../src/utils/toolCallDisplay.mjs'

test('shows only approved argument fields and masks common secrets', () => {
  const call = { arguments: JSON.stringify({ command: 'curl --token abc123', content: 'private body', apiKey: 'secret', path: 'src/App.vue' }) }
  assert.equal(toolCallArguments(call), 'command: curl --token [已隐藏]\npath: src/App.vue')
  assert.equal(toolCallArguments({ arguments: '{"path":' }), '')
  assert.equal(redactToolText('Authorization: Bearer abc123'), 'Authorization: [已隐藏] [已隐藏]')
})

test('distinguishes ledger states', () => {
  assert.deepEqual(['PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'].map(status => toolCallStatus({ status })),
    ['调用中', '已返回', '调用失败', '结果未知'])
})
