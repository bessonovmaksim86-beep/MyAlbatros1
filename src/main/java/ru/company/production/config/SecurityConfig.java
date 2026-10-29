package ru.company.production.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                        /*
                         * Стили и скрипты разделов: общие правила
                         * (см. docs/security-access.md, разделы 2–3).
                         */
                        .requestMatchers("/*/css/**", "/*/js/**")
                        .permitAll()
                        .requestMatchers("/admin", "/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/my-operations",
                                "/my-operations/**")
                        .authenticated()
                        /*
                         * Распределение работ: см. docs/security-access.md,
                         * раздел 4.
                         */
                        .requestMatchers(
                                "/workshop/distribution",
                                "/workshop/distribution/**"
                        )
                        .hasAnyRole(AccessRoles.DISTRIBUTION_MANAGERS)
                        /*
                         * Сотрудники, операции, календарь: раздел 5.
                         *
                         * Набор шире UNIT_MANAGERS: сюда входит
                         * руководитель службы качества, которому эти
                         * разделы открыты его собственной службой.
                         */
                        .requestMatchers(
                                HttpMethod.GET,
                                "/workshop/staff",
                                "/workshop/staff/**",
                                "/workshop/operations",
                                "/workshop/operations/**",
                                "/workshop/calendar",
                                "/workshop/calendar/**"
                        )
                        .hasAnyRole(AccessRoles.UNIT_SECTIONS)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/workshop/staff",
                                "/workshop/staff/**",
                                "/workshop/operations",
                                "/workshop/operations/**",
                                "/workshop/calendar",
                                "/workshop/calendar/**"
                        )
                        .hasAnyRole(AccessRoles.UNIT_SECTIONS)
                        /*
                         * Полный цеховой модуль: раздел 6.
                         */
                        .requestMatchers("/workshop", "/workshop/**")
                        .hasAnyRole(AccessRoles.UNIT_MANAGERS)
                        /*
                         * Склад: раздел 7.
                         */
                        .requestMatchers("/warehouse", "/warehouse/**")
                        .hasAnyRole(AccessRoles.WAREHOUSE_EXECUTORS)
                        /*
                         * Бюро входного контроля: раздел 8.
                         */
                        .requestMatchers(
                                HttpMethod.GET,
                                "/input-control",
                                "/input-control/**"
                        )
                        .hasAnyRole(AccessRoles.BVK_STAFF)
                        .requestMatchers("/input-control",
                                "/input-control/**")
                        .hasRole("BVK_EXECUTOR")
                        /*
                         * Служба качества: раздел 9.
                         */
                        .requestMatchers("/quality", "/quality/**")
                        .hasRole("SK_GENERAL")
                        /*
                         * Заявки на отбраковку: раздел 10.
                         */
                        .requestMatchers("/pending-defects",
                                "/pending-defects/**")
                        .hasAnyRole(AccessRoles.DEFECT_REQUEST_STAFF)
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
