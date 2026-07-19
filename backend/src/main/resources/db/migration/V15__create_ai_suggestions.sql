create table ai_suggestions (
                                id uuid primary key default gen_random_uuid(),

                                workspace_id uuid not null,
                                project_id uuid not null,
                                source_issue_id uuid,
                                created_by_user_id uuid not null,

                                type varchar(40) not null,
                                status varchar(30) not null default 'DRAFT',

                                content jsonb not null,
                                prompt_version varchar(100) not null,
                                provider varchar(80),
                                model varchar(160),
                                input_hash varchar(64) not null,
                                input_tokens integer,
                                output_tokens integer,

                                apply_idempotency_key uuid,

                                created_at timestamptz not null default now(),
                                updated_at timestamptz not null default now(),
                                expires_at timestamptz not null,
                                applied_at timestamptz,
                                dismissed_at timestamptz,

                                constraint uk_ai_suggestions_tenant_project_id
                                    unique (workspace_id, project_id, id),

                                constraint fk_ai_suggestions_workspace_project
                                    foreign key (workspace_id, project_id)
                                        references projects (workspace_id, id)
                                        on delete cascade,

                                constraint fk_ai_suggestions_source_issue
                                    foreign key (workspace_id, project_id, source_issue_id)
                                        references issues (workspace_id, project_id, id),

                                constraint fk_ai_suggestions_creator_membership
                                    foreign key (workspace_id, created_by_user_id)
                                        references workspace_memberships (workspace_id, user_id),

                                constraint ck_ai_suggestions_type
                                    check (type in (
                                                    'ISSUE_BREAKDOWN',
                                                    'ISSUE_SUMMARY',
                                                    'PROJECT_SUMMARY'
                                        )),

                                constraint ck_ai_suggestions_status
                                    check (status in (
                                                      'DRAFT',
                                                      'APPLIED',
                                                      'DISMISSED',
                                                      'EXPIRED'
                                        )),

                                constraint ck_ai_suggestions_content_object
                                    check (jsonb_typeof(content) = 'object'),

                                constraint ck_ai_suggestions_tokens
                                    check (
                                        (input_tokens is null or input_tokens >= 0)
                                            and (output_tokens is null or output_tokens >= 0)
                                        ),

                                constraint ck_ai_suggestions_expiry
                                    check (expires_at > created_at),

                                constraint ck_ai_suggestions_source
                                    check (
                                        (type in ('ISSUE_BREAKDOWN', 'ISSUE_SUMMARY')
                                            and source_issue_id is not null)
                                            or
                                        (type = 'PROJECT_SUMMARY'
                                            and source_issue_id is null)
                                        )
);

create index idx_ai_suggestions_creator_created
    on ai_suggestions (
                       workspace_id,
                       created_by_user_id,
                       created_at desc
        );

create index idx_ai_suggestions_project_status
    on ai_suggestions (
                       workspace_id,
                       project_id,
                       status
        );

create index idx_ai_suggestions_draft_expiry
    on ai_suggestions (expires_at)
    where status = 'DRAFT';

create unique index uk_ai_suggestions_apply_idempotency
    on ai_suggestions (
                       workspace_id,
                       created_by_user_id,
                       apply_idempotency_key
        )
    where apply_idempotency_key is not null;