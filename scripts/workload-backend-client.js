'use strict'

const crypto = require('crypto')
const fs = require('fs')
const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const privateKeyPath = process.argv[4]
const keyId = process.argv[5]
const expectedName = process.argv[6]
const expectedUuid = process.argv[7].toLowerCase().replace(/-/g, '')
const output = process.argv[8] || 'workload-identity.json'
const fakeHost = process.argv[9] || 'workload.vip.test'

if (!privateKeyPath || !keyId || !expectedName || !expectedUuid) {
  console.error('usage: workload-backend-client.js host port privateKey keyId expectedName expectedUuid [output] [fakeHost]')
  process.exit(2)
}

const privateKey = crypto.createPrivateKey(fs.readFileSync(privateKeyPath))
let loginSuccess = false
let joined = false
let finished = false
let challengeSeen = false

function fail (message) {
  if (finished) return
  finished = true
  console.error(message)
  try { client.end('VIP workload qualification failure') } catch {}
  setTimeout(() => process.exit(1), 200).unref()
}

const client = mc.createClient({
  host,
  port,
  fakeHost,
  username: 'UntrustedClaim',
  version: '1.21.4',
  auth: 'offline',
  disableChatSigning: true
})

client.removeAllListeners('login_plugin_request')
client.on('login_plugin_request', packet => {
  if (packet.channel !== 'supracraft:vip_auth') {
    client.write('login_plugin_response', { messageId: packet.messageId })
    return
  }
  const challenge = Buffer.from(packet.data)
  if (challenge.length !== 36 || challenge.subarray(0, 4).toString('ascii') !== 'VIP1') {
    fail('invalid VIP challenge')
    return
  }
  const key = Buffer.from(keyId, 'ascii')
  if (key.length < 1 || key.length > 64) {
    fail('invalid key id length')
    return
  }
  const proof = Buffer.concat([
    challenge,
    Buffer.from([1, key.length]),
    key
  ])
  const signature = crypto.sign(null, proof, privateKey)
  const response = Buffer.concat([
    Buffer.from([1, key.length]),
    key,
    signature
  ])
  challengeSeen = true
  client.write('login_plugin_response', {
    messageId: packet.messageId,
    data: response
  })
  console.log('workload_challenge_signed=PASS')
})

client.on('packet', (data, meta) => {
  if (meta.name !== 'success' || finished) return
  const assignedUuid = String(data.uuid).toLowerCase().replace(/-/g, '')
  if (data.username !== expectedName) {
    fail(`workload name mismatch: expected ${expectedName}, got ${data.username}`)
    return
  }
  if (assignedUuid !== expectedUuid) {
    fail(`workload UUID mismatch: expected ${expectedUuid}, got ${data.uuid}`)
    return
  }
  loginSuccess = true
  fs.writeFileSync(output, JSON.stringify({
    assigned_name: data.username,
    assigned_uuid: data.uuid,
    original_claim: 'UntrustedClaim'
  }, null, 2) + '\n')
  console.log('workload_velocity_identity=PASS')
})

client.on('playerJoin', () => {
  joined = true
  console.log('workload_backend_play_state=PASS')
  setTimeout(() => {
    if (finished) return
    finished = true
    client.end('VIP workload qualification complete')
  }, 1500)
})

client.on('error', err => fail(`client error: ${err.stack || err}`))
client.on('disconnect', packet => {
  if (!finished && !joined) fail(`disconnected before backend play state: ${JSON.stringify(packet)}`)
})
client.on('end', () => {
  if (finished && loginSuccess && joined && challengeSeen) process.exit(0)
  else if (!finished) fail('connection ended before workload qualification completed')
})

setTimeout(() => fail('timed out waiting for workload backend play state'), 60000).unref()
