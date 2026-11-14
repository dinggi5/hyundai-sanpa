import { json, params, readState } from './_lib.js'

// GET /api/state?pair=… → { question: {id, text} | null, parent: {at, type} | null, child: {at, type} | null }
export async function GET(request) {
  const { pair } = params(request)
  if (!pair) return json({ error: 'pair' }, 400)
  return json((await readState(pair)).state)
}
