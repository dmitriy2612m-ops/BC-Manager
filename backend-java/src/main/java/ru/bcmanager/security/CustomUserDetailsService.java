package ru.bcmanager.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.domain.User;
import ru.bcmanager.repository.UserRepository;

/**
 * Загрузка учётных записей для {@code DaoAuthenticationProvider}
 * (Plan.md, раздел 5.1).
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Создать сервис загрузки пользователей.
     *
     * @param userRepository репозиторий пользователей
     */
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Пользователь " + username + " не найден"));
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .build();
    }
}
