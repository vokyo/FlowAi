alter table ai_suggestions
    add column context_truncated boolean not null default false,
    add column created_issue_ids jsonb not null default '[]'::jsonb;

-- V15 did not persist created issue IDs. Preserve internally consistent legacy
-- APPLIED rows with an empty (unknown) result, and safely terminate any
-- partially-applied legacy row instead of inventing issue references.
update ai_suggestions
set status = 'EXPIRED',
    apply_idempotency_key = null,
    applied_at = null
where status = 'APPLIED'
  and (apply_idempotency_key is null or applied_at is null);

update ai_suggestions
set apply_idempotency_key = null,
    applied_at = null,
    created_issue_ids = '[]'::jsonb
where status <> 'APPLIED';

alter table ai_suggestions
    add constraint ck_ai_suggestions_created_issue_ids_array
        check (jsonb_typeof(created_issue_ids) = 'array'),
    add constraint ck_ai_suggestions_apply_state
        check (
            (
                status = 'APPLIED'
                and apply_idempotency_key is not null
                and applied_at is not null
            )
            or
            (
                status <> 'APPLIED'
                and apply_idempotency_key is null
                and applied_at is null
                and jsonb_array_length(created_issue_ids) = 0
            )
        );
