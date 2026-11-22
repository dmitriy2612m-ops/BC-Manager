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

/**
 * Фильтр, восстанавливающий аутентификацию из заголовка
 * {@code Authorization: Bearer <JWT>} (Plan.md, раздел 5.1).
 *
 * <p>Если токен валиден, в {@link SecurityContextHolder} помещается
 * аутентификация с ролью {@code ROLE_ADMIN} или {@code ROLE_MANAGER}.
 * Некорректный или просроченный токен просто игнорируется — дальнейшее
 * решение принимают правила безопасности (возврат {@code 401}).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Префикс схемы авторизации. */
    private static final String BEARER_PREFIX = "Bearer ";

    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtService jwtService;

    /**
     * Создать фильтр JWT.
     *
     * @param jwtService сервис разбора токенов
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
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
                String role = jwtService.extractRole(token);
                List<SimpleGrantedAuthority> authorities = role == null
                        ? List.of()
                        : List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role));

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
