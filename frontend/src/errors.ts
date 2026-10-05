import { ApiError } from './api'

/** The server's message for one form field, if the request failed validation on it. */
export function fieldError(error: unknown, field: string) {
  return error instanceof ApiError ? error.fieldErrors[field] : undefined
}
