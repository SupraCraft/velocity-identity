'use strict'

const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const sessionServer = process.argv[4] || 'http://127.0.0.1:18080'
const expectedName = process.env.VIP_ORACLE_NAME || 'OracleUser'
const expectedUuid = (process.env.VIP_ORACLE_UUID || 'a1b2c3d4-e5f6-4789-8abc-def012345678').toLowerCase()
const compactUuid = expectedUuid.replace(/-/g, '')
const accessToken = process.env.VIP_ORACLE_TOKEN || 'vip-oracle-token'

let finished = false

function fail (message) {
  if (finished) return
  finished = true
  console.error(message)
  try { client.end('oracle integration failure') } catch {}
  setTimeout(() => process.exit(1), 100).unref()
}

const selectedProfile = {
  id: compactUuid,
  name: expectedName
}

const client = mc.createClient({
  host,
  port,
  username: expectedName,
  version: '1.21.4',
  auth: 'mojang',
  profilesFolder: false,
  skipValidation: true,
  sessionServer,
  session: {
    accessToken,
    clientToken: 'vip-oracle-client',
    selectedProfile,
    availableProfiles: [selectedProfile]
  },
  disableChatSigning: true
})

client.on('packet', (data, meta) => {
  if (meta.name !== 'success' || finished) return

  const assignedName = data.username
  const assignedUuid = String(data.uuid).toLowerCase()
  const normalizedAssigned = assignedUuid.replace(/-/g, '')

  if (assignedName !== expectedName) {
    fail(`online-session name mismatch: expected ${expectedName}, got ${assignedName}`)
    return
  }
  if (normalizedAssigned !== compactUuid) {
    fail(`online-session UUID mismatch: expected ${expectedUuid}, got ${assignedUuid}`)
    return
  }

  finished = true
  console.log('online_session_login=PASS')
  console.log(`assigned_uuid=${assignedUuid}`)
  console.log(`assigned_name=${assignedName}`)
  client.end('oracle integration complete')
  setTimeout(() => process.exit(0), 100).unref()
})

client.on('error', error => fail(`client error: ${error.stack || error}`))
client.on('disconnect', packet => {
  if (!finished) fail(`disconnected before online-session success: ${JSON.stringify(packet)}`)
})
client.on('end', () => {
  if (!finished) fail('connection ended before online-session success')
})

setTimeout(() => fail('timed out waiting for online-session login success'), 30000).unref()
