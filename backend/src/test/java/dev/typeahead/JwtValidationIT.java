package dev.typeahead;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.List;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

class JwtValidationIT {
 @Test void signatureIssuerAudienceAndExpiry()throws Exception{
  var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
  var key=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey((RSAPrivateKey)pair.getPrivate()).keyID("integration-key").build();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/jwks",exchange->{byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
  try{
   var decoder=new Security().jwtDecoder("http://127.0.0.1:"+server.getAddress().getPort()+"/jwks","https://issuer.example","autocomplete-api");
   var encoder=new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
   String valid=token(encoder,"https://issuer.example","autocomplete-api",300);assertEquals("user",decoder.decode(valid).getSubject());
   assertThrows(JwtException.class,()->decoder.decode(token(encoder,"https://wrong.example","autocomplete-api",300)));
   assertThrows(JwtException.class,()->decoder.decode(token(encoder,"https://issuer.example","wrong-api",300)));
   assertThrows(JwtException.class,()->decoder.decode(token(encoder,"https://issuer.example","autocomplete-api",-300)));
   String[] parts=valid.split("\\.");String tampered=parts[0]+"."+parts[1]+"."+(parts[2].charAt(0)=='a'?'b':'a')+parts[2].substring(1);
   assertThrows(JwtException.class,()->decoder.decode(tampered));
  }finally{server.stop(0);}
 }
 private String token(JwtEncoder encoder,String issuer,String audience,int expiry){var claims=JwtClaimsSet.builder().issuer(issuer).subject("user").audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().plusSeconds(expiry)).claim("tenant","public").build();return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("integration-key").build(),claims)).getTokenValue();}
}
