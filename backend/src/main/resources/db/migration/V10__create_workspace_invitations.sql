create table workspace_invitations (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null,
    invited_by_user_id uuid not null,
    accepted_by_user_id uuid,
    email varchar(255) not null,
    role varchar(30) not null,
    token_hash varchar(255) not null unique,
    status varchar(30) not null default 'PENDING',
    expires_at timestamptz not null,
    accepted_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint fk_workspace_invitations_workspace
        foreign key (workspace_id)
            references workspaces (id)
            on delete cascade,

    constraint fk_workspace_invitations_invited_by
        foreign key (invited_by_user_id)
            references users (id),

    constraint fk_workspace_invitations_accepted_by
        foreign key (accepted_by_user_id)
            references users (id),

    constraint ck_workspace_invitations_role
        check (role in ('ADMIN', 'MEMBER', 'GUEST')),

    constraint ck_workspace_invitations_status
        check (status in ('PENDING', 'ACCEPTED', 'REVOKED'))
);

create unique index uk_workspace_invitations_pending_email
    on workspace_invitations (workspace_id, email)
    where status = 'PENDING';

create index idx_workspace_invitations_workspace_created_at
    on workspace_invitations (workspace_id, created_at desc);

create index idx_workspace_invitations_email_status
    on workspace_invitations (email, status);

create index idx_workspace_invitations_expires_at
    on workspace_invitations (expires_at);
