package com.agentcv;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentcv.api.ApiAdvice;

@Configuration
class SecurityConfiguration {
    @Bean
    UserDetailsService users(@Value("${AGENTCV_OPERATOR_PASSWORD_HASH:}") String hash) {
        if (hash.isBlank()) return new InMemoryUserDetailsManager();
        if (!hash.matches("\\$2[aby]\\$[0-9]{2}\\$.{53}")) throw new IllegalArgumentException("Invalid operator hash configuration");
        return new InMemoryUserDetailsManager(User.withUsername("operator").password(hash).roles("OPERATOR").build());
    }
    @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/assets/**", "/api/v1/system/status", "/api/v1/csrf", "/login").permitAll()
                .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.loginProcessingUrl("/login")
                        .successHandler((req,res,auth)->{ res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"authenticated\":true}"); })
                        .failureHandler((req,res,error)->{ res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); json.writeValue(res.getWriter(),ApiAdvice.error("BAD_CREDENTIALS","账号或口令不正确。",false)); }))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler((req,res,auth)->res.setStatus(204)))
                .addFilterBefore(new LoginThrottle(json), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req,res,error)->{ res.setStatus(401);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),ApiAdvice.error("AUTH_REQUIRED","登录已失效，请重新登录；页面输入仍保留。",false)); })
                        .accessDeniedHandler((req,res,error)->{res.setStatus(403);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),ApiAdvice.error("ACCESS_DENIED","操作凭证无效，请重新登录后再试。",false));}))
                .build();
    }
}
