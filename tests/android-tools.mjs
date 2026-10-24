import { existsSync, statSync } from 'node:fs'
import { apply } from '../dsh-plugin/android-tool.mjs'

const NO_ROOT = process.env.DSH_ANDROID_NO_ROOT === '1'
const tools = new Map()
apply({ tools: { register: definition => tools.set(definition.name, definition) }, get: () => undefined, logger: console })
const exec = { signal: new AbortController().signal }
let passed = 0
let failed = 0

async function run(name, args, check) {
  try {
    const value = await tools.get(name).execute(args, exec)
    const text = typeof value === 'string' ? value : JSON.stringify(value)
    const ok = check ? check(text, value) : true
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name}: ${text.replace(/\n/gu, ' | ').slice(0, 220)}`)
    ok ? passed++ : failed++
    return value
  } catch (error) {
    console.log(`FAIL ${name}: ${error.message}`)
    failed++
    return undefined
  }
}

console.log('registered: ' + [...tools.keys()].join(', '))

await run('android_health', {}, text => /"ok":\s*true/u.test(text))
await run('android_apps', {}, text => text.includes('launchable apps'))
await run('android_act', { op: 'launch', package: 'com.android.chrome' }, text =>
  NO_ROOT
    ? text.includes('launched com.android.chrome')
    : text.includes('launch com.android.chrome') && text.includes('exit=0'))
if (NO_ROOT) {
  console.log('SKIP android_act wait: Android/MIUI may block background app launch without root')
} else {
  await run('android_act', { op: 'wait', package: 'com.android.chrome', timeoutMs: 8000 }, text => text.includes('condition met'))
}
const initial = await run('android_state', { maxElements: 300 }, text => text.includes('# package='))
const initialEditable = initial && initial.split('\n').find(line => line.includes('editable'))
if (initialEditable) {
  const match = initialEditable.match(/^\[(\d+)\]/u)
  const id = Number(match[1])
  await run('android_act', { op: 'set_text', id, text: 'tool-test' }, text => text.includes('text set'))
  await run('android_act', { op: 'clear_text', id }, text => text.includes('text set'))
} else {
  console.log('SKIP android_act set_text: no editable node in the initial state')
}

const shot = await run('android_screenshot', { maxWidth: 360, quality: 60 }, (_text, value) => value && existsSync(value.path) && statSync(value.path).size > 0)
if (shot) console.log(`screenshot file: ${shot.path} ${shot.width}x${shot.height}`)

if (NO_ROOT) {
  console.log('SKIP android_shell: no-root mode')
} else {
  await run('android_shell', { command: 'id; wm size; getprop ro.product.model; am force-stop com.android.settings; input keyevent 3; echo SHELL_OK', timeoutMs: 15000 }, text => text.includes('uid=0') && text.includes('SHELL_OK'))
}

if (NO_ROOT) {
  console.log('SKIP Settings launch/wait/click: direct background launch needs root on this device')
} else {
  await run('android_act', { op: 'launch', package: 'com.android.settings' }, text => text.includes('launch com.android.settings') && text.includes('exit=0'))
  await run('android_act', { op: 'wait', package: 'com.android.settings', timeoutMs: 8000 }, text => text.includes('condition met'))
  await run('android_state', { maxElements: 200 }, text => text.includes('com.android.settings'))
  await run('android_act', { op: 'click', text: 'WLAN' }, text => text.includes('dispatched'))
  await run('android_act', { op: 'global', global: 'back' }, text => text.includes('dispatched'))
}



await run('android_act', { op: 'global', global: 'home' }, text => text.includes('dispatched'))

console.log(`RESULT passed=${passed} failed=${failed}`)
process.exit(failed === 0 ? 0 : 1)
