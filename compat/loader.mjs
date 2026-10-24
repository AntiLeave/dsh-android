const FLOCK = new URL('./flock.mjs', import.meta.url).href
const REQUIRE_BUILTIN = new URL('./require-builtin.mjs', import.meta.url).href

export async function resolve(specifier, context, nextResolve) {
  if (specifier === '@deepseek-ai/node-addon-system/flock') {
    return { url: FLOCK, shortCircuit: true }
  }
  if (specifier === 'node-addon-require-builtin') {
    return { url: REQUIRE_BUILTIN, shortCircuit: true }
  }
  return nextResolve(specifier, context)
}
