package uz.inha.tickets.service;

import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.inha.tickets.domain.UserAccount;

@Service
public class JwtService {

    private static final Logger LOG = LoggerFactory.getLogger(JwtService.class);

    @Value("${app.jwt.issuer}")
    String issuer;

    @Value("${app.jwt.access-ttl-seconds:1800}")
    long ttl;

    @Value("${app.jwt.rsa-public-key:}")
    String publicKeyPemOrPath;

    @Value("${app.jwt.rsa-private-key:}")
    String privateKeyPemOrPath;

    @Value("${app.jwt.rsa-public-key-path:}")
    String publicKeyPath;

    @Value("${app.jwt.rsa-private-key-path:}")
    String privateKeyPath;

    PrivateKey privateKey;
    PublicKey publicKey;

    @PostConstruct
    void init() {
        try {
            String priv = firstNonBlank(
                readIfPath(privateKeyPath),
                readIfPath(privateKeyPemOrPath),
                privateKeyPemOrPath
            );
            String pub = firstNonBlank(readIfPath(publicKeyPath), readIfPath(publicKeyPemOrPath), publicKeyPemOrPath);
            if (priv != null && pub != null) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pemBytes(priv)));
                publicKey = kf.generatePublic(new X509EncodedKeySpec(pemBytes(pub)));
                LOG.info("JWT RS256 keypair loaded from configured PEM source");
            } else {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair pair = gen.generateKeyPair();
                privateKey = pair.getPrivate();
                publicKey = pair.getPublic();
                // SECURITY: every restart invalidates all outstanding access tokens because
                // the public verification key changes. Acceptable for local development
                // only — production MUST mount a real keypair via JWT_RSA_*_KEY_PATH (see
                // README §Environment variables).
                LOG.warn(
                    "JWT RS256 keys not configured (JWT_RSA_*_KEY[_PATH] empty) — "
                        + "generated ephemeral 2048-bit keypair. Outstanding access tokens "
                        + "will be invalidated on restart. Do not use in production."
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize RS256 JWT keys", e);
        }
    }

    public String access(UserAccount u) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(u.email)
            .issuer(issuer)
            .claim("uid", u.id.toString())
            .claim("role", u.role.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttl)))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }

    public String subject(String token) {
        return Jwts.parser()
            .verifyWith(publicKey)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject();
    }

    public long accessTtlSeconds() {
        return ttl;
    }

    private static String readIfPath(String value) {
        try {
            if (value == null || value.isBlank()) return null;
            Path p = Path.of(value);
            return Files.exists(p) ? Files.readString(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static byte[] pemBytes(String pem) {
        String body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
