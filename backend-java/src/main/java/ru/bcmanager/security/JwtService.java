package ru.bcmanager.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.bcmanager.domain.User;

/**
 * Генерация и разбор JWT-токенов (Plan.md, раздел 5.1).
 *
 * <p>Токен подписывается HMAC-SHA ключом из свойства {@code bcmanager.jwt.secret}
 * (переопределяется переменной окружения {@code JWT_SECRET}). В полезной
 * нагрузке хранятся логин, роль и ФИО пользователя, поэтому фильтр
 * аутентификации не обращается к БД на каждом запросе.
 */
@Service
public class JwtService {

    /** Претензия с ролью пользователя. */
    public static final String CLAIM_ROLE = "role";

    /** Претензия с ФИО пользователя. */
    public static final String CLAIM_FULL_NAME = "fullName";

    private final SecretKey signingKey;

    private final long expirationMinutes;

    /**
     * Создать сервис JWT.
     *
     * @param secret            секрет подписи (не короче 32 байт)
     * @param expirationMinutes срок жизни токена в минутах
     */
    public JwtService(
            @Value("${bcmanager.jwt.secret}") String secret,
            @Value("${bcmanager.jwt.expiration-minutes:480}") long expirationMinutes) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "bcmanager.jwt.secret должен содержать не менее 32 байт для HMAC-SHA256");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMinutes = expirationMinutes;
    }

    /**
     * Выпустить токен для пользователя.
     *
     * @param user пользователь
     * @return подписанный JWT
     */
    public String generateToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(expirationMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_FULL_NAME, user.getFullName())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Извлечь логин из токена.
     *
     * @param token JWT
     * @return логин пользователя
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Извлечь роль из токена.
     *
     * @param token JWT
     * @return роль доступа
     */
    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get(CLAIM_ROLE, String.class));
    }

    /**
     * Извлечь ФИО из токена.
     *
     * @param token JWT
     * @return ФИО сотрудника
     */
    public String extractFullName(String token) {
        return extractClaim(token, claims -> claims.get(CLAIM_FULL_NAME, String.class));
    }

    /**
     * Проверить подпись и срок действия токена.
     *
     * @param token JWT
     * @return {@code true}, если токен действителен
     */
    public boolean isValid(String token) {
        try {
            Claims claims = parse(token);
            return claims.getExpiration() == null
                    || claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Время жизни токена в минутах.
     *
     * @return срок действия в минутах
     */
    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(parse(token));
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
