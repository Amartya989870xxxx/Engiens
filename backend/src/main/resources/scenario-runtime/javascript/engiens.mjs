// Engiens check API. Owned by Engiens, never generated: hidden checks call check(name, fn).
import { isDeepStrictEqual } from 'node:util'

export const checks = []

export function check(name, fn) {
  checks.push({ name, fn })
}

export class CheckFailure extends Error {
  constructor(message) {
    super(message ?? 'A check assertion failed')
    this.name = 'CheckFailure'
  }
}

export function assert(condition, message) {
  if (!condition) throw new CheckFailure(message)
}

export function assertEqual(actual, expected, message) {
  if (!isDeepStrictEqual(actual, expected)) {
    throw new CheckFailure(`${message ?? 'Values differ'} (expected ${JSON.stringify(expected)} but got ${JSON.stringify(actual)})`)
  }
}

export async function assertRejects(fn, message) {
  try {
    await fn()
  } catch {
    return
  }
  throw new CheckFailure(message ?? 'Expected an error')
}
