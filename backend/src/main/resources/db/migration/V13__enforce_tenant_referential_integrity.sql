do $$
declare
    violations text[] := array[]::text[];
    violation_count bigint;
begin
    select count(*)
    into violation_count
    from project_members member
    join projects project on project.id = member.project_id
    where member.workspace_id is distinct from project.workspace_id
       or not exists (
           select 1
           from workspace_memberships workspace_membership
           where workspace_membership.workspace_id = member.workspace_id
             and workspace_membership.user_id = member.user_id
       );

    if violation_count > 0 then
        violations := array_append(violations, format('project_members (%s rows)', violation_count));
    end if;

    select count(*)
    into violation_count
    from project_workflow_states workflow_state
    join projects project on project.id = workflow_state.project_id
    where workflow_state.workspace_id is distinct from project.workspace_id;

    if violation_count > 0 then
        violations := array_append(violations, format('project_workflow_states (%s rows)', violation_count));
    end if;

    select count(*)
    into violation_count
    from project_labels label
    join projects project on project.id = label.project_id
    where label.workspace_id is distinct from project.workspace_id;

    if violation_count > 0 then
        violations := array_append(violations, format('project_labels (%s rows)', violation_count));
    end if;

    select count(*)
    into violation_count
    from issues issue
    join projects project on project.id = issue.project_id
    join project_workflow_states workflow_state on workflow_state.id = issue.workflow_state_id
    where issue.workspace_id is distinct from project.workspace_id
       or issue.workspace_id is distinct from workflow_state.workspace_id
       or issue.project_id is distinct from workflow_state.project_id;

    if violation_count > 0 then
        violations := array_append(violations, format('issues (%s rows)', violation_count));
    end if;

    select count(*)
    into violation_count
    from issue_comments comment
    join issues issue on issue.id = comment.issue_id
    where comment.workspace_id is distinct from issue.workspace_id
       or comment.project_id is distinct from issue.project_id;

    if violation_count > 0 then
        violations := array_append(violations, format('issue_comments (%s rows)', violation_count));
    end if;

    select count(*)
    into violation_count
    from activity_events event
    left join projects project on project.id = event.project_id
    left join issues issue on issue.id = event.issue_id
    where (
        event.project_id is not null
        and (
            project.id is null
            or event.workspace_id is distinct from project.workspace_id
        )
    )
    or (
        event.issue_id is not null
        and (
            issue.id is null
            or event.project_id is null
            or event.workspace_id is distinct from issue.workspace_id
            or event.project_id is distinct from issue.project_id
        )
    );

    if violation_count > 0 then
        violations := array_append(violations, format('activity_events (%s rows)', violation_count));
    end if;

    select count(*)
    into violation_count
    from issue_labels issue_label
    join issues issue on issue.id = issue_label.issue_id
    join project_labels label on label.id = issue_label.label_id
    where issue.workspace_id is distinct from label.workspace_id
       or issue.project_id is distinct from label.project_id;

    if violation_count > 0 then
        violations := array_append(violations, format('issue_labels (%s rows)', violation_count));
    end if;

    select count(*)
    into violation_count
    from refresh_tokens refresh_token
    join workspace_memberships workspace_membership
        on workspace_membership.id = refresh_token.workspace_membership_id
    where refresh_token.user_id is distinct from workspace_membership.user_id;

    if violation_count > 0 then
        violations := array_append(violations, format('refresh_tokens (%s rows)', violation_count));
    end if;

    if cardinality(violations) > 0 then
        raise exception using
            errcode = '23514',
            message = 'V13 tenant integrity check failed for table(s): '
                || array_to_string(violations, ', '),
            hint = 'Fix the cross-workspace/project/issue rows in the named tables before rerunning V13.';
    end if;
end
$$;

alter table projects
    add constraint uk_projects_workspace_id
        unique (workspace_id, id);

alter table workspace_memberships
    add constraint uk_workspace_memberships_user_id
        unique (user_id, id);

alter table project_workflow_states
    add constraint uk_project_workflow_states_workspace_project_id
        unique (workspace_id, project_id, id);

alter table project_labels
    add constraint uk_project_labels_workspace_project_id
        unique (workspace_id, project_id, id);

alter table issues
    add constraint uk_issues_workspace_project_id
        unique (workspace_id, project_id, id);

