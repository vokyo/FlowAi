package com.vokyo.backend.demo;

import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.workspace.WorkspaceRole;

import java.util.List;

/**
 * The content of the demo workspace, kept apart from the code that writes it.
 *
 * <p>Everything is relative to the seeding day rather than a fixed calendar, so a
 * database reset a year from now still produces a workspace that looks worked-in:
 * eight weeks of history and a handful of issues finished in the last week.
 *
 * <p>Completion days deliberately repeat. A team does not finish exactly one issue
 * every other day, and a dataset that says it does draws a completion trend of
 * identical bars at even spacing — visibly generated. Days here carry one to three
 * completions with empty days between them, and the seeder shifts anything that
 * falls on a weekend back to the Friday. Every completion is at least five days
 * after the issue that carries it, which leaves room for that shift.
 *
 * <p>The counts here are what the checks in {@code DemoSeedIntegrationTests}
 * assert, so changing a list changes an expected number there too.
 */
final class DemoDataset {

    static final String DEMO = "demo";
    static final String MAYA = "maya";
    static final String DANIEL = "daniel";
    static final String PRIYA = "priya";

    static final String DEMO_DISPLAY_NAME = "Demo User";

    /** Labels the seeded AI draft suggests, and the issue it hangs off. */
    static final String COPILOT_SOURCE_ISSUE = "Break the checkout flow into resumable steps";

    private DemoDataset() {
    }

    record DemoMember(String key, String email, String displayName, WorkspaceRole role) {
    }

    record DemoLabel(String name, String color) {
    }

    record DemoWorkflowState(String name, WorkflowStateCategory category) {
    }

    record DemoProject(
            String name,
            String description,
            List<DemoWorkflowState> extraStates,
            List<String> stateOrder,
            List<DemoLabel> labels,
            List<DemoIssue> issues
    ) {
    }

    /**
     * @param stateIndex        position in the project's {@code stateOrder}
     * @param createdDaysAgo    how long ago the issue was opened
     * @param completedDaysAgo  how long ago it was finished; set exactly when the
     *                          issue sits in a DONE state
     * @param dueInDays         due date relative to today, negative for overdue
     */
    record DemoIssue(
            String title,
            String description,
            int stateIndex,
            IssuePriority priority,
            List<String> labels,
            String assignee,
            int createdDaysAgo,
            Integer completedDaysAgo,
            Integer dueInDays
    ) {
    }

    record DemoComment(String issueTitle, String author, String body, int daysAgo) {
    }

    record DemoBreakdownItem(
            String clientItemId,
            String title,
            String description,
            IssuePriority priority,
            List<String> acceptanceCriteria,
            List<String> labels,
            String assignee
    ) {
    }

    static List<DemoMember> members() {
        return List.of(
                new DemoMember(MAYA, "maya@flowai.dev", "Maya Okonkwo", WorkspaceRole.ADMIN),
                new DemoMember(DANIEL, "daniel@flowai.dev", "Daniel Reyes", WorkspaceRole.MEMBER),
                new DemoMember(PRIYA, "priya@flowai.dev", "Priya Raman", WorkspaceRole.MEMBER)
        );
    }

    static List<DemoProject> projects() {
        return List.of(webPlatform(), mobileApp());
    }

    // ---------------------------------------------------------------- projects

    private static DemoProject webPlatform() {
        return new DemoProject(
                "Web Platform",
                "The customer-facing web app: onboarding, workspace administration, "
                        + "the issue board, and the reporting surface.",
                List.of(
                        new DemoWorkflowState("In review", WorkflowStateCategory.IN_PROGRESS),
                        new DemoWorkflowState("Backlog", WorkflowStateCategory.TODO)
                ),
                List.of("Backlog", "Todo", "In progress", "In review", "Done"),
                List.of(
                        new DemoLabel("bug", "#DC2626"),
                        new DemoLabel("feature", "#2563EB"),
                        new DemoLabel("tech-debt", "#B45309"),
                        new DemoLabel("design", "#7C3AED"),
                        new DemoLabel("infra", "#0F766E"),
                        new DemoLabel("docs", "#475569")
                ),
                webPlatformIssues()
        );
    }

