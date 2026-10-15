package com.nexusgate.management.service;

import com.nexusgate.management.model.OrganizationStatus;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserStatus;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class JwtPrincipalLoader {

    private final UserRepository userRepository;

    public JwtPrincipalLoader(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<AuthenticatedUserPrincipal> load(UUID userId, UUID tokenOrganizationId) {
        return userRepository.findById(userId)
                .filter(user -> isActiveInTokenOrganization(user, tokenOrganizationId))
                .map(user -> new AuthenticatedUserPrincipal(
                        user.getId(),
                        user.getOrganization().getId(),
                        user.getRole()
                ));
    }

    private static boolean isActiveInTokenOrganization(User user, UUID tokenOrganizationId) {
        return user.getStatus() == UserStatus.ACTIVE
                && user.getOrganization().getStatus() == OrganizationStatus.ACTIVE
                && user.getOrganization().getId().equals(tokenOrganizationId);
    }
}
