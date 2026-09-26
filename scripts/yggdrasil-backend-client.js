'use strict'

const fs = require('fs')
const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const authServer = process.argv[4]
const sessionServer = process.argv[5]
const username = process.argv[6]
const password = process.argv[7]
const expectedName = process.argv[8]
const expectedUuid = process.argv[9].toLowerCase().replace(/-/g, '')
const output = process.argv[10] || 'live-backend-identity.json'
const label = process.argv[11] || 'yggdrasil'

let loginSuccess = false
let joined = false
let finished = false

function fail (message) {
  if (finished) return
  finished = true
  console.error(message)
  try { client.end('VIP backend qualification failure') } catch {}
  setTimeout(() => process.exit(1), 200).unref()
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

client.on('session', session => {
  console.log(`${label}_launcher_auth=PASS`)
  console.log(`${label}_selected_profile=${session.selectedProfile?.name || ''}`)
})

client.on('packet', (data, meta) => {
  if (meta.name !== 'success' || finished) return
  const assignedUuid = String(data.uuid).toLowerCase().replace(/-/g, '')
  if (data.username !== expectedName) {
    fail(`login name mismatch: expected ${expectedName}, got ${data.username}`)
    return
  }
  if (assignedUuid !== expectedUuid) {
    fail(`login UUID mismatch: expected ${expectedUuid}, got ${data.uuid}`)
    return
  }
  loginSuccess = true
  fs.writeFileSync(output, JSON.stringify({
    assigned_name: data.username,
    assigned_uuid: data.uuid
  }, null, 2) + '\n')
  console.log(`${label}_velocity_identity=PASS`)
})

client.on('playerJoin', () => {
  joined = true
  console.log(`${label}_backend_play_state=PASS`)
  setTimeout(() => {
    if (finished) return
    finished = true
    client.end('VIP backend qualification complete')
  }, 2000)
})

client.on('error', err => fail(`client error: ${err.stack || err}`))
client.on('disconnect', packet => {
  if (!finished && !joined) fail(`disconnected before backend play state: ${JSON.stringify(packet)}`)
})
client.on('end', () => {
  if (finished && loginSuccess && joined) process.exit(0)
  else if (!finished) fail('connection ended before backend qualification completed')
})

setTimeout(() => fail('timed out waiting for backend play state'), 60000).unref()
