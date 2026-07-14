-- Stable keyset pagination indexes. Each trailing id makes rows with identical
-- sort values deterministic and allows the next-page predicate to stay indexed.

create index idx_issues_workspace_project_created_id
    on issues (workspace_id, project_id, created_at desc, id desc);

drop index if exists idx_issues_workspace_project_workflow_state_board_position;

create index idx_issues_active_board_cursor
    on issues (workspace_id, project_id, workflow_state_id, board_position asc, id asc)
    where archived_at is null;

create index idx_issue_comments_workspace_project_issue_created_id
    on issue_comments (workspace_id, project_id, issue_id, created_at desc, id desc);

drop index if exists idx_activity_events_issue_created_at;

create index idx_activity_events_workspace_project_issue_created_id
    on activity_events (workspace_id, project_id, issue_id, created_at desc, id desc)
    where issue_id is not null;

drop index if exists idx_workspace_invitations_workspace_created_at;

create index idx_workspace_invitations_workspace_created_id
    on workspace_invitations (workspace_id, created_at desc, id desc);
