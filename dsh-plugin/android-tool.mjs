/**
 * DeepSeek Harness tools for the DSH Android accessibility bridge.
 *
 * The bridge APK listens on 127.0.0.1:17654. This plugin is loaded from a
 * cordis patch on the device; it has no dependency on the harness monorepo.
 */

const BASE = 'http://127.0.0.1:17654'
const ATTACHMENT_ENTRY =
  'file:///data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment/lib/index.js'
const NO_ROOT = process.env.DSH_ANDROID_NO_ROOT === '1'

async function bridge(path, init = {}) {
  const timeoutMs = typeof init.timeoutMs === 'number' ? init.timeoutMs : 30000
  const { timeoutMs: _ignored, signal, ...rest } = init
  const deadline = AbortSignal.timeout(timeoutMs)
  const combined = signal ? AbortSignal.any([signal, deadline]) : deadline
  let response
  try {
    response = await fetch(BASE + path, { ...rest, signal: combined })
  } catch (error) {
    throw new Error(
      `Android bridge is not reachable at ${BASE}. Enable the DSH Bridge accessibility service and retry. ${error.message}`,
    )
  }
  const text = await response.text()
  let value
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error(`Android bridge returned non-JSON (HTTP ${response.status}): ${text.slice(0, 400)}`)
  }
  if (!response.ok || value.ok === false) {
    throw new Error(value.error ? String(value.error) : `Android bridge HTTP ${response.status}`)
  }
  return value
}

