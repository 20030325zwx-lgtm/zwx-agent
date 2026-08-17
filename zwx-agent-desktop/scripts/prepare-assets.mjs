import { cp, mkdir, rm } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const desktopDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const frontendDist = resolve(desktopDir, '..', 'zwx-agent-frontend', 'dist')
const appDir = resolve(desktopDir, 'app')

await rm(appDir, { recursive: true, force: true })
await mkdir(appDir, { recursive: true })
await cp(frontendDist, appDir, { recursive: true })
