package com.nexusgate.management.security;

import com.nexusgate.management.config.JwtProperties;
import com.nexusgate.management.service.JwtPrincipalLoader;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.crypto.SecretKey;

@Configuration
public class ManagementSecurityConfiguration {

    @Bean
    SecurityFilterChain managementSecurityFilterChain(
            HttpSecurity http,
            JwtProperties jwtProperties,
            SecretKey jwtSigningKey,
            JwtPrincipalLoader principalLoader
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                response.setStatus(401))
                        .accessDeniedHandler((request, response, exception) ->
                                response.setStatus(403))
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/organizations/register",
                                "/api/v1/auth/login"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtProperties, jwtSigningKey, principalLoader),
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }
}
