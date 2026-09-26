'use strict'

const fs = require('fs')
const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const output = process.argv[4] || 'assigned-identity.json'
const fakeHost = process.argv[5] || undefined
const claimedName = 'ClientClaim'

let successSeen = false
let joined = false
let finished = false

function fail (message) {
  if (finished) return
  finished = true
  console.error(message)
  process.exitCode = 1
  try { client.end('integration failure') } catch {}
  setTimeout(() => process.exit(1), 250).unref()
}

const client = mc.createClient({
  host,
  port,
  username: claimedName,
  version: '1.21.4',
  auth: 'offline',
  fakeHost,
  disableChatSigning: true
})

client.on('packet', (data, meta) => {
  if (meta.name !== 'success') return
  successSeen = true
  const assigned = {
    claimed_name: claimedName,
    assigned_name: data.username,
    assigned_uuid: data.uuid
  }
  if (data.username === claimedName) {
    fail('proxy did not replace the client-supplied guest username')
    return
  }
  if (!/^Guest_[0-9a-fA-F]{10}$/.test(data.username)) {
    fail(`unexpected assigned guest name: ${data.username}`)
    return
  }
  fs.writeFileSync(output, JSON.stringify(assigned, null, 2) + '\n')
  console.log('proxy_identity=PASS')
  console.log(`assigned_name=${data.username}`)
  console.log(`assigned_uuid=${data.uuid}`)
})

client.on('playerJoin', () => {
  joined = true
  console.log('minecraft_play_state=PASS')
  setTimeout(() => {
    if (finished) return
    finished = true
    client.end('integration complete')
  }, 1500)
})

client.on('error', err => fail(`client error: ${err.stack || err}`))
client.on('disconnect', packet => {
  if (!finished && !joined) fail(`disconnected before play state: ${JSON.stringify(packet)}`)
})
client.on('end', () => {
  if (finished && successSeen && joined) {
    process.exit(0)
  }
  if (!finished) fail('connection ended before integration assertions completed')
})

setTimeout(() => fail('timed out waiting for backend play state'), 45000).unref()
