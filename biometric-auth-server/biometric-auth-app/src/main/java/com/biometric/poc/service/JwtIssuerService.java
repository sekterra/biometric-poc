package com.biometric.poc.service;

import com.biometric.poc.lib.model.JwtTokenPair;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 발급. PoC 에서는 대칭키(HS256) 사용.
 *
 * <p>TODO: [실서비스] {@code jwt.secret} 을 환경 변수({@code ${JWT_SECRET}}) 또는 비밀 관리 저장소로 이전하고,
 * 운영에서는 RS256·키 로테이션·aud/iss 클레임 검증을 검토할 것.
 */
@Service
public class JwtIssuerService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiry-minutes}")
    private int accessExpiryMinutes;

    @Value("${jwt.refresh-token-expiry-days}")
    private int refreshExpiryDays;

    public JwtTokenPair issueTokenPair(String userId) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();

        Date issuedAt = Date.from(now);
        Date accessExp = Date.from(now.plus(accessExpiryMinutes, ChronoUnit.MINUTES));
        Date refreshExp = Date.from(now.plus(refreshExpiryDays, ChronoUnit.DAYS));

        String accessToken =
                Jwts.builder()
                        .subject(userId)
                        .issuedAt(issuedAt)
                        .expiration(accessExp)
                        .signWith(key)
                        .compact();

        String refreshToken =
                Jwts.builder()
                        .subject(userId)
                        .expiration(refreshExp)
                        .signWith(key)
                        .compact();

        return new JwtTokenPair(accessToken, refreshToken, accessExpiryMinutes * 60);
    }
}