    private static DemoProject mobileApp() {
        return new DemoProject(
                "Mobile App",
                "iOS and Android clients sharing the platform API, with offline "
                        + "reads and push notifications.",
                List.of(new DemoWorkflowState("In review", WorkflowStateCategory.IN_PROGRESS)),
                List.of("Todo", "In progress", "In review", "Done"),
                List.of(
                        new DemoLabel("bug", "#DC2626"),
                        new DemoLabel("feature", "#2563EB"),
                        new DemoLabel("release", "#BE123C"),
                        new DemoLabel("accessibility", "#0E7490")
                ),
                mobileAppIssues()
        );
    }

    // ------------------------------------------------------- Web Platform work

    private static final int BACKLOG = 0;
    private static final int TODO = 1;
    private static final int IN_PROGRESS = 2;
    private static final int IN_REVIEW = 3;
    private static final int DONE = 4;

    private static List<DemoIssue> webPlatformIssues() {
        return List.of(
                // Finished work spread across the last eight weeks, clustered onto
                // shared completion days so the trend has shape at every range the
                // UI offers rather than a flat row of ones.
                done("Rotate refresh tokens on every use",
                        "Each refresh mints a new token and revokes the presented one, so a "
                                + "leaked token is usable at most once.",
                        IssuePriority.HIGH, List.of("tech-debt"), DANIEL, 56, 49),
                done("Deliver the refresh token as an HttpOnly SameSite=Strict cookie",
                        "Keeps the refresh token out of JavaScript's reach and scopes the "
                                + "cookie to /api.",
                        IssuePriority.HIGH, List.of("tech-debt"), MAYA, 55, 49),
                done("Return one JSON error shape from every endpoint",
                        "code, message, fieldErrors, traceId — so the client renders errors "
                                + "the same way everywhere.",
                        IssuePriority.MEDIUM, List.of("tech-debt", "docs"), DEMO, 54, 49),
                done("Hash workspace invitation tokens at rest",
                        "Only the hash is stored; the plaintext token exists in the invite "
                                + "link and nowhere else.",
                        IssuePriority.HIGH, List.of("tech-debt"), DANIEL, 53, 45),
                done("Add the Flyway baseline for the multi-tenant schema",
                        "Every table carries workspace_id with a foreign key, so tenancy is "
                                + "enforced by the database and not only by queries.",
                        IssuePriority.MEDIUM, List.of("infra"), MAYA, 52, 41),
                done("Scope every issue query by workspace id",
                        "A missing tenant predicate on the issue list leaked titles across "
                                + "workspaces in a staging test.",
                        IssuePriority.URGENT, List.of("bug"), DANIEL, 50, 41),
                done("Paginate the issue list with an opaque cursor",
                        "Keyset pagination on (created_at, id), with the cursor signed so it "
                                + "cannot be edited into another workspace's range.",
                        IssuePriority.MEDIUM, List.of("feature"), PRIYA, 48, 37),
                done("Add the composite index behind cursor pagination",
                        "Without (workspace_id, project_id, created_at desc, id desc) the "
                                + "second page fell back to a sequential scan.",
                        IssuePriority.MEDIUM, List.of("infra", "tech-debt"), DANIEL, 46, 33),
                done("Batch label loading to remove the N+1 on the board",
                        "A board of fifty cards was issuing fifty label queries.",
                        IssuePriority.HIGH, List.of("tech-debt"), MAYA, 45, 33),
                done("Move project settings to their own route",
                        "Settings were a modal that lost state on refresh and could not be "
                                + "linked to.",
                        IssuePriority.LOW, List.of("design"), PRIYA, 43, 28),
                done("Debounce the issue search field",
                        "Every keystroke was a request; the list now waits for a pause.",
                        IssuePriority.LOW, List.of("tech-debt"), PRIYA, 40, 28),
                done("Persist board column order across reloads",
                        "Column order is part of the project's workflow states rather than "
                                + "browser state.",
                        IssuePriority.MEDIUM, List.of("feature"), DEMO, 38, 24),
                done("Show comment counts on issue cards",
                        "One aggregate query per page instead of a count per card.",
                        IssuePriority.LOW, List.of("feature"), PRIYA, 36, 20),
                done("Reject workflow state deletion without a replacement",
                        "Deleting a column used to orphan its issues. The API now requires a "
                                + "state to move them to.",
                        IssuePriority.MEDIUM, List.of("bug"), DANIEL, 34, 20),
                done("Rate limit the login endpoint per email",
                        "Bucket4j, keyed on the normalised email, with the bucket cleared on "
                                + "a successful sign-in.",
                        IssuePriority.HIGH, List.of("tech-debt"), MAYA, 32, 20),
                done("Put a trace id on every request and error response",
                        "The id is in the response body, the access log, and the MDC, so a "
                                + "reported error can be found in the logs.",
                        IssuePriority.MEDIUM, List.of("infra", "docs"), DEMO, 30, 16),
                done("Design an empty state for a project with no issues",
                        "A blank board told a new user nothing about what to do next.",
                        IssuePriority.LOW, List.of("design"), PRIYA, 28, 16),
                done("Stop archived projects from accepting new issues",
                        "Archiving hid the project but the API still wrote to it.",
                        IssuePriority.MEDIUM, List.of("bug"), DANIEL, 26, 11),
                done("Cache the workspace switcher between navigations",
                        "The switcher refetched the membership list on every route change.",
                        IssuePriority.LOW, List.of("tech-debt"), MAYA, 24, 7),
                done("Fix the due date off-by-one at the UTC boundary",
                        "Due dates a day early for anyone west of UTC: the date was built "
                                + "from a local timestamp and stored as a UTC day.",
                        IssuePriority.HIGH, List.of("bug"), DANIEL, 20, 7),
                done("Add a keyboard focus ring to board cards",
                        "Cards were reachable by tab but gave no visible focus.",
                        IssuePriority.LOW, List.of("design"), PRIYA, 18, 7),
                done("Surface API validation errors next to the field that failed",
                        "fieldErrors from the shared error shape now map onto the form.",
                        IssuePriority.MEDIUM, List.of("bug", "design"), MAYA, 15, 5),
                done("Split the analytics overview into three projections",
                        "One query per panel — summary, distribution, trend — instead of a "
                                + "join that grew with the issue count.",
                        IssuePriority.MEDIUM, List.of("tech-debt"), DEMO, 12, 1),
                done("Retry a failed board reorder once before surfacing an error",
                        "Two people dragging in the same column produced a lost update the "
                                + "user could not act on.",
                        IssuePriority.LOW, List.of("bug"), DANIEL, 9, 1),

                // In review
                open("Add optimistic updates to the board drag handler",
                        "The card should move on drop and roll back if the request fails, "
                                + "rather than waiting a round trip.",
                        IN_REVIEW, IssuePriority.MEDIUM, List.of("feature"), PRIYA, 11, 2),
                open("Extract the workspace context resolver into one service",
                        "Four services each rebuilt the current workspace from the JWT.",
                        IN_REVIEW, IssuePriority.MEDIUM, List.of("tech-debt"), DANIEL, 9, 3),
                open("Render markdown in issue descriptions",
                        "Sanitised, with a restricted tag set and no raw HTML.",
                        IN_REVIEW, IssuePriority.LOW, List.of("feature"), MAYA, 8, 4),
                open("Add a workspace member directory",
                        "Roles, join dates, and pending invitations on one page.",
                        IN_REVIEW, IssuePriority.MEDIUM, List.of("feature"), PRIYA, 7, 5),
                open("Log queries slower than 200ms",
                        "Enough to catch a regression without logging every read.",
                        IN_REVIEW, IssuePriority.LOW, List.of("infra"), DEMO, 5, null),

                // In progress
                open(COPILOT_SOURCE_ISSUE,
                        "Checkout is one long form. A dropped connection loses everything "
                                + "the customer entered. Split it into steps that persist "
                                + "server-side so a session can be resumed on another device.",
                        IN_PROGRESS, IssuePriority.HIGH, List.of("feature", "design"), MAYA, 16, 6),
                open("Add per-workspace rate limits to the AI endpoints",
                        "The existing limiter is per user, so one workspace can exhaust a "
                                + "provider quota shared by everyone.",
                        IN_PROGRESS, IssuePriority.HIGH, List.of("tech-debt", "infra"), DANIEL, 13, 4),
                open("Replace the polling board refresh with server-sent events",
                        "The board polls every ten seconds whether or not anything changed.",
                        IN_PROGRESS, IssuePriority.MEDIUM, List.of("feature"), DEMO, 12, 9),
                open("Support bulk assignee changes from the issue list",
                        "Select several issues and reassign them in one request.",
                        IN_PROGRESS, IssuePriority.MEDIUM, List.of("feature"), PRIYA, 10, 7),
                open("Add an audit trail for workspace role changes",
                        "Who changed whose role, and when. Owners need this before we can "
                                + "sell to teams with a compliance review.",
                        IN_PROGRESS, IssuePriority.MEDIUM, List.of("feature"), DANIEL, 8, 12),
                open("Make the analytics range selector shareable by URL",
                        "The selected range lives in component state, so a link always opens "
                                + "on thirty days.",
                        IN_PROGRESS, IssuePriority.LOW, List.of("bug"), PRIYA, 6, 3),
                open("Tighten Nginx cache headers for hashed assets",
                        "Immutable, one year, for anything with a content hash in the name.",
                        IN_PROGRESS, IssuePriority.LOW, List.of("infra"), MAYA, 4, 8),

                // Todo
                open("Add saved filter views to the issue list",
                        "Name a set of filters and pin it to the project sidebar.",
                        TODO, IssuePriority.MEDIUM, List.of("feature"), PRIYA, 14, 16),
                open("Send an email when an invitation is issued",
                        "Invitations are copy-a-link only, which does not survive contact "
                                + "with a real team.",
                        TODO, IssuePriority.HIGH, List.of("feature"), MAYA, 12, 10),
                open("Export a project's issues as CSV",
                        "Asked for in three of the last five demo calls.",
                        TODO, IssuePriority.LOW, List.of("feature"), null, 11, null),
                open("Add a due-this-week digest to the dashboard",
                        "Issues due in the next seven days, grouped by assignee.",
                        TODO, IssuePriority.MEDIUM, List.of("feature"), DEMO, 9, 21),
                open("Show a diff when an issue title changes",
                        "The activity entry records both values but renders neither.",
                        TODO, IssuePriority.LOW, List.of("design"), null, 7, null),
                open("Add project templates for new workspaces",
                        "A new workspace starts with three states and no labels; most teams "
                                + "rebuild the same setup by hand.",
                        TODO, IssuePriority.MEDIUM, List.of("feature"), MAYA, 6, 30),
                open("Warn before archiving a project with open issues",
                        "Archiving is reversible but silent, and it hides live work.",
                        TODO, IssuePriority.MEDIUM, List.of("design"), DANIEL, 5, -2),
                open("Support attachments on issue comments",
                        "Screenshots are the most common thing people try to paste into a "
                                + "comment, and it silently does nothing.",
                        TODO, IssuePriority.HIGH, List.of("feature"), PRIYA, 3, 14),

                // Backlog
                open("Search across every project in a workspace",
                        "Search is scoped to one project at a time.",
                        BACKLOG, IssuePriority.LOW, List.of("feature"), null, 30, null),
                open("Custom fields on issues",
                        "Per-project fields with a small set of types.",
                        BACKLOG, IssuePriority.LOW, List.of("feature"), null, 28, null),
                open("Sprint planning view with capacity",
                        "Assign issues to a dated sprint and show load per assignee.",
                        BACKLOG, IssuePriority.MEDIUM, List.of("feature"), null, 26, null),
                open("Slack notification when an issue is assigned to you",
                        "Needs an outbound integration story we do not have yet.",
                        BACKLOG, IssuePriority.MEDIUM, List.of("feature"), MAYA, 24, null),
                open("Public read-only project sharing links",
                        "Share a board with someone outside the workspace.",
                        BACKLOG, IssuePriority.LOW, List.of("feature"), null, 22, null),
                open("Link GitHub pull requests to issues",
                        "Match on a branch naming convention and show status on the card.",
                        BACKLOG, IssuePriority.MEDIUM, List.of("feature"), DANIEL, 20, null),
                open("Time tracking on issues",
                        "Estimate and logged time, rolled up per project.",
                        BACKLOG, IssuePriority.LOW, List.of("feature"), null, 18, null),
                open("Dependency graph between issues",
                        "Blocks and is-blocked-by, with cycle detection.",
                        BACKLOG, IssuePriority.LOW, List.of("feature"), null, 15, null),
                open("Recurring issues for maintenance work",
                        "Certificate renewals and dependency bumps are recreated by hand.",
                        BACKLOG, IssuePriority.LOW, List.of("feature"), null, 13, null),
                open("SAML single sign-on for enterprise workspaces",
                        "The most common blocker raised in enterprise conversations.",
                        BACKLOG, IssuePriority.HIGH, List.of("feature"), null, 11, null),
                open("Rework the board layout for mobile web",
                        "Columns are unusable below 640px.",
                        BACKLOG, IssuePriority.MEDIUM, List.of("design"), PRIYA, 8, null),
                open("Keep comment drafts when the connection drops",
                        "A long comment is lost if the tab reloads mid-write.",
                        BACKLOG, IssuePriority.LOW, List.of("feature"), null, 5, null)
        );
    }

