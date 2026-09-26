'use strict'

const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const yggdrasilRoot = process.argv[4]
const username = process.argv[5]
const password = process.argv[6]
const expectedName = process.argv[7]
const expectedUuid = process.argv[8].toLowerCase().replace(/-/g, '')

if (!yggdrasilRoot || !username || !password || !expectedName || !expectedUuid) {
  console.error('usage: drasl-online-client.js host port yggdrasilRoot username password expectedName expectedUuid')
  process.exit(2)
}

let finished = false

function fail (message) {
  if (finished) return
  finished = true
  console.error(message)
  try { client.end('VIP Drasl live-service qualification failure') } catch {}
  setTimeout(() => process.exit(1), 100).unref()
}

const client = mc.createClient({
  host,
  port,
  username,
  password,
  version: '1.21.4',
  auth: 'mojang',
  authServer: yggdrasilRoot,
  sessionServer: yggdrasilRoot,
  profilesFolder: false,
  disableChatSigning: true
})

client.on('session', session => {
  console.log('drasl_launcher_auth=PASS')
  console.log(`drasl_selected_profile=${session.selectedProfile?.name || ''}`)
})

client.on('packet', (data, meta) => {
  if (meta.name !== 'success' || finished) return

  const assignedName = data.username
  const assignedUuid = String(data.uuid).toLowerCase().replace(/-/g, '')

  if (assignedName !== expectedName) {
    fail(`online-session name mismatch: expected ${expectedName}, got ${assignedName}`)
    return
  }
  if (assignedUuid !== expectedUuid) {
    fail(`online-session UUID mismatch: expected ${expectedUuid}, got ${data.uuid}`)
    return
  }

  finished = true
  console.log('drasl_velocity_login=PASS')
  console.log(`assigned_uuid=${data.uuid}`)
  console.log(`assigned_name=${assignedName}`)
  client.end('Drasl live-service qualification complete')
  setTimeout(() => process.exit(0), 100).unref()
})

client.on('error', error => fail(`client error: ${error.stack || error}`))
client.on('disconnect', packet => {
  if (!finished) fail(`disconnected before online-session success: ${JSON.stringify(packet)}`)
})
client.on('end', () => {
  if (!finished) fail('connection ended before online-session success')
})

setTimeout(() => fail('timed out waiting for Drasl online-session login success'), 45000).unref()
