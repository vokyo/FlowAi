package com.vokyo.backend.project;

import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.MembershipStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
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

    public Project requireOwnedProjectForUpdate(UUID projectId, CurrentWorkspaceContext context) {
        Project project = projectRepository.findByIdAndWorkspaceIdForUpdate(
                        projectId,
                        context.workspace().getId()
                )
                .orElseThrow(() -> notFound("Project not found"));
        ProjectMember member = projectMemberRepository
                .findByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
                        context.workspace().getId(),
                        project.getId(),
                        context.user().getId(),
                        MembershipStatus.ACTIVE
                )
                .orElseThrow(() -> notFound("Project not found"));

        if (member.getRole() != ProjectRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project owner role is required");
        }

        return project;
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

    public Optional<ProjectMember> findProjectMembership(Project project, User user) {
        return projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_Id(
                project.getWorkspace().getId(),
                project.getId(),
                user.getId()
        );
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

    public ProjectMember requireProjectMember(Project project, UUID memberId) {
        return projectMemberRepository.findByWorkspace_IdAndProject_IdAndId(
                        project.getWorkspace().getId(),
                        project.getId(),
                        memberId
                )
                .orElseThrow(() -> notFound("Project member not found"));
    }

    public ProjectMember requireActiveProjectMember(Project project, UUID memberId) {
        return projectMemberRepository.findByWorkspace_IdAndProject_IdAndIdAndStatus(
                        project.getWorkspace().getId(),
                        project.getId(),
                        memberId,
                        MembershipStatus.ACTIVE
                )
                .orElseThrow(() -> notFound("Project member not found"));
    }

    public long countActiveProjectOwners(Project project) {
        return projectMemberRepository.countByWorkspace_IdAndProject_IdAndRoleAndStatus(
                project.getWorkspace().getId(),
                project.getId(),
                ProjectRole.OWNER,
                MembershipStatus.ACTIVE
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
