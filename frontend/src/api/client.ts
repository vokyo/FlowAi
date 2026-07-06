type AccessTokenProvider = () => string | null
type UnauthorizedHandler = () => void
type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

type ApiRequestOptions = Omit<RequestInit, 'body' | 'headers' | 'method'> & {
  method?: HttpMethod
  body?: unknown
  headers?: HeadersInit
  auth?: boolean
}

type ApiRequestWithoutMethod = Omit<ApiRequestOptions, 'method'>
type ApiRequestWithoutBody = Omit<ApiRequestOptions, 'method' | 'body'>

let accessTokenProvider: AccessTokenProvider | undefined
let unauthorizedHandler: UnauthorizedHandler | undefined

export class ApiError extends Error {
  readonly status: number
  readonly payload: unknown

  constructor(message: string, status: number, payload: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
  }
}

export function setAccessTokenProvider(provider: AccessTokenProvider) {
  accessTokenProvider = provider
}

export function clearAccessTokenProvider() {
  accessTokenProvider = undefined
}

export function setUnauthorizedHandler(handler: UnauthorizedHandler) {
  unauthorizedHandler = handler
}

export function clearUnauthorizedHandler() {
  unauthorizedHandler = undefined
}

export async function apiRequest<T>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  const { method = 'GET', body, headers, auth = true, ...requestInit } = options
  const requestHeaders = createHeaders(headers, body, auth)

  const response = await fetch(toApiUrl(path), {
    ...requestInit,
    method,
    headers: requestHeaders,
    body: serializeBody(body),
  })

  const payload = await readPayload(response)

  if (response.status === 401) {
    unauthorizedHandler?.()
  }

  if (!response.ok) {
    throw new ApiError(
      getErrorMessage(payload, response.statusText),
      response.status,
      payload,
    )
  }

  return payload as T
}

export const api = {
  get<T>(path: string, options: ApiRequestWithoutBody = {}) {
    return apiRequest<T>(path, { ...options, method: 'GET' })
  },
  post<T>(path: string, body?: unknown, options: ApiRequestWithoutMethod = {}) {
    return apiRequest<T>(path, { ...options, method: 'POST', body })
  },
  put<T>(path: string, body?: unknown, options: ApiRequestWithoutMethod = {}) {
    return apiRequest<T>(path, { ...options, method: 'PUT', body })
  },
  patch<T>(path: string, body?: unknown, options: ApiRequestWithoutMethod = {}) {
    return apiRequest<T>(path, { ...options, method: 'PATCH', body })
  },
  delete<T>(path: string, options: ApiRequestWithoutBody = {}) {
    return apiRequest<T>(path, { ...options, method: 'DELETE' })
  },
}

function createHeaders(
  headers: HeadersInit | undefined,
  body: unknown,
  auth: boolean,
) {
  const requestHeaders = new Headers(headers)

  if (body !== undefined && !isRawBody(body) && !requestHeaders.has('Content-Type')) {
    requestHeaders.set('Content-Type', 'application/json')
  }

  const accessToken = accessTokenProvider?.()
  if (auth && accessToken) {
    requestHeaders.set('Authorization', `Bearer ${accessToken}`)
  }

  return requestHeaders
}

function toApiUrl(path: string) {
  if (/^https?:\/\//.test(path)) {
    return path
  }

  const normalizedPath = path.startsWith('/') ? path : `/${path}`

  if (normalizedPath === '/api' || normalizedPath.startsWith('/api/')) {
    return normalizedPath
  }

  return `/api${normalizedPath}`
}

function serializeBody(body: unknown): BodyInit | undefined {
  if (body === undefined) {
    return undefined
  }

  if (isRawBody(body)) {
    return body
  }

  return JSON.stringify(body)
}

function isRawBody(body: unknown): body is BodyInit {
  return (
    typeof body === 'string' ||
    body instanceof FormData ||
    body instanceof Blob ||
    body instanceof ArrayBuffer ||
    body instanceof URLSearchParams
  )
}

async function readPayload(response: Response) {
  if (response.status === 204) {
    return null
  }

  const contentType = response.headers.get('Content-Type') ?? ''

  if (contentType.includes('application/json')) {
    return response.json()
  }

  const text = await response.text()
  return text.length > 0 ? text : null
}

function getErrorMessage(payload: unknown, fallback: string) {
  if (
    payload &&
    typeof payload === 'object' &&
    'message' in payload &&
    typeof payload.message === 'string'
  ) {
    return payload.message
  }

  return fallback || 'Request failed'
}
