alter table workspace_memberships
    add column last_accessed_at timestamptz;

update workspace_memberships
set last_accessed_at = joined_at;

with default_memberships as (
    select distinct on (user_id) id
    from workspace_memberships
    where status = 'ACTIVE'
    order by user_id, joined_at asc, id asc
)
update workspace_memberships membership
set last_accessed_at = now()
from default_memberships default_membership
where membership.id = default_membership.id;

alter table workspace_memberships
    alter column last_accessed_at set not null;

alter table refresh_tokens
    add column workspace_membership_id uuid;

with default_memberships as (
    select distinct on (user_id) user_id, id
    from workspace_memberships
    where status = 'ACTIVE'
    order by user_id, joined_at asc, id asc
)
update refresh_tokens refresh_token
set workspace_membership_id = default_membership.id
from default_memberships default_membership
where refresh_token.user_id = default_membership.user_id;

delete from refresh_tokens
where workspace_membership_id is null;

alter table refresh_tokens
    alter column workspace_membership_id set not null,
    add constraint fk_refresh_tokens_workspace_membership
        foreign key (workspace_membership_id)
            references workspace_memberships (id)
            on delete cascade;

create index idx_workspace_memberships_user_status_last_accessed
    on workspace_memberships (user_id, status, last_accessed_at desc);

create index idx_refresh_tokens_workspace_membership_id
    on refresh_tokens (workspace_membership_id);
