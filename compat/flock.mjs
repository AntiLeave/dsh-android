import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const mod = require('./flock.cjs')

export const tryLockExclusive = mod.tryLockExclusive
export default mod
