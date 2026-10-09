-- Tokens a user creates in settings and pastes into an AI app, which presents them
-- to the MCP endpoint and nowhere else. Each belongs to one workspace membership, so
-- it reads only that workspace and stops working when the membership is disabled.
-- Only a hash of the token is kept; the token itself is shown once, on creation.
create table personal_access_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    workspace_membership_id uuid not null,
    name varchar(100) not null,
    token_hash varchar(255) not null unique,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    last_used_at timestamptz,
    revoked_at timestamptz,

    constraint fk_personal_access_tokens_user_workspace_membership
        foreign key (user_id, workspace_membership_id)
            references workspace_memberships (user_id, id)
            on delete cascade,

    constraint ck_personal_access_tokens_expiry
        check (expires_at > created_at)
);

create index idx_personal_access_tokens_user_workspace_membership
    on personal_access_tokens (user_id, workspace_membership_id);
