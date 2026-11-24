package ru.bcmanager.service;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.bcmanager.domain.User;
import ru.bcmanager.repository.UserRepository;

/**
 * Контекст рабочего пространства текущего пользователя.
 *
 * <p>Пользователи с {@code personalWorkspace == true} работают в собственном
 * изолированном пространстве ({@code owner_id = user.id}). Штатные учётные записи
 * демонстрационного бизнес-центра ({@code admin}, {@code manager}) и тестовые
 * вызовы без контекста работают с общими данными ({@code owner_id IS NULL}).
 */
@Service
public class WorkspaceContextService {

    private final UserRepository userRepository;

    /**
     * Создать сервис контекста рабочего пространства.
     *
     * @param userRepository репозиторий пользователей
     */
    public WorkspaceContextService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Текущий аутентифицированный пользователь, если запрос авторизован.
     *
     * @return пользователь из БД или пусто
     */
    public Optional<User> currentAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String username = authentication.getName();
        if (username == null || username.isBlank() || "anonymousUser".equals(username)) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username);
    }

    /**
     * Владелец данных для текущего запроса ({@code null} для общего пространства).
     *
     * @return сущность {@link User} или {@code null}
     */
    public User effectiveOwner() {
        return currentAuthenticatedUser()
                .filter(User::isPersonalWorkspace)
                .orElse(null);
    }

    /**
     * Идентификатор владельца данных ({@code null} для общего пространства).
     *
     * @return ID владельца или {@code null}
     */
    public Long effectiveOwnerId() {
        User owner = effectiveOwner();
        return owner == null ? null : owner.getId();
    }
}
