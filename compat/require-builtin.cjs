'use strict'

function requireBuiltin(moduleId) {
  return require(moduleId)
}

function isAllowedInternalId(moduleId) {
  return typeof moduleId === 'string' && moduleId.startsWith('internal/')
}

function getBindingInfo() {
  return { backend: 'js', version: process.versions.node }
}

module.exports = {
  requireBuiltin,
  isAllowedInternalId,
  getBindingInfo,
  default: { requireBuiltin, isAllowedInternalId, getBindingInfo },
}
