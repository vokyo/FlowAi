alter table issues
    add column board_position bigint;

with ranked_issues as (
    select id,
           row_number() over (
               partition by project_id, workflow_state_id
               order by created_at desc, id
           ) * 10000 as board_position
    from issues
)
update issues issue
set board_position = ranked_issue.board_position
from ranked_issues ranked_issue
where issue.id = ranked_issue.id;

alter table issues
    alter column board_position set not null;

create index idx_issues_workspace_project_workflow_state_board_position
    on issues (workspace_id, project_id, workflow_state_id, board_position);
