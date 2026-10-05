-- Outbox for issue embeddings. A row is written in the same transaction that creates an
-- issue or changes its title or description, so a request is never lost if the process
-- stops before the embedding is computed. A background worker turns rows into vectors.
create table issue_embedding_jobs
(
    issue_id        uuid primary key,
    -- Bumped by every new request. The worker deletes a row only at the revision it read,
    -- so an edit made while the old text was being embedded keeps its own request.
    revision        bigint       not null default 1,
    requested_at    timestamptz  not null default now(),
    -- When the worker may next pick the row up. A claim pushes it forward as a lease, so a
    -- second worker skips the row while the first one is calling the embedding API.
    next_attempt_at timestamptz  not null default now(),
    attempts        integer      not null default 0,
    last_error      varchar(200),
    constraint fk_issue_embedding_jobs_issue
        foreign key (issue_id)
            references issues (id)
            on delete cascade
);

create index idx_issue_embedding_jobs_next_attempt_at
    on issue_embedding_jobs (next_attempt_at);

-- Issues that existed before this migration get one request each.
insert into issue_embedding_jobs (issue_id)
select id
from issues;
