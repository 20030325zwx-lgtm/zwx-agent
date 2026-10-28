const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('zwxDesktop', {
  apiBaseUrl: ipcRenderer.sendSync('desktop:get-api-base-url'),
  settings: ipcRenderer.sendSync('desktop:get-settings'),
  saveSettings: value => ipcRenderer.invoke('desktop:save-settings', value),
  // 本机回环令牌：与后端 app.desktop.loopback-token 匹配时免登录使用本机身份
  localToken: ipcRenderer.sendSync('desktop:get-local-token'),
  runtimeState: ipcRenderer.sendSync('desktop:get-runtime-state')
})