    // --------------------------------------------------------- Mobile App work

    private static final int M_TODO = 0;
    private static final int M_IN_PROGRESS = 1;
    private static final int M_IN_REVIEW = 2;
    private static final int M_DONE = 3;

    private static List<DemoIssue> mobileAppIssues() {
        return List.of(
                mobileDone("Ship the shared API client with token refresh",
                        "One client for both platforms, refreshing on a 401 and retrying "
                                + "the original request once.",
                        IssuePriority.HIGH, List.of("feature"), DANIEL, 52, 45),
                mobileDone("Add biometric unlock on iOS",
                        "Face ID gate on cold start, with a passcode fallback.",
                        IssuePriority.MEDIUM, List.of("feature"), MAYA, 46, 36),
                mobileDone("Cache the board for offline reads",
                        "The last synced board renders without a connection; writes still "
                                + "require one.",
                        IssuePriority.HIGH, List.of("feature"), DANIEL, 44, 36),
                mobileDone("Fix the crash when opening an archived issue",
                        "The detail screen assumed a workflow state that archived issues "
                                + "do not carry.",
                        IssuePriority.URGENT, List.of("bug"), MAYA, 33, 26),
                mobileDone("Add pull-to-refresh on the issue list",
                        "The only way to refresh was to leave the screen and come back.",
                        IssuePriority.LOW, List.of("feature"), PRIYA, 31, 26),
                mobileDone("Support dark mode on both platforms",
                        "Follows the system setting, with a manual override in settings.",
                        IssuePriority.MEDIUM, List.of("feature"), PRIYA, 22, 15),
                mobileDone("Stop the Android keyboard covering the comment box",
                        "The compose field sat behind the keyboard on shorter screens.",
                        IssuePriority.HIGH, List.of("bug"), DANIEL, 12, 4),

                open("Deep link from a notification to the issue",
                        "Tapping a notification opens the app on the right issue rather "
                                + "than the board.",
                        M_IN_REVIEW, IssuePriority.MEDIUM, List.of("feature"), MAYA, 10, 4),
                open("Bring cold start under 1.5 seconds",
                        "Currently 2.4s on a mid-range Android device.",
                        M_IN_REVIEW, IssuePriority.HIGH, List.of("feature"), DANIEL, 7, 6),

                open("Build the offline comment queue",
                        "Queue comments written offline and flush them in order once the "
                                + "connection returns, without duplicating on retry.",
                        M_IN_PROGRESS, IssuePriority.HIGH, List.of("feature"), DANIEL, 14, 9),
                open("Add a compact board layout for small screens",
                        "One column at a time with a state switcher.",
                        M_IN_PROGRESS, IssuePriority.MEDIUM, List.of("feature"), PRIYA, 9, 12),
                open("Ask for push notification permission at the right moment",
                        "Asking on first launch gets refused; ask after the first assignment.",
                        M_IN_PROGRESS, IssuePriority.MEDIUM, List.of("feature"), MAYA, 6, 8),

                open("Add release notes to the about screen",
                        "Pulled from the release tag at build time.",
                        M_TODO, IssuePriority.LOW, List.of("release"), null, 12, null),
                open("Upload an attachment from the camera",
                        "Depends on comment attachments landing on the web platform first.",
                        M_TODO, IssuePriority.MEDIUM, List.of("feature"), PRIYA, 10, 20),
                open("Add a widget showing issues due today",
                        "Home screen widget on both platforms.",
                        M_TODO, IssuePriority.LOW, List.of("feature"), null, 8, null),
                open("Localise into German and Japanese",
                        "Strings are already extracted; this is translation and layout work.",
                        M_TODO, IssuePriority.MEDIUM, List.of("feature"), null, 6, null),
                open("Add VoiceOver labels to every board card",
                        "Cards read as a list of unlabelled buttons.",
                        M_TODO, IssuePriority.HIGH, List.of("accessibility"), PRIYA, 4, 7),
                open("Automate the TestFlight upload from CI",
                        "Releases are cut by hand from a laptop.",
                        M_TODO, IssuePriority.MEDIUM, List.of("release"), DANIEL, 2, 15)
        );
    }

