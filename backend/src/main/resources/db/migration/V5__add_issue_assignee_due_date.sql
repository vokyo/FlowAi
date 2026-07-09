alter table issues
    add column assignee_user_id uuid,
    add column due_date date,
    add constraint fk_issues_assignee_user
        foreign key (assignee_user_id)
            references users (id);

create index idx_issues_workspace_project_assignee
    on issues (workspace_id, project_id, assignee_user_id);

alter table activity_events
    drop constraint ck_activity_events_type;

alter table activity_events
    add constraint ck_activity_events_type
        check (event_type in (
            'PROJECT_CREATED',
            'ISSUE_CREATED',
            'COMMENT_CREATED',
            'ISSUE_STATUS_CHANGED',
            'ISSUE_TITLE_CHANGED',
            'ISSUE_PRIORITY_CHANGED',
            'ISSUE_ASSIGNEE_CHANGED',
            'ISSUE_DUE_DATE_CHANGED'
        ));
