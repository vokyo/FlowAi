create table issue_watchers
(
    issue_id     uuid not null,
    user_id      uuid not null,
    workspace_id uuid not null,
    project_id   uuid not null,
    constraint pk_issue_watchers
        primary key (issue_id, user_id),
    constraint 	fk_issue_watchers_workspace_project_issue
        foreign key (workspace_id, project_id, issue_id)
            references issues (workspace_id, project_id, id)
            on delete cascade,
    constraint 	fk_issue_watchers_project_user
        foreign key (project_id, user_id)
            references project_members (project_id,user_id)
           on delete cascade
    );


create index idx_issue_watchers_user_id
    on issue_watchers(user_id);

create function set_issue_watchers_tenant_scope()
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
                    'insert or update on table "issue_watchers" violates foreign key: issue %s does not exist',
                    new.issue_id
                      );
    end if;

    return new;
end
$$;

create trigger trg_issue_watchers_set_tenant_scope
    before insert or update of issue_id
    on issue_watchers
    for each row
execute function set_issue_watchers_tenant_scope();