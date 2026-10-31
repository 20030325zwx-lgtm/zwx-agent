// 工作流运行输入推导（R03，2026-10-31 评审）：从图参数扫描 {{input.KEY}} / {{start.output.KEY}}，
// 创作首页、项目页与编辑器共用同一套推导与表单渲染约定。
export function deriveRunInputs(graph) {
  const seen = new Set()
  const scan = value => {
    for (const match of String(value ?? '').matchAll(/\{\{(?:input|start\.output)\.([A-Za-z0-9_]+)\}\}/g)) {
      seen.add(match[1])
    }
  }
  for (const node of (graph && graph.nodes) || []) {
    Object.values(node.params || {}).forEach(scan)
  }
  const assetKeys = []
  const textKeys = []
  for (const key of seen) {
    ;(/AssetIds?$/.test(key) ? assetKeys : textKeys).push(key)
  }
  if (!textKeys.includes('keyword')) textKeys.unshift('keyword')
  return { assetKeys, textKeys }
}

export const isAssetInputKey = key => /AssetIds?$/.test(key)

/**
 * 运行表单字段（R03 完整版 / Infinite Canvas 批次二）：
 * 优先用工作流声明的 inputs（中文 label/类型/必填/占位），运行者不接触模板语法；
 * 未声明时回退扫描推导（label 显示 key）。返回统一结构 [{key,label,kind,required,placeholder}]。
 */
export function buildRunForm(definition) {
  const declared = Array.isArray(definition?.inputs) ? definition.inputs : []
  if (declared.length) {
    return declared.map(field => ({
      key: field.key,
      label: field.label || field.key,
      kind: field.type === 'asset' ? 'asset' : field.type === 'assets' ? 'assets' : 'text',
      required: !!field.required,
      placeholder: field.placeholder || ''
    }))
  }
  const { assetKeys, textKeys } = deriveRunInputs(definition?.graph || { nodes: [] })
  const fields = []
  for (const key of textKeys) {
    fields.push({ key, label: key, kind: 'text', required: false, placeholder: '' })
  }
  for (const key of assetKeys) {
    fields.push({ key, label: key, kind: 'assets', required: false, placeholder: '' })
  }
  return fields
}
