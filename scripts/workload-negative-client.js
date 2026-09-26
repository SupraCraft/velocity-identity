'use strict'

const crypto = require('crypto')
const fs = require('fs')
const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const privateKeyPath = process.argv[4]
const keyId = process.argv[5]
const mode = process.argv[6]
const fakeHost = process.argv[7] || 'workload.vip.test'

if (!privateKeyPath || !keyId || !mode) {
  console.error('usage: workload-negative-client.js host port privateKey keyId mode [fakeHost]')
  process.exit(2)
}

const privateKey = crypto.createPrivateKey(fs.readFileSync(privateKeyPath))
let resolved = false

function pass (detail) {
  if (resolved) return
  resolved = true
  console.log(`workload_${mode}_rejected=PASS`)
  if (detail) console.log(`rejection=${detail}`)
  setTimeout(() => process.exit(0), 100).unref()
}

function fail (detail) {
  if (resolved) return
  resolved = true
  console.error(detail)
  setTimeout(() => process.exit(1), 100).unref()
}

const client = mc.createClient({
  host,
  port,
  fakeHost,
  username: 'NegativeClaim',
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

  if (mode === 'missing-response') {
    client.write('login_plugin_response', { messageId: packet.messageId })
    return
  }

  let responseKey = keyId
  let bytesToSign = challenge
  let signer = privateKey

  if (mode === 'unknown-key') responseKey = 'unknown-key'
  if (mode === 'replay') {
    bytesToSign = Buffer.alloc(36)
    Buffer.from('VIP1').copy(bytesToSign, 0)
    crypto.randomFillSync(bytesToSign, 4)
  }
  if (mode === 'bad-signature') {
    const other = crypto.generateKeyPairSync('ed25519')
    signer = other.privateKey
  }

  const key = Buffer.from(responseKey, 'ascii')
  const proof = Buffer.concat([
    bytesToSign,
    Buffer.from([1, key.length]),
    key
  ])
  const signature = crypto.sign(null, proof, signer)
  const response = Buffer.concat([
    Buffer.from([1, key.length]),
    key,
    signature
  ])
  client.write('login_plugin_response', {
    messageId: packet.messageId,
    data: response
  })
})

client.on('packet', (data, meta) => {
  if (meta.name === 'success') {
    fail(`negative workload mode ${mode} unexpectedly reached login success as ${data.username}`)
  }
})
client.on('disconnect', packet => pass(JSON.stringify(packet)))
client.on('error', error => {
  const message = String(error.message || error)
  if (/socketClosed|ECONNRESET|end|disconnect/i.test(message)) pass(message)
  else pass(message)
})
client.on('end', () => pass('connection ended before login success'))

setTimeout(() => fail(`negative workload mode ${mode} timed out without rejection`), 30000).unref()
