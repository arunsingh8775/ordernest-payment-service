package com.ordernest.payment.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final JwtDecoder jwksDecoder;

    public JwtService(
            @Value("${app.jwt.jwks-url:}") String jwksUrl,
            @Value("${app.jwt.secret:change-me-in-prod-change-me-in-prod-change-me}") String secret
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwksDecoder = (jwksUrl == null || jwksUrl.isBlank()) ? null : NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
    }

    public String extractEmail(String token) {
        Jwt jwkJwt = decodeWithJwks(token);
        if (jwkJwt != null) {
            String email = jwkJwt.getClaimAsString("email");
            return (email == null || email.isBlank()) ? jwkJwt.getSubject() : email;
        }
        return extractAllClaims(token).getSubject();
    }

    public boolean isTokenValid(String token) {
        Jwt jwkJwt = decodeWithJwks(token);
        if (jwkJwt != null) {
            Instant expiration = jwkJwt.getExpiresAt();
            return expiration != null && expiration.isAfter(Instant.now());
        }
        Claims claims = extractAllClaims(token);
        Date expiration = claims.getExpiration();
        return expiration != null && expiration.after(new Date());
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Jwt decodeWithJwks(String token) {
        if (jwksDecoder == null) {
            return null;
        }
        try {
            return jwksDecoder.decode(token);
        } catch (Exception ex) {
            return null;
        }
    }
}
