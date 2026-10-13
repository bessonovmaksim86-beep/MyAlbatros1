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
                         * Стили и скрипты разделов. Разметка общего
                         * раздела «Мои операции» берёт боковое меню у
                         * модуля руководителя (workshop/fragments/layout),
                         * поэтому страница исполнителя ссылается на
                         * /workshop/css/**; то же делают кладовщик и
                         * руководитель службы качества. Без этого
                         * правила файлы попадают под общий matcher
                         * своего раздела, закрытый ролями руководителей,
                         * и роль исполнителя получает на них 403 —
                         * страница остаётся без каскадов: панель свёрнута
                         * и подписи пунктов вылезают наружу, а бейджи
                         * склеиваются. Правило держится над правилами
                         * разделов: Spring Security берёт первое
                         * совпадение.
                         *
                         * Шаблон по каталогу, а не список разделов:
                         * в static у каждого раздела только css и js, и
                         * новый раздел не придётся дописывать сюда.
                         */
                        .requestMatchers("/*/css/**", "/*/js/**")
                        .permitAll()
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        /*
                         * «Мои операции»: работы, закреплённые за самим
                         * пользователем. Доступен всем аутентифицированным.
                         * Список строится по учётной записи, поэтому
                         * пользователь видит только свои работы независимо
                         * от роли.
                         */
                        .requestMatchers("/my-operations", "/my-operations/**")
                        .authenticated()
                        /*
                         * Распределение работ — ПЕРВЫМ, до общего
                         * "/workshop/**": Spring Security применяет
                         * первое совпадение, и если выше стоит общий
                         * matcher, его короткий список ролей отрезал бы
                         * расширенный — руководитель службы качества не
                         * попадал в раздел, хотя пункт «Распределение
                         * работ» ему в меню рисовали.
                         *
                         * Граница содержимого — подразделение учётной
                         * записи (WorkshopStaffService), роль здесь
                         * решает только «впускать ли вообще». Кроме
                         * мастеров цехов, складов и бюро допущены
                         * руководители вне цехового модуля: техбюро,
                         * диспетчерский отдел, диспетчер и служба
                         * качества — операции (в т. ч. получение
                         * комплектации) их подразделения планировщик
                         * закрепляет за ними, и распределять их больше
                         * некому.
                         */
                        .requestMatchers(
                                "/workshop/distribution",
                                "/workshop/distribution/**"
                        )
                        .hasAnyRole(
                                "SDP_SPP_MASTER",
                                "SDP_SA_MASTER",
                                "SDP_SC_MASTER",
                                "SDP_ST_MASTER",
                                "OMTSIK_MASTER",
                                "OMTSIK_С_MASTER",
                                "BVK_MASTER",
                                "SK_GENERAL",
                                "SDP_SERVICE_HEAD_TECHNOLOGY",
                                "SDP_SERVICE_HEAD_DISPATCHER",
                                "DISPATCHER"
                        )
                        /*
                         * Сотрудники, операции и производственный календарь —
                         * начальнику службы контроля их открывают отдельными
                         * правилами.
                         *
                         * Правила стоят до общего блока /workshop/**, потому что
                         * Spring Security берёт первое совпадение: блок закрыт
                         * цеховыми ролями и отсек бы SK_GENERAL раньше. Границей
                         * его списков служит служба целиком (WorkshopStaffService
                         * #unitScope), поэтому контролёры БТК и БВК видны ему
                         * целиком.
                         *
                         * Все три раздела открыты и на запись: по замыслу они
                         * работают для службы так же, как для руководителя
                         * цеха. Справочник операций общий с технологическим
                         * отделом, и без права записи контролёры БТК и БВК
                         * работали бы операциями чужих цехов, а без закрепления
                         * сотрудников в очереди распределения не появилось бы
                         * ни одного исполнителя. Границы при этом проверяет
                         * сервис: подразделение вне своей службы служба не
                         * получит, даже изменив идентификатор в форме.
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
                        .hasAnyRole(
                                "SDP_SPP_MASTER",
                                "SDP_SA_MASTER",
                                "SDP_SC_MASTER",
                                "SDP_ST_MASTER",
                                "OMTSIK_MASTER",
                                "OMTSIK_С_MASTER",
                                "BVK_MASTER",
                                "SK_GENERAL"
                        )
                        .requestMatchers(
                                HttpMethod.POST,
                                "/workshop/staff",
                                "/workshop/staff/**",
                                "/workshop/operations",
                                "/workshop/operations/**",
                                "/workshop/calendar",
                                "/workshop/calendar/**"
                        )
                        .hasAnyRole(
                                "SDP_SPP_MASTER",
                                "SDP_SA_MASTER",
                                "SDP_SC_MASTER",
                                "SDP_ST_MASTER",
                                "OMTSIK_MASTER",
                                "OMTSIK_С_MASTER",
                                "BVK_MASTER",
                                "SK_GENERAL"
                        )
                        /*
                         * Полный цеховой модуль (сотрудники, календарь,
                         * операции подразделения) остаётся за мастерами
                         * цехов, складов и бюро входного контроля.
                         */
                        .requestMatchers("/workshop", "/workshop/**")
                        .hasAnyRole(
                                "SDP_SPP_MASTER",
                                "SDP_SA_MASTER",
                                "SDP_SC_MASTER",
                                "SDP_ST_MASTER",
                                "OMTSIK_MASTER",
                                "OMTSIK_С_MASTER",
                                "BVK_MASTER"
                        )
                        /*
                         * Склад полуфабрикатов: оприходование ячеек.
                         * Модуль определяет свои границы по учётной записи,
                         * поэтому право даётся только роли склада.
                         */
                        .requestMatchers("/warehouse", "/warehouse/**")
                        .hasAnyRole(
                                "OMTSIK_EXECUTOR",
                                "OMTSIK_С_EXECUTOR"
                        )
                        /*
                         * Бюро входного контроля: проверка партий,
                         * оприходованных складом. Модуль работает с
                         * чужим приходом, поэтому право давать ему
                         * доступ к этим партиям есть только у роли
                         * входного контроля.
                         *
                         * Руководителю бюро открыт только просмотр:
                         * карточки прихода он читает те же, что и его
                         * контролёры, и без этих пунктов его меню
                         * отправляло бы его в чужой модуль. Проводить
                         * проверку он не вправе — решение по партии
                         * остаётся за исполнителем, поэтому POST
                         * закрыт его ролью и ниже.
                         */
                        .requestMatchers(
                                HttpMethod.GET,
                                "/input-control",
                                "/input-control/**"
                        )
                        .hasAnyRole("BVK_EXECUTOR", "BVK_MASTER")
                        .requestMatchers("/input-control", "/input-control/**")
                        .hasRole("BVK_EXECUTOR")
                        /*
                         * Интерфейс руководителя службы качества: разбор
                         * изолятора брака и заказы на ремонт. Модуль читает
                         * те же карточки входного контроля, что и бюро, но
                         * решает по ним свою задачу — поэтому право на него
                         * есть только у роли руководителя СК.
                         */
                        .requestMatchers("/quality", "/quality/**")
                        .hasRole("SK_GENERAL")
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
