package com.officemanipulator.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Small HS256-only codec. Tokens never contain provider credentials. */
public final class OfficeTokens {
    private final ObjectMapper json;
    private final byte[] secret;
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    public OfficeTokens(ObjectMapper json,String secret) {
        if(secret==null || secret.length()<32) throw new IllegalArgumentException("OFFICE_JWT_SECRET must contain at least 32 characters");
        this.json=json;this.secret=secret.getBytes(StandardCharsets.UTF_8);
    }
    private byte[] mac(String value) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    }
    public String sign(Object payload) {
        try {
            String input=ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.US_ASCII))+"."+ENCODER.encodeToString(json.writeValueAsBytes(payload));
            return input+"."+ENCODER.encodeToString(mac(input));
        } catch(Exception e) {throw new IllegalStateException("Cannot sign editor request",e);}
    }
    public JsonNode verify(String token) {
        try {
            if(token==null || token.length()>131072) throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=3) throw new IllegalArgumentException();
            JsonNode header=json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(!"HS256".equals(header.path("alg").asText()) || header.has("crit")) throw new IllegalArgumentException();
            if(!MessageDigest.isEqual(mac(parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalArgumentException();
            JsonNode claims=json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if(!claims.isObject() || (claims.has("exp") && (!claims.get("exp").isIntegralNumber() || claims.get("exp").asLong()<=Instant.now().getEpochSecond()))) throw new IllegalArgumentException();
            if(claims.has("nbf") && claims.get("nbf").asLong()>Instant.now().getEpochSecond()) throw new IllegalArgumentException();
            return claims;
        } catch(Exception e) {throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Geçersiz veya süresi dolmuş editör yetkisi");}
    }
    public String ticket(UUID id,String purpose,Instant expiry) {return sign(Map.of("sid",id.toString(),"purpose",purpose,"exp",expiry.getEpochSecond()));}
    public void requireTicket(String ticket,UUID id,String purpose) {
        JsonNode claims=verify(ticket);
        if(!id.toString().equals(claims.path("sid").asText()) || !purpose.equals(claims.path("purpose").asText()) || !claims.has("exp"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Bu işlem için yetki yok");
    }
    public JsonNode callback(String authorization,JsonNode body,String key) {
        JsonNode claims;
        if(authorization!=null && authorization.startsWith("Bearer ")) claims=verify(authorization.substring(7)).path("payload");
        else claims=verify(body.path("token").asText(null));
        if(!claims.isObject() || !key.equals(claims.path("key").asText()) || !claims.path("status").isInt())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Belge imzası eşleşmiyor");
        return claims;
    }
}
