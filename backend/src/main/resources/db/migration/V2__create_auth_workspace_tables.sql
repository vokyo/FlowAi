create extension if not exists pgcrypto;

create table users (
                       id uuid primary key default gen_random_uuid(),
                       email varchar(255) not null unique,
                       password_hash varchar(255) not null,
                       display_name varchar(120) not null,
                       avatar_url varchar(500),
                       created_at timestamptz not null default now(),
                       updated_at timestamptz not null default now()
);

create table workspaces (
                            id uuid primary key default gen_random_uuid(),
                            owner_user_id uuid not null,
                            name varchar(160) not null,
                            slug varchar(120) not null unique,
                            created_at timestamptz not null default now(),
                            updated_at timestamptz not null default now(),

                            constraint fk_workspaces_owner
                                foreign key (owner_user_id)
                                    references users (id)
);

create table workspace_memberships (
                                       id uuid primary key default gen_random_uuid(),
                                       workspace_id uuid not null,
                                       user_id uuid not null,
                                       role varchar(30) not null,
                                       status varchar(30) not null default 'ACTIVE',
                                       joined_at timestamptz not null default now(),
                                       created_at timestamptz not null default now(),
                                       updated_at timestamptz not null default now(),

                                       constraint fk_workspace_memberships_workspace
                                           foreign key (workspace_id)
                                               references workspaces (id)
                                               on delete cascade,

                                       constraint fk_workspace_memberships_user
                                           foreign key (user_id)
                                               references users (id)
                                               on delete cascade,

                                       constraint uk_workspace_memberships_workspace_user
                                           unique (workspace_id, user_id),

                                       constraint ck_workspace_memberships_role
                                           check (role in ('OWNER', 'ADMIN', 'MEMBER', 'GUEST')),

                                       constraint ck_workspace_memberships_status
                                           check (status in ('ACTIVE', 'INVITED', 'DISABLED'))
);

create table refresh_tokens (
                                id uuid primary key default gen_random_uuid(),
                                user_id uuid not null,
                                token_hash varchar(255) not null unique,
                                expires_at timestamptz not null,
                                revoked_at timestamptz,
                                created_at timestamptz not null default now(),

                                constraint fk_refresh_tokens_user
                                    foreign key (user_id)
                                        references users (id)
                                        on delete cascade
);

create index idx_workspace_memberships_user_id
    on workspace_memberships (user_id);

create index idx_workspace_memberships_workspace_id
    on workspace_memberships (workspace_id);

create index idx_refresh_tokens_user_id
    on refresh_tokens (user_id);

create index idx_refresh_tokens_expires_at
    on refresh_tokens (expires_at);