function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`
}

async function runShell(command, signal, timeoutMs = 30000) {
  const { execFile } = await import('node:child_process')
  const env = { ...process.env }
  delete env.LD_LIBRARY_PATH
  delete env.LD_PRELOAD
  const timeout = Math.max(1000, Math.min(120000, timeoutMs))
  return await new Promise(resolve => {
    execFile(
      'su',
      ['-c', command],
      { timeout, maxBuffer: 4 * 1024 * 1024, signal, env },
      (error, stdout, stderr) => {
        const code = error ? (error.killed ? 'timeout' : (error.code ?? 1)) : 0
        resolve({ code, output: `${stdout ?? ''}${stderr ?? ''}`.trim() })
      },
    )
  })
}
function act(args, signal, timeoutMs) {
  return bridge('/action', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(args),
    ...(signal === undefined ? {} : { signal }),
    ...(timeoutMs === undefined ? {} : { timeoutMs }),
  })
}

function text(value) {
  return [{ type: 'text', text: value }]
}

function number(value, fallback) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

const commonTarget = {
  id: { type: 'number', description: 'Element id from the latest android_state result.' },
  text: { type: 'string', description: 'Visible text to match when id is omitted.' },
  desc: { type: 'string', description: 'Content description to match when id is omitted.' },
  viewId: { type: 'string', description: 'Resource id to match when id is omitted.' },
}

const actParameters = {
  type: 'object',
  properties: {
    op: {
      type: 'string',
      enum: [
        'click',
        'long_click',
        'set_text',
        'type_text',
        'clear_text',
        'scroll',
        'swipe',
        'tap',
        'global',
        'launch',
        'open_url',
        'clipboard',
        'wait',
        'find',
      ],
      description: 'Operation to perform on the device.',
    },
    ...commonTarget,
    x: { type: 'number', description: 'Screen x coordinate for tap.' },
    y: { type: 'number', description: 'Screen y coordinate for tap.' },
    fromX: { type: 'number' },
    fromY: { type: 'number' },
    toX: { type: 'number' },
    toY: { type: 'number' },
    duration: { type: 'number', description: 'Gesture duration in milliseconds.' },
    direction: { type: 'string', enum: ['up', 'down', 'left', 'right'] },
    global: {
      type: 'string',
      description: 'Global action: back, home, recents, notifications, quick_settings, power_dialog.',
    },
    package: { type: 'string', description: 'Android package name for launch/wait.' },
    app: { type: 'string', description: 'Application label for launch when package is omitted.' },
    url: { type: 'string', description: 'URL for open_url.' },
    timeoutMs: { type: 'number', description: 'Timeout for wait, default 8000.' },
    click: { type: 'boolean', description: 'For find, click the matched node.' },
  },
  required: ['op'],
  additionalProperties: false,
}

export const name = 'android-cua'
export const inject = ['tools']

export function apply(ctx) {
  ctx.tools.register({
    name: 'android_state',
    description:
      'Read the current Android accessibility tree. Returns a compact indented tree with element ids, text, bounds, and flags, plus the foreground package. Call this before acting and re-read after every action; element ids are valid only for the latest state.',
    parameters: {
      type: 'object',
      properties: {
        maxDepth: { type: 'number', description: 'Maximum tree depth, default 28.' },
        maxElements: { type: 'number', description: 'Maximum nodes, default 600.' },
      },
      additionalProperties: false,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => text(value),
    },
    async execute(args, exec) {
      const depth = Math.max(1, Math.min(80, number(args.maxDepth, 28)))
      const elements = Math.max(1, Math.min(3000, number(args.maxElements, 600)))
      const state = await bridge(`/state?maxDepth=${depth}&maxElements=${elements}`, {
        signal: exec.signal,
      })
      return `seq=${state.seq} package=${state.package} activity=${state.activity} truncated=${state.truncated}\n${state.text}`
    },
  })

  ctx.tools.register({
    name: 'android_act',
    description:
      'Perform one Android UI action through the accessibility bridge. Use id from the latest android_state, or text/desc/viewId. Ops: click, long_click, set_text, type_text, clear_text, scroll, swipe, tap, global, launch, open_url, clipboard, wait, find. The result reports dispatched=true, not verified; read android_state again to verify.',
    parameters: actParameters,
    output: {
      schema: { type: 'string' },
      render: (_args, value) => text(value),
    },
    async execute(args, exec) {
      const op = String(args.op)
      const timeout = { signal: AbortSignal.any([exec.signal, AbortSignal.timeout(20000)]) }
      switch (op) {
        case 'click':
        case 'long_click': {
          const result = await act({
            op: op === 'long_click' ? 'longClick' : 'click',
            ...(args.id === undefined ? {} : { id: args.id }),
            ...(args.text === undefined ? {} : { text: args.text }),
            ...(args.desc === undefined ? {} : { desc: args.desc }),
            ...(args.viewId === undefined ? {} : { viewId: args.viewId }),
          })
          return `${result.message}`
        }
        case 'set_text':
        case 'type_text':
        case 'clear_text': {
          const result = await act({
            op: op === 'set_text' ? 'setText' : op === 'type_text' ? 'typeText' : 'clearText',
            ...(args.id === undefined ? {} : { id: args.id }),
            ...(args.text === undefined ? {} : { text: args.text }),
          })
          return `${result.message}`
        }
        case 'scroll': {
          const result = await act({
            op: 'scroll',
            ...(args.id === undefined ? {} : { id: args.id }),
            ...(args.direction === undefined ? {} : { direction: args.direction }),
            ...(args.duration === undefined ? {} : { duration: args.duration }),
          })
          return `${result.message}`
        }
        case 'swipe': {
          const result = await act({
            op: 'swipe',
            fromX: args.fromX ?? args.x,
            fromY: args.fromY ?? args.y,
            toX: args.toX,
            toY: args.toY,
            ...(args.duration === undefined ? {} : { duration: args.duration }),
          })
          return `${result.message}`
        }
        case 'tap': {
          const result = await act({
            op: 'tap',
            x: args.x,
            y: args.y,
            ...(args.duration === undefined ? {} : { duration: args.duration }),
          })
          return `${result.message}`
        }
        case 'global': {
          const result = await act({ op: 'global', action: args.global ?? 'back' })
          return `${result.message}`
        }
        case 'launch': {
          if (NO_ROOT) {
            if (!args.package && !args.app) throw new Error('launch needs package or app')
            const result = await act({
              op: 'launch',
              ...(args.package === undefined ? {} : { package: args.package }),
              ...(args.app === undefined ? {} : { app: args.app }),
            })
            return `${result.message}`
          }
          let pkg = args.package
          if (!pkg && args.app) {
            const apps = await bridge('/apps', { signal: exec.signal })
            const match = apps.apps.find(item =>
              String(item.label).toLowerCase() === String(args.app).toLowerCase())
              ?? apps.apps.find(item =>
                String(item.label).toLowerCase().includes(String(args.app).toLowerCase()))
            pkg = match?.package
          }
          if (!pkg) throw new Error('launch needs package, or an app label that matches an installed app')
          const launched = await runShell(
            `monkey -p ${pkg} -c android.intent.category.LAUNCHER 1 2>&1`,
            exec.signal,
            15000,
          )
          return `launch ${pkg} exit=${launched.code}\n${launched.output}`
        }
        case 'open_url': {
          if (!args.url) throw new Error('open_url needs url')
          if (NO_ROOT) {
            const result = await act({ op: 'openUrl', url: args.url })
            return `${result.message}`
          }
          const opened = await runShell(
            `am start -a android.intent.action.VIEW -d ${shellQuote(args.url)}`,
            exec.signal,
            15000,
          )
          if (opened.code === 0) return `opened ${args.url}\n${opened.output}`
          const result = await act({ op: 'openUrl', url: args.url })
          return `${result.message}`
        }
        case 'clipboard': {
          const result = await act({ op: 'clipboard', text: args.text ?? '' })
          return `${result.message}`
        }
        case 'wait': {
          const result = await act({
            op: 'wait',
            ...(args.text === undefined ? {} : { text: args.text }),
            ...(args.package === undefined ? {} : { package: args.package }),
            timeoutMs: number(args.timeoutMs, 8000),
          }, exec.signal, 65000)
          return `${result.message} seq=${result.seq ?? ''}`
        }
        case 'find': {
          const result = await act({
            op: 'find',
            ...(args.text === undefined ? {} : { text: args.text }),
            ...(args.desc === undefined ? {} : { desc: args.desc }),
            ...(args.viewId === undefined ? {} : { viewId: args.viewId }),
            ...(args.click === undefined ? {} : { click: args.click }),
          })
          return JSON.stringify(result, null, 2)
        }
        default:
          throw new Error(`unsupported android_act op: ${op}`)
      }
    },
  })

  ctx.tools.register({
    name: 'android_apps',
    description: 'List launchable Android applications with package names and labels.',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => text(value),
    },
    async execute(_args, exec) {
      const result = await bridge('/apps', { signal: exec.signal })
      const lines = result.apps.map(app => `${app.package}\t${app.label}${app.system ? '\t(system)' : ''}`)
      return `launchable apps (${lines.length}):\n${lines.join('\n')}`
    },
  })

  ctx.tools.register({
    name: 'android_screenshot',
    description:
      'Capture the current Android screen through the accessibility service. Returns a saved JPEG path and, when the harness attachment store is available, attaches the image to the tool result. Use maxWidth (default 720) to bound image tokens.',
    parameters: {
      type: 'object',
      properties: {
        maxWidth: { type: 'number', description: 'Maximum image width, default 720.' },
        quality: { type: 'number', description: 'JPEG quality 20-100, default 80.' },
      },
      additionalProperties: false,
    },
    output: {
      schema: {
        type: 'object',
        properties: {
          path: { type: 'string' },
          mimeType: { type: 'string' },
          width: { type: 'number' },
          height: { type: 'number' },
          screenWidth: { type: 'number' },
          screenHeight: { type: 'number' },
          attachment: { type: 'object', additionalProperties: true },
        },
        required: ['path', 'mimeType', 'width', 'height'],
        additionalProperties: true,
      },
      render: (_args, value) => {
        const content = [
          {
            type: 'text',
            text: `screenshot ${value.width}x${value.height} saved to ${value.path} (screen ${value.screenWidth}x${value.screenHeight})`,
          },
        ]
        if (value.attachment) content.push({ type: 'image', attachment: value.attachment })
        return content
      },
    },
    async execute(args, exec) {
      const maxWidth = Math.max(1, Math.min(2160, number(args.maxWidth, 720)))
      const quality = Math.max(20, Math.min(100, number(args.quality, 80)))
      const shot = await bridge(`/screenshot?maxWidth=${maxWidth}&quality=${quality}`, {
        signal: exec.signal,
      })
      const fs = await import('node:fs/promises')
      const path = await import('node:path')
      const os = await import('node:os')
      const dir = path.join(os.homedir(), 'dsh-screenshots')
      await fs.mkdir(dir, { recursive: true })
      const file = path.join(dir, `shot-${Date.now()}.jpg`)
      await fs.writeFile(file, Buffer.from(shot.data, 'base64'))
      const value = {
        path: file,
        mimeType: shot.mimeType,
        width: shot.width,
        height: shot.height,
        screenWidth: shot.screenWidth,
        screenHeight: shot.screenHeight,
      }
      try {
        const attachments = ctx.get('attachments')
        if (!attachments) return value
        const mod = await import(ATTACHMENT_ENTRY)
        const refs = await mod.admitEncodedImages(attachments, [
          { data: shot.data, mediaType: shot.mimeType, name: path.basename(file) },
        ])
        if (refs[0]) value.attachment = refs[0]
      } catch (error) {
        ctx.logger?.warn?.(`android_screenshot attachment admission failed: ${error.message}`)
      }
      return value
    },
  })

  if (!NO_ROOT) ctx.tools.register({
    name: 'android_shell',
    description:
      'Run an arbitrary root shell command on the Android device through Termux su. Use this for capabilities the accessibility bridge does not expose: input keyevent, screencap, am/pm/settings/svc, dumpsys, file access. Requires the Termux app to hold Magisk root. Returns exit code, stdout and stderr.',
    parameters: {
      type: 'object',
      properties: {
        command: { type: 'string', description: 'Shell command to run as root.' },
        timeoutMs: { type: 'number', description: 'Timeout, default 30000.' },
      },
      required: ['command'],
      additionalProperties: false,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => text(value),
    },
    async execute(args, exec) {
      const result = await runShell(
        String(args.command),
        exec.signal,
        number(args.timeoutMs, 30000),
      )
      return `exit=${result.code}\n${result.output}`
    },
  })

  ctx.tools.register({
    name: 'android_health',
    description:
      'Check whether the DSH Android accessibility bridge service is running and report the foreground package and screen geometry.',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => text(value),
    },
    async execute(_args, exec) {
      return JSON.stringify(await bridge('/health', { signal: exec.signal }), null, 2)
    },
  })

  console.log(
    NO_ROOT
      ? '[android-cua] registered android_state, android_act, android_apps, android_screenshot, android_health (no-root)'
      : '[android-cua] registered android_state, android_act, android_apps, android_screenshot, android_shell, android_health',
  )
}
