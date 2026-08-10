/**
 * Mirrors the @Size constraints on the auth request DTOs (LoginRequest,
 * RegisterRequest, RegisterWithInvitationRequest) so over-long input fails
 * inline instead of coming back as a generic 400.
 */
export const AUTH_FORM_LIMITS = {
  displayName: 120,
  email: 255,
  /** BCrypt only reads the first 72 characters, so the backend rejects anything longer. */
  password: 72,
  workspaceName: 160,
} as const
