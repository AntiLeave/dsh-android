/**
 * Runtime compatibility patches for Android/bionic.
 *
 * Loaded as an ordinary Cordis plugin before the tool plugin. It keeps the
 * dsh source tree unmodified: the only behavior changed here is Node's
 * FileHandle.sync on Android, where bionic returns EINVAL for fsync on the
 * filesystem root. DSH's durability walks treat the highest syncable directory
 * as their boundary, so swallowing that one error keeps attachment and session
 * writes working without a source patch.
 */

import { open } from 'node:fs/promises'

export const name = 'android-compat'

const PATCHED = Symbol.for('dsh.android-compat.filehandle-sync')

export async function apply() {
  if (process.platform !== 'android') return
  // FileHandle is not a named export; obtain its prototype from a real handle.
  const probe = await open('/dev/null', 'r')
  const prototype = Object.getPrototypeOf(probe)
  await probe.close()
  if (prototype[PATCHED] === true) return
  const original = prototype.sync
  const patched = async function sync(...args) {
    try {
      return await original.apply(this, args)
    } catch (error) {
      if (error?.code === 'EINVAL') return
      throw error
    }
  }
  Object.defineProperty(patched, PATCHED, { value: true })
  prototype.sync = patched
  Object.defineProperty(prototype, PATCHED, { value: true })
  console.log('[android-compat] FileHandle.sync ignores Android EINVAL')
}
