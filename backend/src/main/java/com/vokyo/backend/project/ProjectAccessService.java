package com.vokyo.backend.project;

import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.MembershipStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectAccessService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository
    ) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    public List<Project> listAccessibleProjects(CurrentWorkspaceContext context) {
        return projectMemberRepository.findAccessibleProjectMemberships(
                        context.workspace().getId(),
                        context.user().getId(),
                        MembershipStatus.ACTIVE
                )
                .stream()
                .map(ProjectMember::getProject)
                .toList();
    }

    public Project requireAccessibleProject(UUID projectId, CurrentWorkspaceContext context) {
        return requireActiveProjectMember(projectId, context).getProject();
    }

    public Project requireOwnedProject(UUID projectId, CurrentWorkspaceContext context) {
        ProjectMember member = requireActiveProjectMember(projectId, context);
        if (member.getRole() != ProjectRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project owner role is required");
        }

        return member.getProject();
    }

    public void requireIssueProjectAccess(Issue issue, CurrentWorkspaceContext context) {
        if (!hasActiveProjectMembership(issue.getProject().getId(), context)) {
            throw notFound("Issue not found");
        }
    }

    public ProjectMember createOwnerMembership(Project project, User user) {
        return projectMemberRepository.save(new ProjectMember(
                project.getWorkspace(),
                project,
                user,
                ProjectRole.OWNER
        ));
    }

    public boolean hasProjectMembership(Project project, User user) {
        return projectMemberRepository.existsByProject_IdAndUser_Id(project.getId(), user.getId());
    }

    public ProjectMember createMemberMembership(Project project, User user) {
        return projectMemberRepository.save(new ProjectMember(
                project.getWorkspace(),
                project,
                user,
                ProjectRole.MEMBER
        ));
    }

    public List<ProjectMember> listProjectMembers(Project project) {
        return projectMemberRepository.findByProjectForMemberList(
                project.getWorkspace().getId(),
                project.getId()
        );
    }

    public User requireActiveProjectMemberUser(Project project, UUID userId) {
        return projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
                        project.getWorkspace().getId(),
                        project.getId(),
                        userId,
                        MembershipStatus.ACTIVE
                )
                .map(ProjectMember::getUser)
                .orElseThrow(() -> notFound("Project member not found"));
    }

    private ProjectMember requireActiveProjectMember(UUID projectId, CurrentWorkspaceContext context) {
        Project project = projectRepository.findByIdAndWorkspace_Id(projectId, context.workspace().getId())
                .orElseThrow(() -> notFound("Project not found"));

        return projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
                        context.workspace().getId(),
                        project.getId(),
                        context.user().getId(),
                        MembershipStatus.ACTIVE
                )
                .orElseThrow(() -> notFound("Project not found"));
    }

    private boolean hasActiveProjectMembership(UUID projectId, CurrentWorkspaceContext context) {
        return projectMemberRepository.existsByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
                context.workspace().getId(),
                projectId,
                context.user().getId(),
                MembershipStatus.ACTIVE
        );
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
