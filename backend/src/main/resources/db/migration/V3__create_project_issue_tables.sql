create table projects (
                          id uuid primary key default gen_random_uuid(),
                          workspace_id uuid not null,
                          created_by_user_id uuid not null,
                          name varchar(160) not null,
                          description text,
                          created_at timestamptz not null default now(),
                          updated_at timestamptz not null default now(),

                          constraint fk_projects_workspace
                              foreign key (workspace_id)
                                  references workspaces (id)
                                  on delete cascade,

                          constraint fk_projects_created_by_user
                              foreign key (created_by_user_id)
                                  references users (id)
);

create table issues (
                        id uuid primary key default gen_random_uuid(),
                        workspace_id uuid not null,
                        project_id uuid not null,
                        created_by_user_id uuid not null,
                        title varchar(240) not null,
                        description text,
                        status varchar(30) not null default 'TODO',
                        priority varchar(30),
                        created_at timestamptz not null default now(),
                        updated_at timestamptz not null default now(),

                        constraint fk_issues_workspace
                            foreign key (workspace_id)
                                references workspaces (id)
                                on delete cascade,

                        constraint fk_issues_project
                            foreign key (project_id)
                                references projects (id)
                                on delete cascade,

                        constraint fk_issues_created_by_user
                            foreign key (created_by_user_id)
                                references users (id),

                        constraint ck_issues_status
                            check (status in ('TODO', 'IN_PROGRESS', 'DONE', 'ARCHIVED')),

                        constraint ck_issues_priority
                            check (priority is null or priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);

create table issue_comments (
                                id uuid primary key default gen_random_uuid(),
                                workspace_id uuid not null,
                                project_id uuid not null,
                                issue_id uuid not null,
                                author_user_id uuid not null,
                                body text not null,
                                created_at timestamptz not null default now(),
                                updated_at timestamptz not null default now(),

                                constraint fk_issue_comments_workspace
                                    foreign key (workspace_id)
                                        references workspaces (id)
                                        on delete cascade,

                                constraint fk_issue_comments_project
                                    foreign key (project_id)
                                        references projects (id)
                                        on delete cascade,

                                constraint fk_issue_comments_issue
                                    foreign key (issue_id)
                                        references issues (id)
                                        on delete cascade,

                                constraint fk_issue_comments_author_user
                                    foreign key (author_user_id)
                                        references users (id)
);

create table activity_events (
                                 id uuid primary key default gen_random_uuid(),
                                 workspace_id uuid not null,
                                 project_id uuid,
                                 issue_id uuid,
                                 actor_user_id uuid not null,
                                 event_type varchar(60) not null,
                                 metadata jsonb not null default '{}'::jsonb,
                                 created_at timestamptz not null default now(),

                                 constraint fk_activity_events_workspace
                                     foreign key (workspace_id)
                                         references workspaces (id)
                                         on delete cascade,

                                 constraint fk_activity_events_project
                                     foreign key (project_id)
                                         references projects (id)
                                         on delete cascade,

                                 constraint fk_activity_events_issue
                                     foreign key (issue_id)
                                         references issues (id)
                                         on delete cascade,

                                 constraint fk_activity_events_actor_user
                                     foreign key (actor_user_id)
                                         references users (id),

                                 constraint ck_activity_events_type
                                     check (event_type in (
                                         'PROJECT_CREATED',
                                         'ISSUE_CREATED',
                                         'COMMENT_CREATED',
                                         'ISSUE_STATUS_CHANGED'
                                     ))
);

create index idx_projects_workspace_id
    on projects (workspace_id);

create index idx_issues_workspace_project_status
    on issues (workspace_id, project_id, status);

create index idx_issue_comments_issue_id
    on issue_comments (issue_id);

create index idx_activity_events_issue_created_at
    on activity_events (issue_id, created_at);
