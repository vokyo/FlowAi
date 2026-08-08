package com.vokyo.backend.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.breakdown.IssueBreakdownPromptFactory;
import com.vokyo.backend.ai.breakdown.IssueBreakdownResult;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionService;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.auth.AuthService;
import com.vokyo.backend.auth.AuthSessionResult;
import com.vokyo.backend.auth.dto.RegisterRequest;
import com.vokyo.backend.auth.dto.RegisterWithInvitationRequest;
import com.vokyo.backend.demo.DemoDataset.DemoBreakdownItem;
import com.vokyo.backend.demo.DemoDataset.DemoComment;
import com.vokyo.backend.demo.DemoDataset.DemoIssue;
import com.vokyo.backend.demo.DemoDataset.DemoLabel;
import com.vokyo.backend.demo.DemoDataset.DemoMember;
import com.vokyo.backend.demo.DemoDataset.DemoProject;
import com.vokyo.backend.demo.DemoDataset.DemoWorkflowState;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueCommandService;
import com.vokyo.backend.issue.IssueCreationCommand;
import com.vokyo.backend.issue.IssueCreationService;
import com.vokyo.backend.issue.dto.CreateCommentRequest;
import com.vokyo.backend.issue.dto.IssueCommentResponse;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectCommandService;
import com.vokyo.backend.project.ProjectConfigurationService;
import com.vokyo.backend.project.ProjectQueryService;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.project.dto.AddProjectMemberRequest;
import com.vokyo.backend.project.dto.CreateProjectLabelRequest;
import com.vokyo.backend.project.dto.CreateProjectRequest;
import com.vokyo.backend.project.dto.CreateProjectWorkflowStateRequest;
import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;
import com.vokyo.backend.project.dto.ReorderProjectWorkflowStatesRequest;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import com.vokyo.backend.workspace.WorkspaceInvitationService;
import com.vokyo.backend.workspace.dto.CreateWorkspaceInvitationRequest;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationCreatedResponse;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vokyo.backend.auth.EmailAddressNormalizer.normalize;

/**
 * Writes the demo workspace through the same services an HTTP request would use.
 *
 * <p>Every call here is authenticated by a real {@link Jwt}: registration hands
 * back an access token, and that token is decoded into the exact object the
 * resource server would hand a controller. Tenant scoping, role checks, project
 * membership, board placement and activity records therefore all behave as they
 * do in normal use, and the seeded rows are indistinguishable from rows a user
 * created. No repository is written to directly and no SQL is issued.
 *
 * <p>Runs inside the caller's transaction — see {@link DemoDataSeeder} — so the
 * whole dataset lands or none of it does.
 */
