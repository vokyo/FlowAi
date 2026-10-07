-- A planning run whose plan waits for review, and every version of that plan. The
-- agent keeps its own working state in the agent_checkpoint schema; these rows are
-- what the backend decides with: which version is the latest, whether it can be
-- approved, and how the run ended.
create table agent_runs (
    -- The run id the agent also uses as the thread id of its checkpoints.
    id uuid primary key,

    workspace_id uuid not null,
    project_id uuid not null,
    created_by_user_id uuid not null,

    goal varchar(500) not null,
    -- The date the agent was told is today. Every version is planned and checked
    -- against it, so a due date passing while the run waits does not block it.
    generated_on date not null,
    state varchar(20) not null,
    latest_version integer not null,

    created_at timestamptz not null,
    updated_at timestamptz not null,

    constraint fk_agent_runs_workspace_project
        foreign key (workspace_id, project_id)
            references projects (workspace_id, id)
            on delete cascade,

    constraint fk_agent_runs_creator_membership
        foreign key (workspace_id, created_by_user_id)
            references workspace_memberships (workspace_id, user_id),

    constraint ck_agent_runs_state
        check (state in ('REVIEWING', 'APPROVED', 'CANCELLED')),

    constraint ck_agent_runs_latest_version
        check (latest_version between 1 and 5)
);

create index idx_agent_runs_creator_created
    on agent_runs (workspace_id, created_by_user_id, created_at desc);

create table agent_plan_versions (
    id uuid primary key default gen_random_uuid(),
    run_id uuid not null references agent_runs (id) on delete cascade,
    version integer not null,

    content jsonb not null,
    content_hash varchar(64) not null,
    -- Why this version cannot be approved; null while it can be.
    rejection_reason varchar(1000),
    -- The draft an approvable version is applied through. A version that was found
    -- unapprovable only when it was approved keeps the draft it had.
    suggestion_id uuid references ai_suggestions (id),
    -- Where the agent stopped for review with this version, so a revision continues
    -- from exactly here.
    checkpoint_id varchar(100),

    created_at timestamptz not null,

    constraint uk_agent_plan_versions_run_version
        unique (run_id, version),

    constraint ck_agent_plan_versions_version
        check (version between 1 and 5),

    constraint ck_agent_plan_versions_content_object
        check (jsonb_typeof(content) = 'object'),

    constraint ck_agent_plan_versions_approvable_has_draft
        check (rejection_reason is not null or suggestion_id is not null)
);

create unique index uk_agent_plan_versions_suggestion
    on agent_plan_versions (suggestion_id)
    where suggestion_id is not null;
