-- A project plan comes from the planning agent. Like a project summary it belongs
-- to the whole project, so it never has a source issue.
alter table ai_suggestions
    drop constraint ck_ai_suggestions_type;

alter table ai_suggestions
    add constraint ck_ai_suggestions_type
        check (type in (
                        'ISSUE_BREAKDOWN',
                        'ISSUE_SUMMARY',
                        'PROJECT_SUMMARY',
                        'PROJECT_PLAN'
            ));

alter table ai_suggestions
    drop constraint ck_ai_suggestions_source;

alter table ai_suggestions
    add constraint ck_ai_suggestions_source
        check (
            (type in ('ISSUE_BREAKDOWN', 'ISSUE_SUMMARY')
                and source_issue_id is not null)
                or
            (type in ('PROJECT_SUMMARY', 'PROJECT_PLAN')
                and source_issue_id is null)
            );
