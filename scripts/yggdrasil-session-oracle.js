'use strict'

const http = require('http')
const { URL } = require('url')

const port = Number(process.argv[2] || 18080)
const expectedName = process.env.VIP_ORACLE_NAME || 'OracleUser'
const expectedUuid = (process.env.VIP_ORACLE_UUID || 'a1b2c3d4-e5f6-4789-8abc-def012345678').toLowerCase()
const expectedProfile = expectedUuid.replace(/-/g, '')
const expectedToken = process.env.VIP_ORACLE_TOKEN || 'vip-oracle-token'
const maxBody = 16 * 1024

let joined = null
let hasJoinedCount = 0

function reply (res, status, body, contentType = 'application/json') {
  res.statusCode = status
  if (body === undefined || body === null || body === '') {
    res.end()
    return
  }
  res.setHeader('Content-Type', contentType)
  res.end(typeof body === 'string' ? body : JSON.stringify(body))
}

function readJson (req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', chunk => {
      size += chunk.length
      if (size > maxBody) {
        reject(new Error('body too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch (error) {
        reject(error)
      }
    })
    req.on('error', reject)
  })
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`)

  if (req.method === 'GET' && url.pathname === '/health') {
    reply(res, 200, {
      ok: true,
      joined: joined !== null,
      hasJoinedCount
    })
    return
  }

  if (req.method === 'POST' && url.pathname === '/session/minecraft/join') {
    try {
      const body = await readJson(req)
      if (body.accessToken !== expectedToken ||
          String(body.selectedProfile || '').toLowerCase() !== expectedProfile ||
          typeof body.serverId !== 'string' ||
          body.serverId.length === 0) {
        console.log('join=DENIED')
        reply(res, 403, {
          error: 'ForbiddenOperationException',
          errorMessage: 'invalid oracle join request'
        })
        return
      }
      joined = {
        serverId: body.serverId,
        at: Date.now()
      }
      console.log(`join=PASS server_id=${body.serverId}`)
      reply(res, 204)
    } catch (error) {
      console.error(`join=ERROR detail=${error.message}`)
      reply(res, 400, {
        error: 'BadRequestException',
        errorMessage: 'invalid request'
      })
    }
    return
  }

  if (req.method === 'GET' && url.pathname === '/session/minecraft/hasJoined') {
    const username = url.searchParams.get('username')
    const serverId = url.searchParams.get('serverId')
    if (!joined ||
        username !== expectedName ||
        serverId !== joined.serverId) {
      console.log(`has_joined=MISS username=${username || ''} server_id=${serverId || ''}`)
      reply(res, 204)
      return
    }

    hasJoinedCount++
    console.log(`has_joined=PASS username=${username} server_id=${serverId}`)
    reply(res, 200, {
      id: expectedProfile,
      name: expectedName,
      properties: [
        {
          name: 'vip-oracle',
          value: 'verified-online-session'
        }
      ]
    })
    return
  }

  reply(res, 404, {
    error: 'NotFound',
    errorMessage: 'unknown oracle endpoint'
  })
})

server.listen(port, '127.0.0.1', () => {
  console.log(`oracle_ready=PASS port=${port}`)
})

function shutdown () {
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(1), 2000).unref()
}

process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
