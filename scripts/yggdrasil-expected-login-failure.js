'use strict'

const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const authServer = process.argv[4]
const sessionServer = process.argv[5]
const username = process.argv[6]
const password = process.argv[7]
const label = process.argv[8] || 'provider'

let authenticated = false
let resolved = false

function finishPass(reason) {
  if (resolved) return
  if (!authenticated) {
    resolved = true
    console.error(`login failed before real provider authentication: ${reason}`)
    process.exit(1)
  }
  resolved = true
  console.log(`${label}_provider_auth=PASS`)
  console.log(`${label}_velocity_login_rejected=PASS`)
  console.log(`rejection=${String(reason).replace(/\s+/g, ' ')}`)
  process.exit(0)
}

function fail(message) {
  if (resolved) return
  resolved = true
  console.error(message)
  try { client.end() } catch {}
  process.exit(1)
}

const client = mc.createClient({
  host,
  port,
  username,
  password,
  version: '1.21.4',
  auth: 'mojang',
  authServer,
  sessionServer,
  profilesFolder: false,
  disableChatSigning: true
})

client.on('session', () => {
  authenticated = true
})

client.on('packet', (_data, meta) => {
  if (meta.name === 'success') {
    fail('unexpected Velocity login success while session authority is unavailable')
  }
})

client.on('disconnect', packet => finishPass(JSON.stringify(packet)))
client.on('error', error => finishPass(error.message || error))
client.on('end', () => finishPass('connection ended without Login Success'))

setTimeout(() => fail('timed out waiting for fail-closed login result'), 30000).unref()
