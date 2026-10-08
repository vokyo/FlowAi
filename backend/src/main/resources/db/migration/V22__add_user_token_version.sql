-- Raised whenever a user's sessions end: a logout, a password change, signing out
-- everywhere, or a replayed refresh token. Every access token carries the value it
-- was issued under, so the ones issued before stop working on their next request
-- instead of when they expire.
alter table users
    add column token_version integer not null default 0;

alter table users
    add constraint ck_users_token_version check (token_version >= 0);