    // ---------------------------------------------------------------- comments

    static List<DemoComment> comments() {
        return List.of(
                new DemoComment(COPILOT_SOURCE_ISSUE, DEMO,
                        "Pulling this forward. Support has three tickets this week from "
                                + "customers who lost a cart on a flaky connection.", 14),
                new DemoComment(COPILOT_SOURCE_ISSUE, MAYA,
                        "Four steps: cart review, address, payment method, confirm. Each one "
                                + "PATCHes a checkout session so the state is on the server, "
                                + "not in the tab.", 12),
                new DemoComment(COPILOT_SOURCE_ISSUE, DANIEL,
                        "The session needs a TTL and an idempotency key on confirm, or a "
                                + "double submit charges twice.", 9),
                new DemoComment(COPILOT_SOURCE_ISSUE, MAYA,
                        "Agreed. I will split this into per-step issues once the session "
                                + "model is settled.", 5),

                new DemoComment("Add per-workspace rate limits to the AI endpoints", DANIEL,
                        "The limiter is keyed on user id today. Workspace id needs to be the "
                                + "outer bucket, with the per-user one kept inside it.", 11),
                new DemoComment("Add per-workspace rate limits to the AI endpoints", DEMO,
                        "What happens on the shared demo account, where everyone is the same "
                                + "user in the same workspace?", 8),
                new DemoComment("Add per-workspace rate limits to the AI endpoints", DANIEL,
                        "They collide, which is exactly why the public demo runs with the "
                                + "provider disabled and a saved draft instead.", 6),

                new DemoComment("Fix the due date off-by-one at the UTC boundary", PRIYA,
                        "Reproduced in Los Angeles: a due date set for the 14th renders as "
                                + "the 13th after a reload.", 18),
                new DemoComment("Fix the due date off-by-one at the UTC boundary", DANIEL,
                        "The field is a LocalDate, but the client was sending an ISO instant "
                                + "and the server took the UTC day from it.", 15),
                new DemoComment("Fix the due date off-by-one at the UTC boundary", DANIEL,
                        "Fixed by sending a plain yyyy-MM-dd. Added a test at the boundary "
                                + "in both directions.", 9),

                new DemoComment("Support attachments on issue comments", PRIYA,
                        "Paste-to-upload is the interaction people expect. Drag and drop is "
                                + "secondary.", 3),
                new DemoComment("Support attachments on issue comments", MAYA,
                        "Needs an object store and a size limit before any of it is safe to "
                                + "expose.", 2),
                new DemoComment("Support attachments on issue comments", DEMO,
                        "Let us scope the first version to images under 5MB.", 1),

                new DemoComment("Build the offline comment queue", DANIEL,
                        "Each queued comment carries a client-generated id so a retry after "
                                + "a timeout does not post twice.", 12),
                new DemoComment("Build the offline comment queue", PRIYA,
                        "The UI should show queued comments in place, greyed, rather than "
                                + "hiding them until they send.", 8),
                new DemoComment("Build the offline comment queue", DANIEL,
                        "Good call. Flushing in order per issue, oldest first.", 4)
        );
    }

