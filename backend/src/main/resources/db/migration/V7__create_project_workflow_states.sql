create table project_workflow_states (
                                         id uuid primary key default gen_random_uuid(),
                                         workspace_id uuid not null,
                                         project_id uuid not null,
                                         name varchar(60) not null,
                                         category varchar(30) not null,
                                         position integer not null,
                                         created_at timestamptz not null default now(),
                                         updated_at timestamptz not null default now(),

                                         constraint fk_project_workflow_states_workspace
                                             foreign key (workspace_id)
                                                 references workspaces (id)
                                                 on delete cascade,

                                         constraint fk_project_workflow_states_project
                                             foreign key (project_id)
                                                 references projects (id)
                                                 on delete cascade,

                                         constraint ck_project_workflow_states_name_not_blank
                                             check (btrim(name) <> ''),

                                         constraint ck_project_workflow_states_category
                                             check (category in ('TODO', 'IN_PROGRESS', 'DONE'))
);

create unique index uk_project_workflow_states_project_name_lower
    on project_workflow_states (project_id, lower(name));

create index idx_project_workflow_states_workspace_project_position
    on project_workflow_states (workspace_id, project_id, position);

insert into project_workflow_states (workspace_id, project_id, name, category, position)
select workspace_id, id, 'Todo', 'TODO', 10000
from projects;

insert into project_workflow_states (workspace_id, project_id, name, category, position)
select workspace_id, id, 'In progress', 'IN_PROGRESS', 20000
from projects;

insert into project_workflow_states (workspace_id, project_id, name, category, position)
select workspace_id, id, 'Done', 'DONE', 30000
from projects;

alter table issues
    add column workflow_state_id uuid,
    add column archived_at timestamptz;

update issues issue
set workflow_state_id = workflow_state.id
from project_workflow_states workflow_state
where workflow_state.project_id = issue.project_id
  and workflow_state.category = case
      when issue.status = 'ARCHIVED' then 'DONE'
      else issue.status
  end;

update issues
set archived_at = updated_at
where status = 'ARCHIVED'
  and archived_at is null;

alter table issues
    alter column workflow_state_id set not null,
    add constraint fk_issues_workflow_state
        foreign key (workflow_state_id)
            references project_workflow_states (id);

create index idx_issues_workspace_project_workflow_state
    on issues (workspace_id, project_id, workflow_state_id);

create index idx_issues_workspace_project_archived
    on issues (workspace_id, project_id, archived_at);
