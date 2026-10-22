import express from 'express';
import { randomUUID } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import dns from 'node:dns/promises';
import { chromium } from 'playwright';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import { createMcpExpressApp } from '@modelcontextprotocol/sdk/server/express.js';
import * as z from 'zod/v4';

const PORT = Number(process.env.PORT || 8129);
const SCREENSHOT_DIR = path.resolve(process.env.SCREENSHOT_DIR || './screenshots');
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || `http://127.0.0.1:${PORT}`).replace(/\/$/, '');
const CHROME_EXECUTABLE_PATH = process.env.CHROME_EXECUTABLE_PATH || '';
const ALLOW_PRIVATE = process.env.BROWSER_ALLOW_PRIVATE_HOSTS === 'true';
const ALLOWED_HOSTS = (process.env.BROWSER_ALLOWED_HOSTS || '*').split(',').map(v => v.trim()).filter(Boolean);
const SCREENSHOT_TTL_MS = Number(process.env.SCREENSHOT_TTL_MS || 30 * 60 * 1000);
const NAV_TIMEOUT_MS = 20000;
const MAX_WAIT_MS = 5000;

await fs.mkdir(SCREENSHOT_DIR, { recursive: true });

let browserPromise;
async function getBrowser() {
  if (!browserPromise) {
    browserPromise = chromium.launch({
      headless: true,
      executablePath: CHROME_EXECUTABLE_PATH || undefined,
      args: ['--no-sandbox', '--disable-dev-shm-usage']
    });
  }
  return browserPromise;
}

function isPrivateAddress(address) {
  if (address === '::1' || address === '0.0.0.0') return true;
  if (address.startsWith('127.') || address.startsWith('10.') || address.startsWith('192.168.')) return true;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(address)) return true;
  if (address.startsWith('169.254.') || address.startsWith('fc') || address.startsWith('fd') || address.startsWith('fe80:')) return true;
  return false;
}

function hostAllowed(host) {
  if (ALLOWED_HOSTS.includes('*')) return true;
  const lower = host.toLowerCase();
  return ALLOWED_HOSTS.some(pattern => pattern.startsWith('*.')
    ? lower.endsWith(pattern.slice(1).toLowerCase())
    : lower === pattern.toLowerCase());
}

async function validateUrl(rawUrl) {
  let uri;
  try { uri = new URL(rawUrl); } catch { throw new Error('url is invalid'); }
  if (uri.protocol !== 'http:' && uri.protocol !== 'https:') throw new Error('url must use http or https');
  if (!hostAllowed(uri.hostname)) throw new Error('url host is not allowed');
  if (!ALLOW_PRIVATE) {
    const addresses = await dns.lookup(uri.hostname, { all: true });
    if (!addresses.length || addresses.some(item => isPrivateAddress(item.address))) {
      throw new Error('private network addresses are not allowed');
    }
  }
  return uri;
}

async function cleanupOldScreenshots() {
  const now = Date.now();
  for (const name of await fs.readdir(SCREENSHOT_DIR)) {
    const file = path.join(SCREENSHOT_DIR, name);
    try {
      const stat = await fs.stat(file);
      if (now - stat.mtimeMs > SCREENSHOT_TTL_MS) await fs.unlink(file);
    } catch { /* ignore */ }
  }
}

async function screenshotPage({ url, full_page = false, width = 1440, height = 900, wait_ms = 1000 }) {
  const uri = await validateUrl(url);
  const viewportWidth = Math.max(320, Math.min(2560, Number(width) || 1440));
  const viewportHeight = Math.max(200, Math.min(1600, Number(height) || 900));
  const waitMs = Math.max(0, Math.min(MAX_WAIT_MS, Number(wait_ms) || 0));
  const browser = await getBrowser();
  const context = await browser.newContext({
    viewport: { width: viewportWidth, height: viewportHeight },
    userAgent: 'zwx-browser-mcp/0.1'
  });
  const page = await context.newPage();
  try {
    await page.goto(uri.toString(), { waitUntil: 'load', timeout: NAV_TIMEOUT_MS });
    if (waitMs > 0) await page.waitForTimeout(waitMs);
    await cleanupOldScreenshots();
    const id = `${randomUUID()}.png`;
    const file = path.join(SCREENSHOT_DIR, id);
    await page.screenshot({ path: file, fullPage: Boolean(full_page), type: 'png' });
    const stat = await fs.stat(file);
    return {
      url: `${PUBLIC_BASE_URL}/screenshots/${id}`,
      markdown: `![screenshot](${PUBLIC_BASE_URL}/screenshots/${id})`,
      filename: id,
      width: viewportWidth,
      height: viewportHeight,
      full_page: Boolean(full_page),
      bytes: stat.size
    };
  } finally {
    await context.close();
  }
}

function createServer() {
  const server = new McpServer({ name: 'zwx-browser-mcp-server', version: '0.0.1' });
  server.registerTool('screenshot_page', {
    description: 'Render a web page in headless Chromium and save a screenshot. Returns a short-lived image URL.',
    inputSchema: {
      url: z.string().describe('Public http(s) page URL'),
      full_page: z.boolean().optional().default(false).describe('Capture the full scrollable page'),
      width: z.number().int().min(320).max(2560).optional().default(1440),
      height: z.number().int().min(200).max(1600).optional().default(900),
      wait_ms: z.number().int().min(0).max(5000).optional().default(1000)
    }
  }, async (args) => {
    try {
      const result = await screenshotPage(args);
      return { content: [{ type: 'text', text: JSON.stringify(result) }] };
    } catch (error) {
      return { content: [{ type: 'text', text: JSON.stringify({ error: error.message || String(error) }) }], isError: true };
    }
  });
  return server;
}

const app = createMcpExpressApp({ host: '127.0.0.1' });
const transports = new Map();

app.get('/health', (_req, res) => res.type('text/plain').send('ok'));
app.use('/screenshots', express.static(SCREENSHOT_DIR, { fallthrough: false, maxAge: '10m' }));

app.get('/sse', async (_req, res) => {
  try {
    const transport = new SSEServerTransport('/messages', res);
    transports.set(transport.sessionId, transport);
    transport.onclose = () => transports.delete(transport.sessionId);
    const server = createServer();
    await server.connect(transport);
    console.log(`SSE session established: ${transport.sessionId}`);
  } catch (error) {
    console.error('Failed to establish SSE session', error);
    if (!res.headersSent) res.status(500).send('Failed to establish SSE session');
  }
});

app.post('/messages', async (req, res) => {
  const sessionId = String(req.query.sessionId || '');
  const transport = transports.get(sessionId);
  if (!transport) return res.status(404).send('Session not found');
  try {
    await transport.handlePostMessage(req, res, req.body);
  } catch (error) {
    console.error('Failed to handle MCP message', error);
    if (!res.headersSent) res.status(500).send('Failed to handle MCP message');
  }
});

const httpServer = app.listen(PORT, '127.0.0.1', () => {
  console.log(`zwx-browser-mcp-server listening on http://127.0.0.1:${PORT}`);
  console.log(`SSE endpoint: http://127.0.0.1:${PORT}/sse`);
});

async function shutdown() {
  for (const transport of transports.values()) {
    try { await transport.close(); } catch { /* ignore */ }
  }
  httpServer.close();
  if (browserPromise) {
    try { await (await browserPromise).close(); } catch { /* ignore */ }
  }
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
