package dev.typeahead;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;

@Configuration class Security {
 @Bean JwtDecoder jwtDecoder(
   @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
   @Value("${oidc.issuer:http://localhost:9090/realms/autocomplete}") String issuer,
   @Value("${oidc.audience:autocomplete-api}") String audience) {
  NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
  OAuth2TokenValidator<Jwt> audienceValidator=jwt->jwt.getAudience().contains(audience)
    ?OAuth2TokenValidatorResult.success()
    :OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid audience",null));
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),audienceValidator));
  return decoder;
 }
 @Bean SecurityFilterChain filterChain(HttpSecurity h)throws Exception {
  return h.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
    .requestMatchers("/api/v1/admin/**").hasAuthority("SCOPE_autocomplete.admin")
    .requestMatchers("/api/v1/autocomplete/recent","/api/v1/autocomplete/events").authenticated()
    .requestMatchers("/api/v1/autocomplete/**","/internal/shards/query","/actuator/health","/actuator/prometheus").permitAll().anyRequest().denyAll())
   .oauth2ResourceServer(o->o.jwt(j->{})).build();
 }
}