alter table project_members
    drop constraint fk_project_members_workspace,
    drop constraint fk_project_members_project,
    drop constraint fk_project_members_user,
    add constraint fk_project_members_workspace_project
        foreign key (workspace_id, project_id)
            references projects (workspace_id, id)
            on delete cascade,
    add constraint fk_project_members_workspace_user
        foreign key (workspace_id, user_id)
            references workspace_memberships (workspace_id, user_id);

alter table refresh_tokens
    drop constraint fk_refresh_tokens_user,
    drop constraint fk_refresh_tokens_workspace_membership,
    add constraint fk_refresh_tokens_user_workspace_membership
        foreign key (user_id, workspace_membership_id)
            references workspace_memberships (user_id, id)
            on delete cascade;

alter table project_workflow_states
    drop constraint fk_project_workflow_states_workspace,
    drop constraint fk_project_workflow_states_project,
    add constraint fk_project_workflow_states_workspace_project
        foreign key (workspace_id, project_id)
            references projects (workspace_id, id)
            on delete cascade;

alter table project_labels
    drop constraint fk_project_labels_workspace,
    drop constraint fk_project_labels_project,
    add constraint fk_project_labels_workspace_project
        foreign key (workspace_id, project_id)
            references projects (workspace_id, id)
            on delete cascade;

alter table issues
    drop constraint fk_issues_workspace,
    drop constraint fk_issues_project,
    drop constraint fk_issues_workflow_state,
    add constraint fk_issues_workspace_project
        foreign key (workspace_id, project_id)
            references projects (workspace_id, id)
            on delete cascade,
    add constraint fk_issues_workspace_project_workflow_state
        foreign key (workspace_id, project_id, workflow_state_id)
            references project_workflow_states (workspace_id, project_id, id);

alter table issue_comments
    drop constraint fk_issue_comments_workspace,
    drop constraint fk_issue_comments_project,
    drop constraint fk_issue_comments_issue,
    add constraint fk_issue_comments_workspace_project_issue
        foreign key (workspace_id, project_id, issue_id)
            references issues (workspace_id, project_id, id)
            on delete cascade;

alter table activity_events
    drop constraint fk_activity_events_project,
    drop constraint fk_activity_events_issue,
    add constraint fk_activity_events_workspace_project
        foreign key (workspace_id, project_id)
            references projects (workspace_id, id)
            on delete cascade,
    add constraint fk_activity_events_workspace_project_issue
        foreign key (workspace_id, project_id, issue_id)
            references issues (workspace_id, project_id, id)
            on delete cascade,
    add constraint ck_activity_events_issue_requires_project
        check (issue_id is null or project_id is not null);

alter table issue_labels
    add column workspace_id uuid,
    add column project_id uuid;

update issue_labels issue_label
set workspace_id = issue.workspace_id,
    project_id = issue.project_id
from issues issue
where issue.id = issue_label.issue_id;

alter table issue_labels
    alter column workspace_id set not null,
    alter column project_id set not null;

create function set_issue_labels_tenant_scope()
returns trigger
language plpgsql
as $$
begin
    select issue.workspace_id, issue.project_id
    into new.workspace_id, new.project_id
    from issues issue
    where issue.id = new.issue_id;

    if not found then
        raise exception using
            errcode = '23503',
            message = format(
                'insert or update on table "issue_labels" violates foreign key: issue %s does not exist',
                new.issue_id
            );
    end if;

    return new;
end
$$;

create trigger trg_issue_labels_set_tenant_scope
    before insert or update of issue_id
    on issue_labels
    for each row
    execute function set_issue_labels_tenant_scope();

alter table issue_labels
    drop constraint fk_issue_labels_issue,
    drop constraint fk_issue_labels_label,
    add constraint fk_issue_labels_workspace_project_issue
        foreign key (workspace_id, project_id, issue_id)
            references issues (workspace_id, project_id, id)
            on delete cascade,
    add constraint fk_issue_labels_workspace_project_label
        foreign key (workspace_id, project_id, label_id)
            references project_labels (workspace_id, project_id, id)
            on delete cascade;

drop index idx_projects_workspace_id;
drop index idx_project_labels_workspace_project;
drop index idx_workspace_memberships_user_id;
drop index idx_workspace_memberships_workspace_id;
drop index idx_refresh_tokens_user_id;

create index idx_refresh_tokens_user_workspace_membership
    on refresh_tokens (user_id, workspace_membership_id);

create index idx_activity_events_workspace_project
    on activity_events (workspace_id, project_id)
    where project_id is not null;
