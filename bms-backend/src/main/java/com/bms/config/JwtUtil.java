package com.bms.config;

import com.bms.license.JwtSecretService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    private final JwtSecretService jwtSecretService;

    @Value("${jwt.expiration}")
    private Long jwtExpiration;

    public JwtUtil(@Autowired JwtSecretService jwtSecretService) {
        this.jwtSecretService = jwtSecretService;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSignKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String generateToken(String username) {
        return createToken(username, null);
    }

    public String generateToken(UserDetails userDetails) {
        Integer tokenVersion = null;
        if (userDetails instanceof com.bms.entity.User user) {
            tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        }
        return createToken(userDetails.getUsername(), tokenVersion);
    }

    private String createToken(String subject, Integer tokenVersion) {
        Map<String, Object> claims = new HashMap<>();
        if (tokenVersion != null) {
            claims.put("tv", tokenVersion);
        }
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        if (username == null || !username.equals(userDetails.getUsername()) || isTokenExpired(token)) {
            return false;
        }
        if (userDetails instanceof com.bms.entity.User user) {
            Integer claimVersion = extractClaim(token, claims -> claims.get("tv", Integer.class));
            Integer userVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
            if (claimVersion != null && !claimVersion.equals(userVersion)) {
                return false;
            }
        }
        return true;
    }

    private Key getSignKey() {
        try {
            byte[] keyBytes = java.util.Base64.getDecoder().decode(jwtSecretService.getSecret());
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            // Fall back to the configured secret so a broken/missing secret file never bricks the app
            byte[] keyBytes;
            try {
                keyBytes = io.jsonwebtoken.io.Decoders.BASE64.decode(jwtSecretService.getSecret());
            } catch (IllegalArgumentException ex) {
                keyBytes = jwtSecretService.getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            if (keyBytes.length < 32) {
                try {
                    keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(keyBytes);
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to create JWT signing key", ex);
                }
            }
            return Keys.hmacShaKeyFor(keyBytes);
        }
    }
}
