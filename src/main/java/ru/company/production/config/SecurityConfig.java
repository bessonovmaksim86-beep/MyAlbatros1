package ru.company.production.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import ru.company.production.repository.UserRepository;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    private final DepartmentAuthenticationSuccessHandler successHandler;
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository
                .findByUsernameIgnoreCase(username)
                .map(appUser -> User.builder()
                        .username(appUser.getUsername())
                        .password(appUser.getPasswordHash())
                        .roles(appUser.getRole().name())
                        .disabled(!appUser.isActive())
                        .build())
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "Пользователь не найден: " + username
                        )
                );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login",
                                "/error",
                                "/favicon.ico",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/webjars/**"
                        ).permitAll()
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        /*
                         * Модуль руководителя цеха: доступ только у
                         * руководящих ролей цехов. Содержимое при этом
                         * ограничено цехом конкретной учётной записи —
                         * см. WorkshopStaffService, где граница берётся
                         * из подразделения пользователя.
                         */
                        .requestMatchers("/workshop", "/workshop/**")
                        .hasAnyRole(
                                "SDP_SPP_MASTER",
                                "SDP_SA_MASTER",
                                "SDP_SC_MASTER",
                                "SDP_ST_MASTER"
                        )
                        /*
                         * Склад полуфабрикатов: оприходование ячеек.
                         * Модуль определяет свои границы по учётной записи,
                         * поэтому право даётся только роли склада.
                         */
                        .requestMatchers("/warehouse", "/warehouse/**")
                        .hasRole("OMTSIK_EXECUTOR")
                        /*
                         * Бюро входного контроля: проверка партий,
                         * оприходованных складом. Модуль работает с
                         * чужим приходом, поэтому право давать ему
                         * доступ к этим партиям есть только у роли
                         * входного контроля.
                         */
                        .requestMatchers("/input-control", "/input-control/**")
                        .hasRole("BVK_EXECUTOR")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(successHandler)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .rememberMe(remember -> remember
                        .rememberMeParameter("remember-me")
                        .tokenValiditySeconds(14 * 24 * 60 * 60)
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID", "remember-me")
                        .permitAll()
                );

        return http.build();
    }
}
