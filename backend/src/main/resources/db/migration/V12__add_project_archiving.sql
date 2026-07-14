alter table projects
    add column archived_at timestamptz;

create index idx_projects_workspace_archived
    on projects (workspace_id, archived_at);
