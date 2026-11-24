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

    /** Претензия с отпечатком хэша пароля для отзыва старых токенов после смены пароля. */
    public static final String CLAIM_PWD_VER = "pwdVer";

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
                .claim(CLAIM_PWD_VER, passwordFingerprint(user.getPasswordHash()))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Извлечь отпечаток версии пароля из токена.
     *
     * @param token JWT
     * @return отпечаток пароля или {@code null}
     */
    public String extractPasswordVersion(String token) {
        return extractClaim(token, claims -> claims.get(CLAIM_PWD_VER, String.class));
    }

    /**
     * Проверить, соответствует ли токен текущему хэшу пароля пользователя.
     *
     * @param token        JWT
     * @param passwordHash текущий BCrypt-хэш пользователя в БД
     * @return {@code true}, если пароль не менялся с момента выпуска токена
     */
    public boolean matchesPasswordHash(String token, String passwordHash) {
        String tokenVer = extractPasswordVersion(token);
        return tokenVer != null && tokenVer.equals(passwordFingerprint(passwordHash));
    }

    private static String passwordFingerprint(String passwordHash) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (passwordHash == null ? "" : passwordHash).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
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
