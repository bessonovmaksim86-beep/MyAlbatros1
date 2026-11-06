package ru.company.production.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Матрица доступа ролей к разделам.
 *
 * <p>Проверяется ровно то, для чего существует {@link SecurityConfig}:
 * роль входит в раздел или нет. Контроллеров в срезе нет намеренно:
 * {@code AuthorizationFilter} работает в цепочке фильтров до
 * диспетчеризации, поэтому открытый адрес отвечает 404, а закрытый — 403.
 * Различие 403/не-403 и есть предмет теста, и оно не зависит от того,
 * существует ли страница и что она отдаёт.
 *
 * <p>Срез собран без {@code @WebMvcTest}: тот подтягивает
 * {@code WebConfig} со всеми {@code *ViewAdvice}, которым нужны
 * сервисы и репозитории, — ради проверки фильтров заводить половину
 * приложения смысла нет. Здесь важны только {@code @EnableWebMvc}
 * (чтобы существовал контекст MVC для MockMvc) и {@code @EnableWebSecurity}
 * (чтобы {@code HttpSecurity} собрался так же, как в приложении).
 *
 * <p>Роли берутся из {@link AccessRoles}, а не переписываются в тесте
 * заново: иначе тест подтверждал бы собственную догадку, а не правило.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SecurityAccessMatrixTest.TestSecurity.class)
@WebAppConfiguration
class SecurityAccessMatrixTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestSecurity {

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        DepartmentAuthenticationSuccessHandler authenticationSuccessHandler() {
            return mock(DepartmentAuthenticationSuccessHandler.class);
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecurityConfig securityConfig;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    /*
     * Роли задаёт post-processor user(...).roles(...), а не подставная
     * аутентификация: он прибавляет префикс ROLE_ тем же способом, каким
     * это делает userDetailsService, поэтому проверка сверяет правило с
     * тем же представлением authorities, что и боевой вход.
     */
    private static RequestPostProcessor withRole(String role) {
        return user("тест-" + role).roles(role);
    }

    /** Роль, которой не открыт ни один закрытый раздел. */
    private static final String ROLE_WITHOUT_ANY_SECTION =
            Role.SDP_ST_EXECUTOR.name();

    /*
     * «Разрешено» здесь означает не «код 200», а «не 403»: контроллеров
     * в срезе нет, поэтому разрешённый адрес отвечает 404. Отдельный
     * матчер нужен вместо status().isForbidden() в отрицании ещё и
     * затем, чтобы в сообщении падения было видно фактический код —
     * иначе «ожидание не выполнено» без кода не отличило бы 401 от 500.
     */
    private static ResultMatcher notForbidden() {
        return result -> {
            int status = result.getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    403, status,
                    "запрос " + result.getRequest().getMethod() + " "
                            + result.getRequest().getRequestURI()
                            + " отклонён по правам");
        };
    }

    private void expectAllowed(String role, String url) throws Exception {
        mockMvc.perform(get(url).with(withRole(role)))
                .andExpect(notForbidden());
    }

    private void expectDenied(String role, String url) throws Exception {
        mockMvc.perform(get(url).with(withRole(role)))
                .andExpect(status().isForbidden());
    }

    private void expectAllowedPost(String role, String url) throws Exception {
        mockMvc.perform(post(url).with(withRole(role)).with(csrf()))
                .andExpect(notForbidden());
    }

