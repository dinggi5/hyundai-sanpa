import { get, put } from '@vercel/blob'
import { json, params, readState, sniff, writeState } from './_lib.js'

const MAX = 4 * 1024 * 1024 // 함수 본문 한도(4.5MB) 아래. 16kHz 모노 WAV 로 2분쯤.
const MIN = 1024 // 머리만 있는 빈 녹음은 받지 않는다.

// PUT /api/voice?pair=…&who=parent&q=<회차 id>&text=<질문>   차에서 담은 한마디 — 새 회차를 연다
// PUT /api/voice?pair=…&who=child&q=<회차 id>               가족의 답 — 지금 열려 있는 회차에만
export async function PUT(request) {
  const { pair, who, q, url } = params(request)
  if (!pair || !who || !q) return json({ error: 'params' }, 400)

  const body = await request.arrayBuffer()
  if (body.byteLength < MIN || body.byteLength > MAX) return json({ error: 'size' }, 413)
  const type = sniff(body)
  if (!type) return json({ error: 'type' }, 415)

  const { state: before } = await readState(pair)
  const text = (url.searchParams.get('text') ?? '').slice(0, 200)
  if (who === 'parent') {
    if (!text) return json({ error: 'text' }, 400)
    // 늦게 닿은 지난 회차의 재전송이 지금 회차를 되돌리지 못하게 한다.
    if (before.question && before.question.id !== q && madeAt(q) < madeAt(before.question.id)) return json({ error: 'stale' }, 409)
  } else if (before.question?.id !== q || !before.parent) {
    return json({ error: 'stale' }, 409) // 그사이 회차가 바뀌었다
  }

  // 올릴 때마다 다른 경로에 둔다. 같은 경로에 덮어쓰면, 동시에 올린 두 답 가운데 파일은 B·상태는 A 가 되어
  // 성공한 A 의 녹음이 사라졌다(코덱스 R1 P0, 개발 5). 상태가 가리키는 파일(v)만 듣는다.
  const v = Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
  await put(`${pair}/${q}/${who}-${v}`, body, {
    access: 'private',
    contentType: type,
    addRandomSuffix: false,
    allowOverwrite: false,
  })

  // 소리를 올리는 데 시간이 걸린다. 상태는 올린 뒤에 다시 읽고, 읽은 그대로일 때만 쓴다.
  // 그사이 다른 요청이 상태를 바꿨으면 다시 읽어 다시 판단한다.
  for (let i = 0; i < 5; i++) {
    const { state, etag } = await readState(pair)
    let next
    if (who === 'child') {
      if (state.question?.id !== q) return json({ error: 'stale' }, 409) // 그사이 차가 새 회차를 열었다
      next = { ...state }
    } else if (state.question?.id !== q) {
      if (state.question && madeAt(q) < madeAt(state.question.id)) return json({ error: 'stale' }, 409)
      next = { question: { id: q, text }, parent: null, child: null } // 새 회차
    } else next = { ...state }
    next[who] = { at: Date.now(), type, v }
    if (await writeState(pair, next, etag)) return json(next)
  }
  return json({ error: 'busy' }, 503) // 차는 다시 보낸다(503 은 쥐고 다시 보내는 쪽)
}

/** 회차 id 끝의 만든 시각(36진수). 차가 「물음id-시각」으로 만든다. 못 읽으면 0. */
function madeAt(id) {
  const t = parseInt(id.slice(id.lastIndexOf('-') + 1), 36)
  return Number.isFinite(t) ? t : 0
}

// GET /api/voice?pair=…&who=…&q=<회차 id>   그 회차에 달린 그 사람의 소리
export async function GET(request) {
  const { pair, who, q } = params(request)
  if (!pair || !who || !q) return json({ error: 'params' }, 400)
  // 듣는 쪽이 보고 있는 판(v)을 먼저 쓴다 — 그사이 새 회차가 열려도 웹이 보던 지난 회차를 들을 수 있게(코덱스 R2).
  // 안 주면 지금 상태가 가리키는 판, 그것도 없으면(개발 4 이전 배포의 회차) 옛 고정 경로.
  const asked = new URL(request.url).searchParams.get('v') ?? ''
  let v = /^[a-z0-9]{1,30}$/.test(asked) ? asked : null
  if (!v) {
    const { state } = await readState(pair)
    v = state.question?.id === q ? state[who]?.v : null
  }
  const r = await get(v ? `${pair}/${q}/${who}-${v}` : `${pair}/${q}/${who}`, { access: 'private', useCache: false })
  if (!r || r.statusCode !== 200) return json({ error: 'none' }, 404)
  return new Response(r.stream, {
    headers: { 'content-type': r.blob?.contentType ?? 'application/octet-stream', 'cache-control': 'no-store' },
  })
}
