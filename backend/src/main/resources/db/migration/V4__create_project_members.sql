create table project_members (
                             id uuid primary key default gen_random_uuid(),
                             workspace_id uuid not null,
                             project_id uuid not null,
                             user_id uuid not null,
                             role varchar(30) not null,
                             status varchar(30) not null default 'ACTIVE',
                             joined_at timestamptz not null default now(),
                             created_at timestamptz not null default now(),
                             updated_at timestamptz not null default now(),

                             constraint fk_project_members_workspace
                                 foreign key (workspace_id)
                                     references workspaces (id)
                                     on delete cascade,

                             constraint fk_project_members_project
                                 foreign key (project_id)
                                     references projects (id)
                                     on delete cascade,

                             constraint fk_project_members_user
                                 foreign key (user_id)
                                     references users (id),

                             constraint uk_project_members_project_user
                                 unique (project_id, user_id),

                             constraint ck_project_members_role
                                 check (role in ('OWNER', 'MEMBER')),

                             constraint ck_project_members_status
                                 check (status in ('ACTIVE', 'DISABLED'))
);

insert into project_members (
    workspace_id,
    project_id,
    user_id,
    role,
    status,
    joined_at,
    created_at,
    updated_at
)
select
    workspace_id,
    id,
    created_by_user_id,
    'OWNER',
    'ACTIVE',
    created_at,
    created_at,
    updated_at
from projects;

create index idx_project_members_workspace_user_status
    on project_members (workspace_id, user_id, status);

create index idx_project_members_project_status
    on project_members (project_id, status);
