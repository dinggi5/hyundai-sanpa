// 짝(pair) 하나 = 차 한 대 + 떨어져 사는 가족 한 사람. 짝 코드가 곧 열쇠다.
// 이름은 옛 설계를 따라 차 쪽이 parent, 가족 쪽이 child 다(차에 탄 사람을 부모로 못 박지 않는다 — 개발 4).
// 저장소는 비공개 Blob 이고, 소리는 이 서버를 거쳐서만 나간다.
// 상태의 question.id 는 「회차」 id 다 — 같은 질문을 다시 물어도 회차는 새로 생기고, 소리는 회차마다 따로 남는다.
import { BlobPreconditionFailedError, get, put } from '@vercel/blob'

const PAIR = /^[a-z0-9]{6,32}$/
const ROUND = /^[a-z0-9-]{1,60}$/
const WHO = new Set(['parent', 'child'])

export function params(request) {
  const u = new URL(request.url)
  const pair = u.searchParams.get('pair') ?? ''
  const who = u.searchParams.get('who') ?? ''
  const q = u.searchParams.get('q') ?? ''
  return {
    pair: PAIR.test(pair) ? pair : null,
    who: WHO.has(who) ? who : null,
    q: ROUND.test(q) ? q : null,
    url: u,
  }
}

export const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })

/** 본문 첫머리로 소리 형식을 가린다. 헤더는 믿지 않는다. 모르는 형식이면 null. */
export function sniff(buf) {
  const b = new Uint8Array(buf.slice(0, 12))
  const s = (from, to) => String.fromCharCode(...b.slice(from, to))
  if (s(0, 4) === 'RIFF' && s(8, 12) === 'WAVE') return 'audio/wav'
  if (s(4, 8) === 'ftyp') return 'audio/mp4'
  if (b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3) return 'audio/webm'
  return null
}

const EMPTY = { question: null, parent: null, child: null }

/** 짝의 지금 상태와 그 ETag. 없으면 빈 상태, etag 는 null. */
export async function readState(pair) {
  const r = await get(`${pair}/state.json`, { access: 'private', useCache: false })
  if (!r || r.statusCode !== 200) return { state: { ...EMPTY }, etag: null }
  return { state: { ...EMPTY, ...(await new Response(r.stream).json()) }, etag: r.blob?.etag || null }
}

/**
 * 읽은 뒤로 아무도 안 바꿨을 때만 쓴다(ETag 조건부 쓰기). 처음 만드는 상태는 덮어쓰기 금지로 쓴다.
 * 그사이 누가 바꿨으면 false — 다시 읽고 다시 판단한다. 읽고 쓰는 사이에 다른 요청이 끼어 새 회차를 지우던 것(코덱스 R1 P0, 개발 4).
 */
export async function writeState(pair, state, etag) {
  const opts = { access: 'private', contentType: 'application/json', addRandomSuffix: false }
  try {
    await put(`${pair}/state.json`, JSON.stringify(state), etag ? { ...opts, ifMatch: etag } : { ...opts, allowOverwrite: false })
    return true
  } catch (e) {
    if (e instanceof BlobPreconditionFailedError) return false
    if (!etag && /already exists/i.test(String(e?.message))) return false // 그사이 누가 먼저 만들었다
    throw e
  }
}
