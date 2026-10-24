/**
 * Android runtime bootstrap.
 *
 * Runs before the dsh entry through `node --import`. It keeps the installed
 * dsh package unmodified by aliasing two modules whose published packages do
 * not support Android:
 *   - @deepseek-ai/node-addon-system/flock -> koffi-backed bionic flock(2)
 *   - node-addon-require-builtin          -> --expose-internals shim
 *
 * ESM imports go through the loader hook; CommonJS require calls go through
 * the Module._load patch. The Cordis android-compat plugin owns behavior
 * patches that can safely run after app startup.
 */

import Module, { createRequire, register } from 'node:module'

const require = createRequire(import.meta.url)

register('./loader.mjs', import.meta.url)

const originalLoad = Module._load
Module._load = function load(request, parent, isMain) {
  if (request === 'node-addon-require-builtin') {
    return require('./require-builtin.cjs')
  }
  if (request === '@deepseek-ai/node-addon-system/flock') {
    return require('./flock.cjs')
  }
  return originalLoad.apply(this, arguments)
}
