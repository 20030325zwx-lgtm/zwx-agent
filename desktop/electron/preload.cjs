const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('zwxDesktop', {
  apiBaseUrl: ipcRenderer.sendSync('desktop:get-api-base-url'),
  settings: ipcRenderer.sendSync('desktop:get-settings'),
  saveSettings: value => ipcRenderer.invoke('desktop:save-settings', value),
  // 本机回环令牌：与后端 app.desktop.loopback-token 匹配时免登录使用本机身份
  localToken: ipcRenderer.sendSync('desktop:get-local-token'),
  runtimeState: ipcRenderer.sendSync('desktop:get-runtime-state'),
  // 本机目录授权：系统选择器取得用户授权，授权清单经主进程同步给后端
  chooseDirectory: () => ipcRenderer.invoke('desktop:choose-directory'),
  directoryGrants: () => ipcRenderer.sendSync('desktop:get-directory-grants'),
  revokeDirectoryGrant: path => ipcRenderer.invoke('desktop:revoke-directory-grant', path),
  // 产物保存：系统保存框 + 写盘；导出后在文件管理器中显示
  saveFile: payload => ipcRenderer.invoke('desktop:save-file', payload),
  revealFile: path => ipcRenderer.invoke('desktop:reveal-file', path)
})
