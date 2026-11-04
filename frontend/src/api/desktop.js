// 桌面端（Electron）能力桥：保存文件 / 在文件管理器中显示。
// 浏览器环境没有 window.zwxDesktop，相关入口保持隐藏。
export const desktopBridge = () => (typeof window !== 'undefined' ? window.zwxDesktop || null : null)

export const desktopSaveAvailable = () => !!desktopBridge()?.saveFile

/** 通过系统保存框把已鉴权的 blob URL 落到用户选择的位置，返回绝对路径（取消则 null）。 */
export const saveUrlWithDialog = async (url, fileName) => {
  const bridge = desktopBridge()
  if (!bridge?.saveFile || !url) return null
  const response = await fetch(url)
  const data = new Uint8Array(await response.arrayBuffer())
  return bridge.saveFile({ fileName, data })
}

/** 导出后定位文件（Finder / 资源管理器）。 */
export const revealInFolder = path => {
  const bridge = desktopBridge()
  if (!bridge?.revealFile || !path) return null
  return bridge.revealFile(path)
}

/** 打开目录选择器并授权（用于「保存到文件夹」）。 */
export const chooseExportDirectory = () => {
  const bridge = desktopBridge()
  return bridge?.chooseDirectory ? bridge.chooseDirectory() : null
}
