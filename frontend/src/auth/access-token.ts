let accessToken: string | null = null

removeLegacyStoredTokens()

export function getAccessToken() {
  return accessToken
}

export function hasAccessToken() {
  return accessToken !== null
}

export function setAccessToken(token: string) {
  accessToken = token
}

export function clearAccessToken() {
  accessToken = null
}

function removeLegacyStoredTokens() {
  try {
    window.localStorage.removeItem('flowai.accessToken')
    window.localStorage.removeItem('flowai.refreshToken')
  } catch {
    // Authentication still works when storage is unavailable or blocked.
  }
}
