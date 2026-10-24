import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const mod = require('./require-builtin.cjs')

export const requireBuiltin = mod.requireBuiltin
export const isAllowedInternalId = mod.isAllowedInternalId
export const getBindingInfo = mod.getBindingInfo
export default mod
