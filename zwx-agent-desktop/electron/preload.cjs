const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('zwxDesktop', {
  apiBaseUrl: ipcRenderer.sendSync('desktop:get-api-base-url'),
  setApiBaseUrl: value => ipcRenderer.invoke('desktop:set-api-base-url', value)
})
