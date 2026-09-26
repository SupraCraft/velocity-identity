'use strict'

const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25577)
const authServer = process.argv[4]
const sessionServer = process.argv[5]
const username = process.argv[6]
const password = process.argv[7]
const label = process.argv[8] || 'yggdrasil'

let resolved = false
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
  if (resolved) return
  resolved = true
  console.error('unexpected launcher authentication success')
  client.end()
  process.exit(1)
})
client.on('error', error => {
  if (resolved) return
  resolved = true
  console.log(`${label}_bad_password_rejected=PASS`)
  console.log(`rejection=${String(error.message || error).replace(/\s+/g, ' ')}`)
  process.exit(0)
})
client.on('end', () => {
  if (!resolved) {
    resolved = true
    console.log(`${label}_bad_password_rejected=PASS`)
    process.exit(0)
  }
})
setTimeout(() => {
  if (!resolved) {
    resolved = true
    console.error('bad-password client did not terminate')
    process.exit(1)
  }
}, 20000).unref()
