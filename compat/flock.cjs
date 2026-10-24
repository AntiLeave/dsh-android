'use strict'

const { createRequire } = require('node:module')
const { dirname, join } = require('node:path')
const { getSystemErrorName } = require('node:util')

let binding

function loadKoffi() {
  try {
    return require('koffi')
  } catch {}
  const candidates = []
  if (process.env.PREFIX) {
    candidates.push(join(process.env.PREFIX, 'lib', 'node_modules', '@deepseek-ai', 'dsh', 'node_modules', 'koffi'))
  }
  const root = dirname(process.argv[1] || '')
  candidates.push(join(root, '..', 'node_modules', 'koffi'))
  for (const candidate of candidates) {
    try {
      return require(candidate)
    } catch {}
  }
  throw new Error('koffi is not available for the Android flock shim')
}

function loadBinding() {
  if (binding) return binding
  const koffi = loadKoffi()
  const libc = koffi.load(null)
  const flock = libc.func('int flock(int fd, int operation)')
  const LOCK_EX = 2
  const LOCK_NB = 4
  binding = {
    tryLock(fd, callback) {
      const result = flock(fd, LOCK_EX | LOCK_NB)
      callback(result === 0 ? 0 : koffi.errno())
    },
  }
  return binding
}

async function tryLockExclusive(fd) {
  const errno = await new Promise((resolve) => {
    loadBinding().tryLock(fd, resolve)
  })
  if (errno === 0) return
  const code = getSystemErrorName(-errno)
  throw Object.assign(new Error(`${code}: flock failed`), {
    code,
    errno,
    syscall: 'flock',
  })
}

module.exports = { tryLockExclusive }
