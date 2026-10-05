-- Retrieval for the planning agent's issue search: full-text search over a generated
-- column, and one embedding per issue for semantic search. Keyword search is unchanged.

-- Managed PostgreSQL providers only allow extensions they ship, so pgvector must be available.
create extension if not exists vector;

-- English stemming matches word forms (limit / limits) but not synonyms or other languages.
alter table issues
    add column search_document tsvector
        generated always as (
            setweight(to_tsvector('english', coalesce(title, '')), 'A')
                || setweight(to_tsvector('english', coalesce(description, '')), 'B')
        ) stored;

create index idx_issues_search_document
    on issues using gin (search_document);

-- 1536 dimensions is text-embedding-3-small. content_hash lets the worker skip issues whose text
-- has not changed. No approximate (HNSW) index: a project holds at most a few thousand issues, so
-- an exact scan within one project stays fast and never misses rows, whereas an approximate index
-- filtered by project afterwards can return fewer matches than requested.
create table issue_embeddings
(
    issue_id     uuid primary key,
    workspace_id uuid         not null,
    project_id   uuid         not null,
    model        varchar(100) not null,
    content_hash char(64)     not null,
    embedding    vector(1536) not null,
    embedded_at  timestamptz  not null,
    constraint fk_issue_embeddings_workspace_project_issue
        foreign key (workspace_id, project_id, issue_id)
            references issues (workspace_id, project_id, id)
            on delete cascade
);

create index idx_issue_embeddings_workspace_project
    on issue_embeddings (workspace_id, project_id);
