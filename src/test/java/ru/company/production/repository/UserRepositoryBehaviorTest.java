package ru.company.production.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.AbstractMySQLIntegrationTest;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductionService;
import ru.company.production.entity.Role;
import ru.company.production.entity.RoleUser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поведение {@link UserRepository} на реальной MySQL.
 *
 * <p>Часть проверок бессмысленна на in-memory базе: сортировка по ФИО
 * и регистронезависимый поиск зависят от {@code utf8mb4_unicode_ci},
 * а H2 сортирует кириллицу в байтовом порядке, где «аркадий» идёт после
 * «Юрий». Эти тесты фиксируют именно боевой collation.
 *
 * <p>Требует Docker. Без него класс пропускается целиком (см.
 * {@link AbstractMySQLIntegrationTest}).
 */
@Transactional
class UserRepositoryBehaviorTest extends AbstractMySQLIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductionServiceRepository serviceRepository;

    @Autowired
    private OrganizationUnitRepository unitRepository;

    private AppUser newUser(
            String username,
            String fullName,
            Role role,
            OrganizationUnit unit
    ) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setFullName(fullName);
        user.setSpecialty("Тест");
        user.setRole(role);
        user.setRoleUser(RoleUser.EXECUTOR);
        user.setOrganizationUnit(unit);
        user.setService(unit == null ? null : unit.getService());
        user.setPasswordHash("$2a$12$00000000000000000000000000000000000000000000000000");
        user.setActive(true);
        return user;
    }

    /** Подразделение цеха сборки службы СДП из первоначальных данных. */
    private OrganizationUnit assemblyWorkshop() {
        ProductionService sdp = serviceRepository
                .findByCodeIgnoreCase("SDP")
                .orElseThrow(() -> new IllegalStateException(
                        "V2__insert_initial_data.sql перестал заводить "
                                + "службу SDP — тест теряет смысл"));

        return unitRepository
                .findByServiceIdAndCodeIgnoreCase(sdp.getId(),
                        "WORKSHOP_ASSEMBLY")
                .orElseThrow(() -> new IllegalStateException(
                        "Во первоначальных данных нет цеха сборки"));
    }

    @Nested
    @DisplayName("поиск по логину")
    class ByUsername {

        @Test
        @DisplayName("не чувствителен к регистру")
        void ignoresCase() {
            AppUser saved = userRepository.save(
                    newUser("IvanovII", "Иванов И. И.",
                            Role.SDP_SA_EXECUTOR, assemblyWorkshop()));

            assertTrue(userRepository
                    .findByUsernameIgnoreCase("ivanovii").isPresent());
            assertTrue(userRepository
                    .findByUsernameIgnoreCase("IVANOVII").isPresent());
            assertTrue(userRepository
                    .existsByUsernameIgnoreCase("IvAnOvIi"));

            /*
             * Метод возвращает ту же строку, что и точный поиск:
             * игнорирование регистра не должно ломать идентификатор,
             * по которому потом сверяется сессия.
             */
            assertEquals(saved.getId(), userRepository
                    .findByUsernameIgnoreCase("ivanovii").orElseThrow().getId());
        }

        @Test
        @DisplayName("с службой и подразделением приходит за один запрос")
        void loadsUnitsGraph() {
            userRepository.save(newUser(
                    "PetrovSP", "Петров С. П.",
                    Role.SDP_SA_MASTER, assemblyWorkshop()));

            /*
             * findWithUnitsByUsernameIgnoreCase нужен модулю цеха уже после
             * закрытия сессии (open-in-view выключён). Проверка обращается к
             * связям вне транзакции чтения — без @EntityGraph здесь был бы
             * LazyInitializationException, а не пустой список.
             */
            AppUser user = userRepository
                    .findWithUnitsByUsernameIgnoreCase("petrovsp")
                    .orElseThrow();

            assertEquals("Служба директора по производству",
                    user.getService().getName());
            assertEquals("Цех сборки",
                    user.getOrganizationUnit().getName());
        }

        @Test
        @DisplayName("несуществующий логин — пусто, а не исключение")
        void missingUsernameIsEmpty() {
            assertTrue(userRepository
                    .findByUsernameIgnoreCase("net-takogo").isEmpty());
            assertFalse(userRepository
                    .existsByUsernameIgnoreCase("net-takogo"));
        }
    }

    @Nested
    @DisplayName("списки сотрудников")
    class StaffLists {

        @Test
        @DisplayName("отключённый сотрудник не попадает в список подразделения")
        void inactiveIsFiltered() {
            OrganizationUnit unit = assemblyWorkshop();

            userRepository.save(newUser(
                    "SidorovAA", "Сидоров А. А.",
                    Role.SDP_SA_EXECUTOR, unit));

            AppUser fired = newUser(
                    "KuznetsovVV", "Кузнецов В. В.",
                    Role.SDP_SA_EXECUTOR, unit);
            fired.setActive(false);
            userRepository.save(fired);

            List<AppUser> staff = userRepository
                    .findByOrganizationUnitIdAndActiveTrueOrderByFullNameAscUsernameAsc(
                            unit.getId());

            assertTrue(staff.stream()
                    .anyMatch(u -> u.getUsername().equals("SidorovAA")));
            assertTrue(staff.stream()
                    .noneMatch(u -> u.getUsername().equals("KuznetsovVV")),
                    "уволенный сотрудник виден мастеру в очереди "
                            + "распределения");
        }

        @Test
        @DisplayName("ФИО на кириллице сортируется по алфавиту")
        void sortsCyrillicNamesAlphabetically() {
            OrganizationUnit unit = assemblyWorkshop();

            userRepository.save(newUser("e1", "Юрий", Role.SDP_SA_EXECUTOR, unit));
            userRepository.save(newUser("e2", "Аркадий", Role.SDP_SA_EXECUTOR, unit));
            userRepository.save(newUser("e3", "Борис", Role.SDP_SA_EXECUTOR, unit));

            List<String> names = userRepository
                    .findByOrganizationUnitIdAndActiveTrueOrderByFullNameAscUsernameAsc(
                            unit.getId())
                    .stream()
                    .map(AppUser::getFullName)
                    .toList();

            /*
             * На H2 этот же запрос дал бы [Аркадий, Борис, Юрий] лишь по
             * случайности байтового порядка латиницы в логине: utf8mb4_bin
             * поставил бы «Юрий» первым. Порядок проверяется по списку,
             * отфильтрованному по конкретному подразделению, где больше
             * никого нет.
             */
            int arkadiy = names.indexOf("Аркадий");
            int boris = names.indexOf("Борис");
            int yuriy = names.indexOf("Юрий");

            assertTrue(arkadiy >= 0 && boris >= 0 && yuriy >= 0,
                    "не все трое вернулись: " + names);
            assertTrue(arkadiy < boris && boris < yuriy,
                    "кириллица отсортирована не по алфавиту: " + names);
        }

        @Test
        @DisplayName("выборка по нескольким подразделениям службы качества")
        void selectsByUnitList() {
            OrganizationUnit unit = assemblyWorkshop();
            userRepository.save(newUser(
                    "VolkovNI", "Волков Н. И.",
                    Role.BVK_EXECUTOR, unit));

            List<AppUser> staff = userRepository
                    .findByOrganizationUnitIdInAndActiveTrueOrderByFullNameAscUsernameAsc(
                            List.of(unit.getId()));

            assertEquals(1, staff.size());
            assertEquals("VolkovNI", staff.getFirst().getUsername());
        }
    }

    @Nested
    @DisplayName("счёт по ролям")
    class RoleCount {

        @Test
        @DisplayName("отключённый администратор не считается действующим")
        void countsOnlyActiveAdmins() {
            long before = userRepository.countByRoleAndActiveTrue(Role.ADMIN);

            AppUser blocked = newUser(
                    "admin2", "Заблокированный админ",
                    Role.ADMIN, null);
            blocked.setActive(false);
            userRepository.saveAndFlush(blocked);

            assertEquals(before, userRepository.countByRoleAndActiveTrue(Role.ADMIN),
                    "отключённая учётная запись по-прежнему считается "
                            + "живым администратором");
            assertTrue(userRepository.countByRole(Role.ADMIN) > before,
                    "всего администраторов должно стать больше");
        }

        @Test
        @DisplayName("роль хранится текстом enum, а не порядковым номером")
        void storesRoleAsString() {
            userRepository.saveAndFlush(newUser(
                    "metallovEM", "Металлов Е. М.",
                    Role.SGM_MASTER, null));

            /*
             * EnumType.STRING + длина 50: если роль переименовать, старые
             * строки перестанут читаться. Проверка фиксирует, что в базе
             * лежит именно имя, а не число.
             */
            AppUser user = userRepository
                    .findByUsernameIgnoreCase("metallovem").orElseThrow();
            assertEquals(Role.SGM_MASTER, user.getRole());
        }
    }
}
