import { createReadStream, existsSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { join } from 'node:path'

const dir = process.argv[2]
const port = Number(process.argv[3] || 18080)
if (!dir || !existsSync(dir)) {
  console.error('usage: node serve.mjs <bundle-dir> [port]')
  process.exit(1)
}

const files = new Map([
  ['/setup.sh', 'setup.sh'],
  ['/deploy.tar.gz', 'deploy.tar.gz'],
])

createServer((request, response) => {
  const pathname = new URL(request.url || '/', 'http://127.0.0.1').pathname
  const name = files.get(pathname)
  if (!name) {
    response.writeHead(404, { 'content-type': 'text/plain' })
    response.end('not found\n')
    return
  }
  const file = join(dir, name)
  if (!existsSync(file)) {
    response.writeHead(404, { 'content-type': 'text/plain' })
    response.end('not built\n')
    return
  }
  response.writeHead(200, {
    'content-type': name.endsWith('.sh') ? 'text/x-shellscript' : 'application/gzip',
    'content-length': statSync(file).size,
    'cache-control': 'no-store',
  })
  createReadStream(file).pipe(response)
}).listen(port, '127.0.0.1', () => {
  console.log(`serving ${dir} on http://127.0.0.1:${port}`)
})
