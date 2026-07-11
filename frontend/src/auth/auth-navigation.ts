export function safeAuthReturnTo(value: string | null) {
  if (value?.startsWith('/invite/') && !value.startsWith('//')) {
    return value
  }

  return '/app'
}

export function invitationTokenFromReturnTo(returnTo: string) {
  const prefix = '/invite/'
  return returnTo.startsWith(prefix) ? returnTo.slice(prefix.length) : null
}
