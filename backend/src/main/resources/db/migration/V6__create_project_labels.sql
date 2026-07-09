create table project_labels (
                                id uuid primary key default gen_random_uuid(),
                                workspace_id uuid not null,
                                project_id uuid not null,
                                name varchar(60) not null,
                                color varchar(7) not null default '#64748b',
                                created_at timestamptz not null default now(),
                                updated_at timestamptz not null default now(),

                                constraint fk_project_labels_workspace
                                    foreign key (workspace_id)
                                        references workspaces (id)
                                        on delete cascade,

                                constraint fk_project_labels_project
                                    foreign key (project_id)
                                        references projects (id)
                                        on delete cascade,

                                constraint ck_project_labels_name_not_blank
                                    check (btrim(name) <> ''),

                                constraint ck_project_labels_color
                                    check (color ~ '^#[0-9A-Fa-f]{6}$')
);

create unique index uk_project_labels_project_name_lower
    on project_labels (project_id, lower(name));

create index idx_project_labels_workspace_project
    on project_labels (workspace_id, project_id);

create table issue_labels (
                              issue_id uuid not null,
                              label_id uuid not null,

                              constraint pk_issue_labels
                                  primary key (issue_id, label_id),

                              constraint fk_issue_labels_issue
                                  foreign key (issue_id)
                                      references issues (id)
                                      on delete cascade,

                              constraint fk_issue_labels_label
                                  foreign key (label_id)
                                      references project_labels (id)
                                      on delete cascade
);

create index idx_issue_labels_label_id
    on issue_labels (label_id);
