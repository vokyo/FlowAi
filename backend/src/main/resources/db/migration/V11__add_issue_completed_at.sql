alter table issues
    add column completed_at timestamptz;

update issues issue
set completed_at = issue.updated_at
from project_workflow_states workflow_state
where workflow_state.id = issue.workflow_state_id
  and workflow_state.category = 'DONE'
  and issue.archived_at is null;

create index idx_issues_workspace_project_completed_at
    on issues (workspace_id, project_id, completed_at)
    where archived_at is null
      and completed_at is not null;