    // ----------------------------------------------------------- AI suggestion

    static String breakdownOverview() {
        return "Resumable checkout splits into a server-side session plus one issue per "
                + "step. The session and its idempotency guarantees come first; the step "
                + "UIs can then land independently.";
    }

    /**
     * A saved Copilot draft, written as if a model had produced it. No provider is
     * called during seeding: the public demo runs with AI switched off, and a
     * stored draft still shows the editable review and the Apply flow the project
     * is built around.
     */
    static List<DemoBreakdownItem> breakdownItems() {
        return List.of(
                new DemoBreakdownItem(
                        "item-1",
                        "Add a server-side checkout session model",
                        "Persist cart, address, and payment selection against a session id "
                                + "with a TTL, so a step can be resumed from another device.",
                        IssuePriority.HIGH,
                        List.of(
                                "A session survives a browser reload and a device change",
                                "Sessions expire after 24 hours of inactivity",
                                "Session state is scoped to the workspace that owns the cart"
                        ),
                        List.of("feature"),
                        MAYA
                ),
                new DemoBreakdownItem(
                        "item-2",
                        "Make checkout confirmation idempotent",
                        "Require an idempotency key on confirm and replay the stored result "
                                + "for a repeated key, so a double submit cannot charge twice.",
                        IssuePriority.HIGH,
                        List.of(
                                "Confirming twice with one key produces one charge",
                                "A repeated key returns the original result rather than an error",
                                "Keys are retained for at least 24 hours"
                        ),
                        List.of("feature", "tech-debt"),
                        DANIEL
                ),
                new DemoBreakdownItem(
                        "item-3",
                        "Split the checkout form into four routed steps",
                        "Cart review, address, payment method, and confirm, each with its own "
                                + "URL so a step can be linked to and the back button works.",
                        IssuePriority.MEDIUM,
                        List.of(
                                "Each step has its own route and browser history entry",
                                "Leaving mid-flow and returning resumes at the last step",
                                "Validation errors keep the customer on the failing step"
                        ),
                        List.of("feature", "design"),
                        PRIYA
                ),
                new DemoBreakdownItem(
                        "item-4",
                        "Add a resumable-checkout end-to-end test",
                        "Drive the four steps, drop the connection mid-flow, and assert the "
                                + "session resumes with the entered data intact.",
                        IssuePriority.MEDIUM,
                        List.of(
                                "The test covers a reload between every pair of steps",
                                "A simulated network failure on confirm does not double charge"
                        ),
                        List.of("tech-debt"),
                        DANIEL
                )
        );
    }

    // ----------------------------------------------------------------- helpers

    private static DemoIssue done(
            String title,
            String description,
            IssuePriority priority,
            List<String> labels,
            String assignee,
            int createdDaysAgo,
            int completedDaysAgo
    ) {
        return new DemoIssue(
                title, description, DONE, priority, labels, assignee,
                createdDaysAgo, completedDaysAgo, null
        );
    }

    private static DemoIssue mobileDone(
            String title,
            String description,
            IssuePriority priority,
            List<String> labels,
            String assignee,
            int createdDaysAgo,
            int completedDaysAgo
    ) {
        return new DemoIssue(
                title, description, M_DONE, priority, labels, assignee,
                createdDaysAgo, completedDaysAgo, null
        );
    }

    private static DemoIssue open(
            String title,
            String description,
            int stateIndex,
            IssuePriority priority,
            List<String> labels,
            String assignee,
            int createdDaysAgo,
            Integer dueInDays
    ) {
        return new DemoIssue(
                title, description, stateIndex, priority, labels, assignee,
                createdDaysAgo, null, dueInDays
        );
    }
}