    private void expectDeniedPost(String role, String url) throws Exception {
        mockMvc.perform(post(url).with(withRole(role)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Nested
    @DisplayName("администратор")
    class Admin {

        @Test
        @DisplayName("консоль открыта только роли администратора")
        void onlyAdminEnters() throws Exception {
            expectAllowed(Role.ADMIN.name(), "/admin");
            expectAllowed(Role.ADMIN.name(), "/admin/users");
            expectDenied(ROLE_WITHOUT_ANY_SECTION, "/admin");
            expectDenied(Role.SDP_SA_MASTER.name(), "/admin");
        }
    }

    @Nested
    @DisplayName("распределение работ")
    class Distribution {

        @Test
        @DisplayName("все роли набора входят, несмотря на общий блок /workshop/**")
        void everyListedRoleEnters() throws Exception {
            for (String role : AccessRoles.DISTRIBUTION_MANAGERS) {
                expectAllowed(role, "/workshop/distribution");
                expectAllowed(role, "/workshop/distribution/1");
            }
        }

        @Test
        @DisplayName("руководитель службы качества входит в подразделение, хотя цеховой модуль ему закрыт")
        void qualityHeadEntersBeforeGeneralBlock() throws Exception {
            /*
             * Порядок правил (docs/security-access.md, раздел 1): если
             * "/workshop/**" встанет выше, SK_GENERAL получит 403 — при
             * том, что меню продолжит рисовать ему пункт раздела, и
             * симптом выглядел бы как «кнопка есть, страница не
             * открывается».
             */
            expectAllowed(Role.SK_GENERAL.name(), "/workshop/distribution");
            expectAllowed(Role.SK_GENERAL.name(), "/workshop/staff/1");
            expectAllowed(Role.SK_GENERAL.name(), "/workshop/operations/1");
            expectAllowed(Role.SK_GENERAL.name(), "/workshop/calendar/1");
        }

        @Test
        @DisplayName("руководителей БВК и БТК в наборе нет намеренно")
        void bureauHeadsAreExcluded() throws Exception {
            /*
             * В бюро работу не распределяют: строку берут из перечня
             * подразделения. Если роль сюда вернётся — закрепление за
             * человеком отрезит работу от остальных сотрудников бюро.
             */
            expectDenied(Role.BVK_MASTER.name(), "/workshop/distribution");
            expectDenied(Role.BTK_MASTER.name(), "/workshop/distribution");
        }

        @Test
        @DisplayName("исполнитель цеха не входит")
        void executorIsDenied() throws Exception {
            expectDenied(Role.SDP_SA_EXECUTOR.name(), "/workshop/distribution");
        }
    }

    @Nested
    @DisplayName("сотрудники, операции и календарь подразделения")
    class UnitSections {

        @Test
        @DisplayName("набор руководителей входит на чтение")
        void managersRead() throws Exception {
            for (String role : AccessRoles.UNIT_MANAGERS) {
                expectAllowed(role, "/workshop/staff");
                expectAllowed(role, "/workshop/calendar");
                expectAllowed(role, "/workshop/operations");
            }
        }

        @Test
        @DisplayName("запись открыта тем же ролям, что и чтение")
        void managersWrite() throws Exception {
            for (String role : AccessRoles.UNIT_MANAGERS) {
                mockMvc.perform(post("/workshop/staff")
                                .with(withRole(role)).with(csrf()))
                        .andExpect(notForbidden());
            }
        }

        @Test
        @DisplayName("POST без CSRF-токена отклоняется — и его нельзя путать с отказом по роли")
        void writeRequiresCsrf() throws Exception {
            /*
             * В проекте CSRF включён. Если проверка отвалится, все
             * утверждения «не 403» выше теряют смысл: они перестанут
             * различать отказ по правам и отказ по токену.
             */
            mockMvc.perform(post("/workshop/staff")
                            .with(withRole(Role.SDP_SA_MASTER.name())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("вне набора руководителей раздел закрыт")
        void othersCannotManageStaff() throws Exception {
            expectDenied(Role.SDP_SA_EXECUTOR.name(), "/workshop/staff");
            expectDenied(Role.DISPATCHER.name(), "/workshop/staff/1");
        }
    }

    @Nested
    @DisplayName("полный цеховой модуль")
    class WorkshopModule {

        @Test
        @DisplayName("открыт мастерам цехов, складов и бюро")
        void unitManagersEnter() throws Exception {
            for (String role : AccessRoles.UNIT_MANAGERS) {
                expectAllowed(role, "/workshop");
            }
        }

        @Test
        @DisplayName("закрыт диспетчеру и исполнителям")
        void othersAreDenied() throws Exception {
            expectDenied(Role.DISPATCHER.name(), "/workshop");
            expectDenied(Role.SDP_SA_EXECUTOR.name(), "/workshop");
            expectDenied(Role.BVK_EXECUTOR.name(), "/workshop");
        }

        @Test
        @DisplayName("руководителю службы качества закрыт целиком, хотя сотрудники и календарь открыты")
        void qualityHeadSeesOnlySections() throws Exception {
            /*
             * Разделение UNIT_SECTIONS и UNIT_MANAGERS держит эту границу:
             * служба качества смотрит сотрудников и операции подразделения,
             * но не ведает штатным расписанием и не правит календарь цеха
             * (docs, разделы 5–6). Если наборы склеить в один, тест
             * поймает это как «не 403» здесь, а не как молчаливо
             * расширенные права.
             */
            expectAllowed(Role.SK_GENERAL.name(), "/workshop/staff");
            expectDenied(Role.SK_GENERAL.name(), "/workshop");
        }
    }

    @Nested
    @DisplayName("приёмка от склада (/workshop/receiving)")
    class WorkshopReceiving {

        /*
         * Приёмку подтверждает тот, кому позицию привезли, — доступ у
         * набора руководителей цехового модуля, того же, что и у остального
         * модуля. Границу «своё подразделение» держит сервис: ссылка
         * /workshop/receiving/accept одна на все цеха, и проверка по роли
         * её не заменит.
         */
        @Test
        @DisplayName("открыта мастерам цехов и руководителям подразделений")
        void unitManagersEnter() throws Exception {
            for (String role : AccessRoles.UNIT_MANAGERS) {
                expectAllowed(role, "/workshop/receiving");
                expectAllowed(role, "/workshop/receiving/accept");
            }
        }

        @Test
        @DisplayName("закрыта исполнителям и диспетчеру")
        void othersAreDenied() throws Exception {
            expectDenied(Role.SDP_SA_EXECUTOR.name(), "/workshop/receiving");
            expectDenied(Role.BVK_EXECUTOR.name(), "/workshop/receiving");
            expectDenied(Role.DISPATCHER.name(), "/workshop/receiving");
            expectDenied(Role.SK_GENERAL.name(), "/workshop/receiving");
        }
    }

    @Nested
    @DisplayName("склад")
    class Warehouse {

        @Test
        @DisplayName("открыт обоим кладовщикам")
        void executorsEnter() throws Exception {
            for (String role : AccessRoles.WAREHOUSE_EXECUTORS) {
                expectAllowed(role, "/warehouse");
                expectAllowed(role, "/warehouse/batches");
            }
        }

        @Test
        @DisplayName("руководителю склада закрыт: его модуль — /workshop")
        void headIsDenied() throws Exception {
            expectDenied(Role.OMTSIK_MASTER.name(), "/warehouse");
            expectDenied(Role.SGP_MASTER.name(), "/warehouse");
        }
    }

    @Nested
    @DisplayName("бюро входного контроля")
    class InputControl {

        @Test
        @DisplayName("обе роли бюро читают карточки прихода")
        void bothRolesRead() throws Exception {
            for (String role : AccessRoles.BVK_STAFF) {
                expectAllowed(role, "/input-control");
                expectAllowed(role, "/input-control/1");
            }
        }

        @Test
        @DisplayName("проводить проверку вправе только исполнитель")
        void onlyExecutorDecides() throws Exception {
            /*
             * Решение по партии остаётся за тем, кто ставит подпись
             * (docs, раздел 8).
             */
            mockMvc.perform(post("/input-control/decision")
                            .with(withRole(Role.BVK_MASTER.name())).with(csrf()))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/input-control/decision")
                            .with(withRole(Role.BVK_EXECUTOR.name()))
                            .with(csrf()))
                    .andExpect(notForbidden());
        }

        @Test
        @DisplayName("чужим ролям раздел закрыт целиком")
        void otherRolesAreDenied() throws Exception {
            expectDenied(Role.BTK_EXECUTOR.name(), "/input-control");
            expectDenied(Role.SDP_SA_MASTER.name(), "/input-control");
        }
    }

    @Nested
    @DisplayName("служба качества")
    class Quality {

        @Test
        @DisplayName("изолятор брака и ремонты — только руководитель службы")
        void qualityIsHeadOnly() throws Exception {
            expectAllowed(Role.SK_GENERAL.name(), "/quality");
            expectDenied(Role.BVK_EXECUTOR.name(), "/quality");
            expectDenied(Role.BVK_MASTER.name(), "/quality");
        }
    }

    @Nested
    @DisplayName("заявки на отбраковку")
    class PendingDefects {

        @Test
        @DisplayName("ведёт любое из двух бюро, обе роли каждого")
        void bothBureausHandleRequests() throws Exception {
            for (String role : AccessRoles.DEFECT_REQUEST_STAFF) {
                expectAllowed(role, "/pending-defects");
            }
        }

        @Test
        @DisplayName("цеху, который заявку порождает, раздел закрыт")
        void workshopCannotDecide() throws Exception {
            /*
             * Заявка рождается из цеха, но проводит её по журналу бюро
             * (docs, раздел 10).
             */
            expectDenied(Role.SDP_SA_MASTER.name(), "/pending-defects");
            expectDenied(Role.SDP_SA_EXECUTOR.name(), "/pending-defects");
        }
    }

    @Nested
    @DisplayName("кириллица в именах ролей")
    class CyrillicRoleNames {

        @Test
        @DisplayName("настоящее имя склада комплектации даёт доступ")
        void realNameGrantsAccess() throws Exception {
            expectAllowed(Role.OMTSIK_С_MASTER.name(), "/workshop/distribution");
            expectAllowed(Role.OMTSIK_С_EXECUTOR.name(), "/warehouse");
        }

        @Test
        @DisplayName("латинская «C» вместо кириллической «С» доступа не даёт")
        void latinLookAlikeIsNotTheSameRole() throws Exception {
            /*
             * Единственная защита от ловушки, которую невозможно найти
             * глазами: OMTSIK_C_MASTER и OMTSIK_С_MASTER различаются
             * только кодовой точкой (U+0043 против U+0421). Правило или
             * учётная запись с «не той» буквой молча не совпадают, и
             * единственный признак — «у кладовщика пропал раздел».
             */
            expectDenied("OMTSIK_C_MASTER", "/workshop/distribution");
            expectDenied("OMTSIK_C_EXECUTOR", "/warehouse");

            assertFalse(Role.OMTSIK_С_MASTER.name()
                            .equals("OMTSIK_C_MASTER"),
                    "имя роли совпало с латинским написанием: либо enum "
                            + "переписан, либо тест проверяет не то");
        }

        @Test
        @DisplayName("каждое имя в наборах существует в enum")
        void everyRoleInSetsExists() {
            for (String[] set : new String[][]{
                    AccessRoles.SDP_WORKSHOP_MASTERS,
                    AccessRoles.WAREHOUSE_HEADS,
                    AccessRoles.UNIT_MANAGERS,
                    AccessRoles.UNIT_SECTIONS,
                    AccessRoles.DISTRIBUTION_MANAGERS,
                    AccessRoles.WAREHOUSE_EXECUTORS,
                    AccessRoles.BVK_STAFF,
                    AccessRoles.DEFECT_REQUEST_STAFF}) {

                for (String name : set) {
                    assertEquals(name, Role.valueOf(name).name(),
                            "правило ссылается на роль, которой нет в enum");
                }
            }
        }
    }

    @Nested
    @DisplayName("общедоступное")
    class PublicPart {

        @Test
        @DisplayName("страница входа и выход открыты без роли")
        @WithMockUser
        void loginAndLogoutAreOpen() throws Exception {
            mockMvc.perform(get("/login"))
                    .andExpect(notForbidden());
            mockMvc.perform(get("/css/app.css"))
                    .andExpect(notForbidden());
            mockMvc.perform(post("/logout").with(csrf()))
                    .andExpect(notForbidden());
        }

        @Test
        @DisplayName("каскады и скрипты разделов не закрыты ролью руководителя")
        void sectionAssetsAreOpen() throws Exception {
            /*
             * Симптом нарушения этого правила выглядит как дефект
             * вёрстки, а не как отказ доступа: «Мои операции» берут меню
             * у модуля руководителя, и исполнителю возвращаются 403 на
             * /workshop/css/** — страница остаётся без каскадов (docs,
             * раздел 2).
             */
            for (String url : new String[]{
                    "/workshop/css/workshop.css",
                    "/workshop/js/distribution.js",
                    "/admin/css/admin.css",
                    "/quality/css/quality.css",
                    "/warehouse/css/warehouse.css"}) {

                mockMvc.perform(get(url).with(withRole(Role.SDP_SA_EXECUTOR.name())))
                        .andExpect(notForbidden());
            }
        }

        @Test
        @DisplayName("«Мои операции» доступны любой аутентифицированной роли")
        void myOperationsAreOpenToEveryone() throws Exception {
            expectAllowed(Role.SDP_SA_EXECUTOR.name(), "/my-operations");
            expectAllowed(Role.SK_GENERAL.name(), "/my-operations");
            expectAllowed(Role.OMTSIK_С_EXECUTOR.name(), "/my-operations");
            expectAllowed(Role.ADMIN.name(), "/my-operations/1");
        }

        @Test
        @DisplayName("неаутентифицированный запрос уходит на вход, а не получает 403")
        void anonymousIsRedirected() throws Exception {
            mockMvc.perform(get("/workshop/staff"))
                    .andExpect(status().is3xxRedirection());
        }
    }

    @Nested
    @DisplayName("соответствие роли и authorities")
    class UserDetailsMapping {

        @Test
        @DisplayName("роль из enum становится authority с префиксом ROLE_")
        void roleBecomesAuthority() {
            AppUser saved = user(Role.OMTSIK_С_MASTER);
            when(userRepository.findByUsernameIgnoreCase(anyString()))
                    .thenReturn(java.util.Optional.of(saved));

            UserDetails details =
                    securityConfig.userDetailsService().loadUserByUsername("u");

            /*
             * Правило доступа сравнивает строки, а строку даёт этот
             * метод: если здесь окажется не name(), а displayName с
             * точками («СГД.Склад комплектации.Руководитель»), ни одно
             * правило не совпадёт, и отказ будет выглядеть как
             * «правило есть, но оно не работает».
             */
            assertTrue(details.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority()
                                    .equals("ROLE_" + Role.OMTSIK_С_MASTER.name())),
                    "authority построено не из имени enum: "
                            + details.getAuthorities());
        }

        @Test
        @DisplayName("отключённая учётная запись не получает доступа")
        void inactiveUserIsDisabled() {
            AppUser blocked = user(Role.SK_GENERAL);
            blocked.setActive(false);
            when(userRepository.findByUsernameIgnoreCase(anyString()))
                    .thenReturn(java.util.Optional.of(blocked));

            UserDetails details =
                    securityConfig.userDetailsService().loadUserByUsername("u");

            /*
             * .disabled(...) в userDetailsService — единственное место,
             * где признак active доходит до фильтра: сами правила про
             * активность ничего не знают.
             */
            assertFalse(details.isEnabled(),
                    "отключённая учётная запись прошла бы вход");
        }

        @Test
        @DisplayName("несуществующий пользователь — ошибка, а не пустой доступ")
        void unknownUsernameThrows() {
            when(userRepository.findByUsernameIgnoreCase(anyString()))
                    .thenReturn(java.util.Optional.empty());

            assertThrows(UsernameNotFoundException.class,
                    () -> securityConfig.userDetailsService()
                            .loadUserByUsername("net-takogo"));
        }

        private AppUser user(Role role) {
            AppUser appUser = new AppUser();
            appUser.setUsername("u");
            appUser.setRole(role);
            appUser.setPasswordHash("$2a$12$00000000000000000000000000000000000000000000000000");
            appUser.setActive(true);
            return appUser;
        }
    }

    @Nested
    @DisplayName("наборы ролей покрывают enum")
    class RoleCoverage {

        @Test
        @DisplayName("у каждой роли enum есть либо набор, либо осознанное отсутствие")
        void everyRoleIsAccountedFor() {
            /*
             * Новая роль обязана появиться в AccessRoles или в списке
             * ниже: иначе она останется без раздела, и заметят это не
             * сразу, а когда пользователь скажет «у меня пустое меню».
             */
            java.util.Set<String> unassigned = new java.util.TreeSet<>();

            for (Role role : Role.values()) {
                if (!inAnySet(role.name())) {
                    unassigned.add(role.name());
                }
            }

            assertEquals(
                    java.util.Set.of(
                            /* Исполнители: их единственный раздел — «Мои
                             * операции», он открыт всем аутентифицированным. */
                            Role.SDP_SPP_EXECUTOR.name(),
                            Role.SDP_SA_EXECUTOR.name(),
                            Role.SDP_SC_EXECUTOR.name(),
                            Role.SDP_ST_EXECUTOR.name(),
                            /* SC — устаревшее имя службы без собственного
                             * интерфейса. */
                            Role.SC.name()),
                    unassigned,
                    "роль не назначена ни одному разделу и не объяснена");
        }

        private boolean inAnySet(String name) {
            for (String[] set : new String[][]{
                    AccessRoles.SDP_WORKSHOP_MASTERS,
                    AccessRoles.WAREHOUSE_HEADS,
                    AccessRoles.UNIT_MANAGERS,
                    AccessRoles.UNIT_SECTIONS,
                    AccessRoles.DISTRIBUTION_MANAGERS,
                    AccessRoles.WAREHOUSE_EXECUTORS,
                    AccessRoles.BVK_STAFF,
                    AccessRoles.DEFECT_REQUEST_STAFF}) {

                for (String entry : set) {
                    if (entry.equals(name)) {
                        return true;
                    }
                }
            }

            /* ADMIN — единственная роль, закрытая правилом в одну роль;
             * остальные одиночные правила (SK_GENERAL, BVK_EXECUTOR)
             * покрыты наборами выше. */
            return Role.ADMIN.name().equals(name);
        }
    }

    @Nested
    @DisplayName("согласованность с маршрутами модулей")
    class ConsistencyWithModuleRoutes {

        @Test
        @DisplayName("модуль, куда роль входит после входа, ей действительно открыт")
        void destinationIsReachable() throws Exception {
            /*
             * DepartmentAuthenticationSuccessHandler отправляет роль на
             * адрес из ModuleRoutes. Если SecurityConfig этот адрес не
             * открывает, пользователь получает 403 сразу после входа —
             * то есть ровно то, что было с SK_GENERAL и распределением
             * работ до правки порядка правил.
             */
            lenient().when(userRepository.findWithUnitsByUsernameIgnoreCase(anyString()))
                    .thenReturn(java.util.Optional.empty());

            for (Role role : Role.values()) {
                String url = ru.company.production.service.ModuleRoutes
                        .destinationFor(role).url();

                if ("/home".equals(url) || url.isBlank()) {
                    continue;
                }

                int status = mockMvc.perform(
                                get(url).with(withRole(role.name())))
                        .andReturn().getResponse().getStatus();

                assertTrue(status != 403,
                        role + " после входа идёт на " + url
                                + ", но раздел ей закрыт");
            }
        }
    }
}
