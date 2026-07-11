package com.vokyo.backend.workspace;

import com.vokyo.backend.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkspaceProvisioningService {

    private static final int SLUG_MAX_LENGTH = 120;
    private static final int RANDOM_SUFFIX_LENGTH = 8;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public WorkspaceProvisioningService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMembershipRepository membershipRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public WorkspaceMembership createOwnedWorkspace(User owner, String name) {
        String normalizedName = name.trim();
        Workspace workspace = workspaceRepository.save(new Workspace(
                owner,
                normalizedName,
                generateUniqueSlug(normalizedName)
        ));

        return membershipRepository.save(new WorkspaceMembership(
                workspace,
                owner,
                WorkspaceRole.OWNER
        ));
    }

    private String generateUniqueSlug(String workspaceName) {
        String baseSlug = truncate(slugify(workspaceName), SLUG_MAX_LENGTH);
        String slug = baseSlug;

        while (workspaceRepository.existsBySlug(slug)) {
            String suffix = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, RANDOM_SUFFIX_LENGTH);
            slug = truncate(baseSlug, SLUG_MAX_LENGTH - RANDOM_SUFFIX_LENGTH - 1) + "-" + suffix;
        }

        return slug;
    }

    private String slugify(String value) {
        String slug = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        return slug.isBlank() ? "workspace" : slug;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength).replaceAll("-$", "");
    }
}
