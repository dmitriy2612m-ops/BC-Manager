package ru.bcmanager.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.bcmanager.security.CustomUserDetailsService;
import ru.bcmanager.security.JwtAuthenticationFilter;

/**
 * Конфигурация Spring Security: stateless-аутентификация по JWT и разделение
 * прав {@code ADMIN} / {@code MANAGER} (Plan.md, разделы 5.1 и 5.2).
 *
 * <p>Публичные пути: корень и статика веб-интерфейса, вход в систему,
 * Swagger UI и описание OpenAPI. Изменение реестра помещений доступно только
 * управляющему, остальные операции — любой авторизованной роли.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Пути, не требующие аутентификации. */
    private static final String[] PUBLIC_PATHS = {
        "/",
        "/index.html",
        "/guide.html",
        "/architecture.html",
        "/favicon.ico",
        "/css/**",
        "/js/**",
        "/assets/**",
        "/api/v1/auth/login",
        "/api/v1/auth/register",
        "/error"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final CustomUserDetailsService userDetailsService;

    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    private final RestAccessDeniedHandler restAccessDeniedHandler;

    /**
     * Создать конфигурацию безопасности.
     *
     * @param jwtAuthenticationFilter      фильтр JWT
     * @param userDetailsService           загрузчик пользователей
     * @param restAuthenticationEntryPoint обработчик неавторизованных запросов (401)
     * @param restAccessDeniedHandler      обработчик недостатка прав (403)
     */
    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CustomUserDetailsService userDetailsService,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
        this.restAccessDeniedHandler = restAccessDeniedHandler;
    }

    /**
     * Цепочка фильтров безопасности.
     *
     * @param http конфигуратор HTTP-безопасности
     * @return цепочка фильтров
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Реестр помещений: создание, изменение и удаление — только ADMIN.
                        .requestMatchers(HttpMethod.POST, "/api/v1/premises").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/premises/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/premises/*").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Провайдер аутентификации по логину и паролю.
     *
     * @return провайдер с BCrypt-проверкой пароля
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * Менеджер аутентификации для контроллера входа.
     *
     * @return менеджер аутентификации
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        return new org.springframework.security.authentication.ProviderManager(
                authenticationProvider());
    }

    /**
     * Кодировщик паролей BCrypt.
     *
     * @return BCrypt-кодировщик
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS для локальной разработки веб-клиента.
     *
     * @return источник конфигурации CORS
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
