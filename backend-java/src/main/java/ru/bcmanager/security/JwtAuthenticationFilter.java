package ru.bcmanager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.bcmanager.domain.User;
import ru.bcmanager.repository.UserRepository;

/**
 * Фильтр, восстанавливающий аутентификацию из заголовка
 * {@code Authorization: Bearer <JWT>} (Plan.md, раздел 5.1).
 *
 * <p>Если токен валиден, пользователь существует в БД и его пароль не менялся
 * после выпуска токена, в {@link SecurityContextHolder} помещается
 * аутентификация с актуальной ролью из БД ({@code ROLE_ADMIN} или
 * {@code ROLE_MANAGER}). Некорректный, просроченный или отозванный токен
 * игнорируется — дальнейшее решение принимают правила безопасности (возврат
 * {@code 401}).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Префикс схемы авторизации. */
    private static final String BEARER_PREFIX = "Bearer ";

    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtService jwtService;

    private final UserRepository userRepository;

    /**
     * Создать фильтр JWT.
     *
     * @param jwtService     сервис разбора токенов
     * @param userRepository репозиторий пользователей
     */
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null
                && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (jwtService.isValid(token)) {
                String username = jwtService.extractUsername(token);
                User user = username == null
                        ? null
                        : userRepository.findByUsername(username).orElse(null);
                if (user != null && jwtService.matchesPasswordHash(token, user.getPasswordHash())) {
                    List<SimpleGrantedAuthority> authorities = List.of(
                            new SimpleGrantedAuthority(ROLE_PREFIX + user.getRole().name()));

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    user.getUsername(), null, authorities);
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
