const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('zwxDesktop', {
  apiBaseUrl: ipcRenderer.sendSync('desktop:get-api-base-url'),
  settings: ipcRenderer.sendSync('desktop:get-settings'),
  saveSettings: value => ipcRenderer.invoke('desktop:save-settings', value)
})
