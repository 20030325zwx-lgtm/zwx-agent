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