@Component
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "true")
public class DemoWorkspaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoWorkspaceSeeder.class);

    /** Keeps a comment on the same UTC day from sorting before its own issue. */
    private static final int ISSUE_HOUR_OFFSET = 3;

    private final AuthService authService;
    private final JwtService jwtService;
    private final WorkspaceInvitationService invitationService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectCommandService projectCommandService;
    private final ProjectConfigurationService projectConfigurationService;
    private final ProjectQueryService projectQueryService;
    private final ProjectAccessService projectAccessService;
    private final IssueCreationService issueCreationService;
    private final IssueCommandService issueCommandService;
    private final AiSuggestionService aiSuggestionService;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public DemoWorkspaceSeeder(
            AuthService authService,
            JwtService jwtService,
            WorkspaceInvitationService invitationService,
            WorkspaceAccessService workspaceAccessService,
            ProjectCommandService projectCommandService,
            ProjectConfigurationService projectConfigurationService,
            ProjectQueryService projectQueryService,
            ProjectAccessService projectAccessService,
            IssueCreationService issueCreationService,
            IssueCommandService issueCommandService,
            AiSuggestionService aiSuggestionService,
            ObjectMapper objectMapper,
            EntityManager entityManager
    ) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.invitationService = invitationService;
        this.workspaceAccessService = workspaceAccessService;
        this.projectCommandService = projectCommandService;
        this.projectConfigurationService = projectConfigurationService;
        this.projectQueryService = projectQueryService;
        this.projectAccessService = projectAccessService;
        this.issueCreationService = issueCreationService;
        this.issueCommandService = issueCommandService;
        this.aiSuggestionService = aiSuggestionService;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    public DemoSeedSummary seed(DemoSeedProperties properties) {
        Instant now = Instant.now();
        Seat owner = registerOwner(properties);
        Map<String, Seat> seats = new LinkedHashMap<>();
        seats.put(DemoDataset.DEMO, owner);
        seats.putAll(inviteTeam(properties, owner));

        Map<String, Issue> issuesByTitle = new LinkedHashMap<>();
        List<ProjectResponse> projects = new ArrayList<>();
        Backdating backdating = new Backdating();
        int issueCount = 0;

        for (DemoProject spec : DemoDataset.projects()) {
            ProjectResponse project = projectCommandService.createProject(
                    owner.jwt(),
                    new CreateProjectRequest(spec.name(), spec.description())
            );
            projects.add(project);

            addTeamToProject(owner, seats, project.id());
            List<ProjectWorkflowStateResponse> states = configureWorkflow(owner, project.id(), spec);
            Map<String, UUID> labels = createLabels(owner, project.id(), spec.labels());

            issueCount += createIssues(
                    seats, project.id(), spec, states, labels, now, issuesByTitle, backdating
            );
        }

        int commentCount = createComments(seats, issuesByTitle, now, backdating);
        seedSavedCopilotDraft(owner, issuesByTitle);
        backdating.apply();

        return new DemoSeedSummary(seats.size(), projects.size(), issueCount, commentCount);
    }

    // ------------------------------------------------------------------ people

    private Seat registerOwner(DemoSeedProperties properties) {
        AuthSessionResult session = authService.register(new RegisterRequest(
                properties.email(),
                properties.password(),
                DemoDataset.DEMO_DISPLAY_NAME,
                properties.workspaceName()
        ));
        return seatFrom(session);
    }

    /**
     * Teammates join the way a real teammate does: the owner issues an invitation,
     * and registering against that token creates the user and the membership in
     * one step. Registering them directly would give each of them a workspace of
     * their own and leave the demo workspace with a single member.
     */
    private Map<String, Seat> inviteTeam(DemoSeedProperties properties, Seat owner) {
        Map<String, Seat> seats = new LinkedHashMap<>();
        String ownerEmail = normalize(properties.email());

        for (DemoMember member : DemoDataset.members()) {
            if (normalize(member.email()).equals(ownerEmail)) {
                log.warn(
                        "Demo teammate {} collides with the demo account email; skipping",
                        member.email()
                );
                continue;
            }

            WorkspaceInvitationCreatedResponse invitation = invitationService.createInvitation(
                    owner.jwt(),
                    new CreateWorkspaceInvitationRequest(member.email(), member.role())
            );
            AuthSessionResult session = invitationService.registerWithInvitation(
                    new RegisterWithInvitationRequest(
                            invitation.token(),
                            member.email(),
                            member.displayName(),
                            properties.password()
                    )
            );
            seats.put(member.key(), seatFrom(session));
        }

        return seats;
    }

    private Seat seatFrom(AuthSessionResult session) {
        Jwt jwt = jwtService.decode(session.response().accessToken());
        return new Seat(jwt, workspaceAccessService.requireCurrentContext(jwt));
    }

    // ---------------------------------------------------------------- projects

    private void addTeamToProject(Seat owner, Map<String, Seat> seats, UUID projectId) {
        seats.forEach((key, seat) -> {
            if (DemoDataset.DEMO.equals(key)) {
                return;
            }
            projectConfigurationService.addProjectMember(
                    owner.jwt(),
                    projectId,
                    new AddProjectMemberRequest(seat.userId(), ProjectRole.MEMBER)
            );
        });
    }

    /**
     * A new project starts with Todo, In progress and Done. The extra states are
     * appended and the whole set is then reordered into the board order the demo
     * wants, because appended states land next to Done rather than where they
     * belong in the flow.
     */
    private List<ProjectWorkflowStateResponse> configureWorkflow(
            Seat owner,
            UUID projectId,
            DemoProject spec
    ) {
        for (DemoWorkflowState state : spec.extraStates()) {
            projectConfigurationService.createProjectWorkflowState(
                    owner.jwt(),
                    projectId,
                    new CreateProjectWorkflowStateRequest(state.name(), state.category())
            );
        }

        Map<String, UUID> byName = new LinkedHashMap<>();
        projectQueryService.listProjectWorkflowStates(owner.jwt(), projectId)
                .forEach(state -> byName.put(state.name(), state.id()));

        List<UUID> order = spec.stateOrder().stream()
                .map(name -> requireState(byName, name, spec.name()))
                .toList();

        return projectConfigurationService.reorderProjectWorkflowStates(
                owner.jwt(),
                projectId,
                new ReorderProjectWorkflowStatesRequest(order)
        );
    }

    private UUID requireState(Map<String, UUID> byName, String name, String projectName) {
        UUID id = byName.get(name);
        if (id == null) {
            throw new IllegalStateException(
                    "Demo project '" + projectName + "' has no workflow state named " + name
            );
        }
        return id;
    }

    private Map<String, UUID> createLabels(Seat owner, UUID projectId, List<DemoLabel> labels) {
        Map<String, UUID> byName = new LinkedHashMap<>();
        for (DemoLabel label : labels) {
            ProjectLabelResponse created = projectConfigurationService.createProjectLabel(
                    owner.jwt(),
                    projectId,
                    new CreateProjectLabelRequest(label.name(), label.color())
            );
            byName.put(created.name(), created.id());
        }
        return byName;
    }

    // ------------------------------------------------------------------ issues

    private int createIssues(
            Map<String, Seat> seats,
            UUID projectId,
            DemoProject spec,
            List<ProjectWorkflowStateResponse> states,
            Map<String, UUID> labels,
            Instant now,
            Map<String, Issue> issuesByTitle,
            Backdating backdating
    ) {
        for (DemoIssue issueSpec : spec.issues()) {
            Seat author = authorFor(seats, issueSpec.assignee());
            Project project = projectAccessService.requireAccessibleProjectForUpdate(
                    projectId,
                    author.context()
            );

            Issue issue = issueCreationService.create(
                    author.context(),
                    project,
                    new IssueCreationCommand(
                            issueSpec.title(),
                            issueSpec.description(),
                            labelIds(labels, issueSpec.labels()),
                            issueSpec.assignee() == null
                                    ? null
                                    : seats.get(issueSpec.assignee()).userId(),
                            states.get(issueSpec.stateIndex()).id(),
                            null,
                            issueSpec.priority(),
                            dueDate(issueSpec.dueInDays())
                    )
            );
            issuesByTitle.put(issueSpec.title(), issue);
            backdating.issue(
                    issue.getId(),
                    createdInstant(issueSpec, now),
                    completedInstant(issueSpec, now)
            );
        }

        return spec.issues().size();
    }

    /**
     * Issues are opened by the person who owns them, so the activity feed and the
     * creator column carry more than one name. Unassigned work falls to the demo
     * account, which is what an unowned backlog item looks like anyway.
     */
    private Seat authorFor(Map<String, Seat> seats, String assignee) {
        Seat seat = assignee == null ? null : seats.get(assignee);
        return seat == null ? seats.get(DemoDataset.DEMO) : seat;
    }

    private Instant createdInstant(DemoIssue spec, Instant now) {
        return daysBefore(now, spec.createdDaysAgo()).minus(ISSUE_HOUR_OFFSET, ChronoUnit.HOURS);
    }

    private Instant completedInstant(DemoIssue spec, Instant now) {
        return spec.completedDaysAgo() == null
                ? null
                : onPrecedingWorkday(daysBefore(now, spec.completedDaysAgo()));
    }

    /**
     * Moves a completion off the weekend and onto the Friday before it.
     *
     * <p>The dataset counts days back from whenever seeding runs, so which of them
     * land on a weekend is not knowable in advance. Without this the completion
     * trend shows a team that ships steadily through every Saturday, which is the
     * kind of detail that makes a demo read as generated. Shifting backwards also
     * keeps a completion after the issue that carries it, since every pair in the
     * dataset is at least five days apart.
     */
    private Instant onPrecedingWorkday(Instant instant) {
        int shift = switch (instant.atZone(ZoneOffset.UTC).getDayOfWeek()) {
            case SATURDAY -> 1;
            case SUNDAY -> 2;
            default -> 0;
        };
        return instant.minus(shift, ChronoUnit.DAYS);
    }

    private List<UUID> labelIds(Map<String, UUID> labels, List<String> names) {
        return names.stream()
                .map(name -> {
                    UUID id = labels.get(name);
                    if (id == null) {
                        throw new IllegalStateException("Demo project has no label named " + name);
                    }
                    return id;
                })
                .toList();
    }

    private LocalDate dueDate(Integer dueInDays) {
        return dueInDays == null ? null : LocalDate.now(ZoneOffset.UTC).plusDays(dueInDays);
    }

    // ---------------------------------------------------------------- comments

    private int createComments(
            Map<String, Seat> seats,
            Map<String, Issue> issuesByTitle,
            Instant now,
            Backdating backdating
    ) {
        int count = 0;
        for (DemoComment comment : DemoDataset.comments()) {
            Issue issue = issuesByTitle.get(comment.issueTitle());
            if (issue == null) {
                throw new IllegalStateException(
                        "Demo comment references an unknown issue: " + comment.issueTitle()
                );
            }

            Seat author = authorFor(seats, comment.author());
            IssueCommentResponse created = issueCommandService.createComment(
                    author.jwt(),
                    issue.getId(),
                    new CreateCommentRequest(comment.body())
            );
            backdating.comment(created.id(), daysBefore(now, comment.daysAgo()));
            count++;
        }
        return count;
    }

    // ----------------------------------------------------------- saved AI draft

    /**
     * Stores a Copilot breakdown as if it had already been generated. The demo
     * runs with the provider switched off — an open demo sharing one account would
     * put every visitor on the same rate-limit bucket and the same bill — so the
     * draft is written here rather than requested, and the review-and-apply flow
     * still works end to end because Apply only ever reads the stored draft.
     */
    private void seedSavedCopilotDraft(Seat owner, Map<String, Issue> issuesByTitle) {
        Issue sourceIssue = issuesByTitle.get(DemoDataset.COPILOT_SOURCE_ISSUE);
        if (sourceIssue == null) {
            throw new IllegalStateException(
                    "Demo AI suggestion references an unknown issue: "
                            + DemoDataset.COPILOT_SOURCE_ISSUE
            );
        }

        Project project = sourceIssue.getProject();
        Map<String, UUID> labels = new LinkedHashMap<>();
        projectQueryService.listProjectLabels(owner.jwt(), project.getId())
                .forEach(label -> labels.put(label.name(), label.id()));
        Map<String, UUID> members = new LinkedHashMap<>();
        projectQueryService.listProjectMembers(owner.jwt(), project.getId())
                .forEach(member -> members.put(normalize(member.email()), member.userId()));

        List<IssueBreakdownResult.Item> items = DemoDataset.breakdownItems().stream()
                .map(item -> toResultItem(item, labels, members))
                .toList();

        AiSuggestion suggestion = aiSuggestionService.createDraft(
                new AiSuggestionService.CreateDraftCommand(
                        owner.context(),
                        project,
                        sourceIssue,
                        AiSuggestionType.ISSUE_BREAKDOWN,
                        objectMapper.valueToTree(new IssueBreakdownResult(
                                DemoDataset.breakdownOverview(),
                                items,
                                List.of()
                        )),
                        IssueBreakdownPromptFactory.VERSION,
                        "demo-seed",
                        null,
                        "demo-seed:" + IssueBreakdownPromptFactory.VERSION + ":" + sourceIssue.getId(),
                        null,
                        null
                )
        );

        log.info(
                "Seeded AI Copilot draft {}. With AI disabled the Copilot button stays"
                        + " greyed out, so open the draft directly at"
                        + " /app/workspaces/{}/projects/{}/issues/{}"
                        + "?copilot=breakdown&aiSuggestionType=breakdown&aiSuggestion={}",
                suggestion.getId(),
                project.getWorkspace().getId(),
                project.getId(),
                sourceIssue.getId(),
                suggestion.getId()
        );
    }

    private IssueBreakdownResult.Item toResultItem(
            DemoBreakdownItem item,
            Map<String, UUID> labels,
            Map<String, UUID> members
    ) {
        return new IssueBreakdownResult.Item(
                item.clientItemId(),
                item.title(),
                item.description(),
                item.priority(),
                item.acceptanceCriteria(),
                labelIds(labels, item.labels()),
                suggestedAssignee(item.assignee(), members),
                null,
                List.of()
        );
    }

    private UUID suggestedAssignee(String memberKey, Map<String, UUID> members) {
        if (memberKey == null) {
            return null;
        }
        return DemoDataset.members().stream()
                .filter(member -> member.key().equals(memberKey))
                .map(member -> members.get(normalize(member.email())))
                .findFirst()
                .orElse(null);
    }

    // ----------------------------------------------------------------- helpers

    private Instant daysBefore(Instant now, int days) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    /**
     * One seeded user's session: the token the services authenticate against, and
     * the workspace context resolved from it.
     */
    private record Seat(Jwt jwt, CurrentWorkspaceContext context) {

        UUID userId() {
            return context.user().getId();
        }
    }

    /**
     * Restates when the seeded rows happened, once every service call is done.
     *
     * <p>This is the one place the seeder leaves the service layer, and it is a
     * deliberate trade. The entities stamp {@code created_at} and
     * {@code completed_at} from the wall clock in {@code @PrePersist}, with no
     * seam to pass another instant through. The alternatives were to widen the
     * issue and activity services with a timestamp parameter that only this class
     * would ever pass, or to accept a completion trend that collapses onto the
     * seeding day. Neither is worth it for a demo dataset, so the write goes
     * straight to the columns instead.
     *
     * <p>What that costs, stated plainly: these statements name columns that
     * Hibernate would otherwise keep in step with the entities, so a rename in a
     * future migration compiles clean and fails at runtime. {@code
     * DemoSeedIntegrationTests} is what catches that — it asserts the spread and
     * the completion count against a freshly migrated database, so a drifted
     * column turns into a red build rather than a flat chart in production.
     *
     * <p>Everything else — tenant scoping, validation, board placement, activity
     * records, the labels join — still comes from the services. Only these three
     * timestamp columns are written directly, and the rows are otherwise exactly
     * what a real user's requests would have produced.
     */
    private final class Backdating {

        private final Map<UUID, Instant[]> issues = new LinkedHashMap<>();
        private final Map<UUID, Instant> comments = new LinkedHashMap<>();

        void issue(UUID issueId, Instant createdAt, Instant completedAt) {
            issues.put(issueId, new Instant[]{createdAt, completedAt});
        }

        void comment(UUID commentId, Instant createdAt) {
            comments.put(commentId, createdAt);
        }

        /**
         * Runs after every service call, so nothing downstream re-stamps a row we
         * have already moved. The activity entries follow the thing they describe,
         * or the issue timeline would read as one burst on the seeding day.
         */
        void apply() {
            entityManager.flush();

            issues.forEach((issueId, timestamps) -> {
                entityManager.createNativeQuery("""
                                update issues
                                set created_at = :createdAt, completed_at = :completedAt
                                where id = :issueId
                                """)
                        .setParameter("createdAt", timestamps[0])
                        .setParameter("completedAt", timestamps[1])
                        .setParameter("issueId", issueId)
                        .executeUpdate();
                entityManager.createNativeQuery("""
                                update activity_events
                                set created_at = :createdAt
                                where issue_id = :issueId and event_type = 'ISSUE_CREATED'
                                """)
                        .setParameter("createdAt", timestamps[0])
                        .setParameter("issueId", issueId)
                        .executeUpdate();
            });

            comments.forEach((commentId, createdAt) -> {
                entityManager.createNativeQuery("""
                                update issue_comments
                                set created_at = :createdAt
                                where id = :commentId
                                """)
                        .setParameter("createdAt", createdAt)
                        .setParameter("commentId", commentId)
                        .executeUpdate();
                entityManager.createNativeQuery("""
                                update activity_events
                                set created_at = :createdAt
                                where event_type = 'COMMENT_CREATED'
                                  and metadata ->> 'commentId' = :commentId
                                """)
                        .setParameter("createdAt", createdAt)
                        .setParameter("commentId", commentId.toString())
                        .executeUpdate();
            });

            // The rows on disk no longer match what the session holds.
            entityManager.clear();
        }
    }
}
