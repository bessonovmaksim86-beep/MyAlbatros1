package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationType;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.Role;
import ru.company.production.entity.UserRole;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.TechProcessOperationControlParamRepository;
import ru.company.production.repository.TechProcessOperationRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Фиксирующий тест ролевой политики распределения работ.
 *
 * <p>Тест написан на поведение, а не на реализацию: он утверждает, какая
 * роль руководящая, какой раздел распределения открыт и где работу берут
 * из перечня подразделения. Состав наборов ролей здесь переписан руками,
 * а не взят из {@code WorkshopDistributionService}, — иначе тест
 * подтверждал бы собственную догадку и не заметил бы, как роль потеряла
 * или получила доступ.
 *
 * <p>Смысл теста в том, что большой сервис будут разбивать. Признаки
 * {@code isSupervisor}, {@code canDistribute} и {@code isUnitPool}
 * держат меню, уведомления и право проводить закрепление одновременно,
 * и расхождение между ними выглядит как «меню звало в 403». Пока
 * логика живёт в трёх местах, её перенос в отдельный класс обязан
 * оставить эти ответы теми же.
 *
 * <p>Зависимости сервиса подставлены пустыми моками: проверяемые
 * признаки читают только роль учётной записи и справочные коды
 * операции, к базе не обращаются.
 */
@ExtendWith(MockitoExtension.class)
class WorkshopDistributionServicePolicyTest {

    @Mock private OrderScheduleRowRepository scheduleRowRepo;
    @Mock private WorkshopStaffBindingRepository bindingRepo;
    @Mock private TechProcessRepository techProcessRepo;
    @Mock private EmployeeAbsenceRepository absenceRepo;
    @Mock private UserRepository userRepository;
    @Mock private WorkshopStaffService staffService;
    @Mock private WorkshopDistributionSerialService serialService;
    @Mock private TechProcessOperationRepository routeNodeRepo;
    @Mock private TechProcessOperationControlParamRepository controlParamLinkRepo;
    @Mock private ControlReturnService controlReturnService;
    @Mock private ReworkReturnService reworkReturnService;
    @Mock private UnitHandoverService handoverService;
    @Mock private WorkingDaysCalculator workingDays;

    private WorkshopDistributionService service;

    @BeforeEach
    void setUp() {
        service = new WorkshopDistributionService(
                scheduleRowRepo, bindingRepo, techProcessRepo,
                absenceRepo, userRepository, staffService, serialService,
                routeNodeRepo, controlParamLinkRepo, controlReturnService,
                reworkReturnService, handoverService, workingDays
        );
    }

    private static AppUser user(Role role) {
        AppUser appUser = new AppUser();
        appUser.setId(1L);
        appUser.setUsername("u");
        appUser.setRole(role);
        return appUser;
    }

    /**
     * Роли, которым планировщик отдаёт получение комплектации как личную
     * работу: мастера цехов и складов, начальники бюро, руководители
     * служб, диспетчер и руководитель службы качества.
     */
    private static final Set<Role> EXPECTED_SUPERVISORS = EnumSet.of(
            Role.SDP_SPP_MASTER,
            Role.SDP_SA_MASTER,
            Role.SDP_SC_MASTER,
            Role.SDP_ST_MASTER,
            Role.OMTSIK_MASTER,
            Role.OMTSIK_С_MASTER,
            Role.BVK_MASTER,
            Role.BTK_MASTER,
            Role.SGP_MASTER,
            Role.SGM_MASTER,
            Role.SDP_SERVICE_HEAD_TECHNOLOGY,
            Role.SDP_SERVICE_HEAD_DISPATCHER,
            Role.DISPATCHER,
            Role.SK_GENERAL
    );

    /** Бюро контроля и служба качества: работу берут из перечня подразделения. */
    private static final Set<Role> EXPECTED_UNIT_POOLS = EnumSet.of(
            Role.BVK_EXECUTOR,
            Role.BVK_MASTER,
            Role.BTK_EXECUTOR,
            Role.BTK_MASTER,
            Role.SK_GENERAL
    );

    /** Руководящие роли без собственной очереди распределения. */
    private static final Set<Role> EXPECTED_NO_DISTRIBUTION = EnumSet.of(
            Role.BVK_MASTER,
            Role.BTK_MASTER
    );

    @Nested
    @DisplayName("руководящая роль")
    class Supervisor {

        @Test
        @DisplayName("набор руководящих ролей задан ровно")
        void everyRoleAnswered() {
            for (Role role : Role.values()) {
                assertEquals(EXPECTED_SUPERVISORS.contains(role),
                        service.isSupervisor(user(role)),
                        role + ": признак руководящей роли изменился");
            }
        }

        @Test
        @DisplayName("исполнитель не руководящая роль")
        void executorIsNotSupervisor() {
            assertFalse(service.isSupervisor(user(Role.SDP_SA_EXECUTOR)));
            assertFalse(service.isSupervisor(user(Role.BVK_EXECUTOR)));
            assertFalse(service.isSupervisor(user(Role.ADMIN)));
        }

        @Test
        @DisplayName("пустая учётная запись и пустая роль не руководящие")
        void nullSafe() {
            assertFalse(service.isSupervisor(null));
            assertFalse(service.isSupervisor(user(null)));
        }
    }

    @Nested
    @DisplayName("раздел распределения")
    class DistributionAccess {

        @Test
        @DisplayName("открыт всем руководящим ролям, кроме бюро")
        void everyRoleAnswered() {
            for (Role role : Role.values()) {
                boolean expected = EXPECTED_SUPERVISORS.contains(role)
                        && !EXPECTED_NO_DISTRIBUTION.contains(role);

                assertEquals(expected, service.canDistribute(user(role)),
                        role + ": доступ к разделу распределения изменился");
            }
        }

        @Test
        @DisplayName("руководителю бюро закрыт, хотя роль руководящая")
        void bureauHeadExcluded() {
            assertTrue(service.isSupervisor(user(Role.BVK_MASTER)));
            assertFalse(service.canDistribute(user(Role.BVK_MASTER)));

            assertTrue(service.isSupervisor(user(Role.BTK_MASTER)));
            assertFalse(service.canDistribute(user(Role.BTK_MASTER)));
        }

        @Test
        @DisplayName("руководителю службы качества раздел открыт")
        void qualityHeadKeepsSection() {
            /*
             * Он входит в пул подразделения наравне с бюро, но отсекать
             * ему раздел нельзя: службу качества в бюро не превращают.
             * Это самое тонкое место набора — его легко «починить»
             * неверно.
             */
            assertTrue(service.isUnitPool(user(Role.SK_GENERAL)));
            assertTrue(service.canDistribute(user(Role.SK_GENERAL)));
        }

        @Test
        @DisplayName("пустая учётная запись не получает раздел")
        void nullSafe() {
            assertFalse(service.canDistribute(null));
            assertFalse(service.canDistribute(user(null)));
        }
    }

    @Nested
    @DisplayName("перечень подразделения")
    class UnitPool {

        @Test
        @DisplayName("набор ролей пула задан ровно")
        void everyRoleAnswered() {
            for (Role role : Role.values()) {
                assertEquals(EXPECTED_UNIT_POOLS.contains(role),
                        service.isUnitPool(user(role)),
                        role + ": признак пула подразделения изменился");
            }
        }

        @Test
        @DisplayName("мастеру цеха пул не принадлежит: у него очередь закрепления")
        void workshopMasterIsNotPool() {
            assertFalse(service.isUnitPool(user(Role.SDP_SA_MASTER)));
            assertFalse(service.isUnitPool(user(Role.SGP_MASTER)));
        }

        @Test
        @DisplayName("пустая учётная запись не попадает в пул")
        void nullSafe() {
            assertFalse(service.isUnitPool(null));
            assertFalse(service.isUnitPool(user(null)));
        }
    }

    @Nested
    @DisplayName("срок работ в неделях")
    class Weeks {

        @Test
        @DisplayName("всё, что вне диапазона, становится двумя неделями")
        void clampedToDefault() {
            assertEquals(2, WorkshopDistributionService.normalizeWeeks(null));
            assertEquals(2, WorkshopDistributionService.normalizeWeeks(0));
            assertEquals(2, WorkshopDistributionService.normalizeWeeks(-5));
            assertEquals(2, WorkshopDistributionService.normalizeWeeks(13));
            assertEquals(2, WorkshopDistributionService.normalizeWeeks(99999));
        }

        @Test
        @DisplayName("границы диапазона сохраняются")
        void boundariesKept() {
            assertEquals(1, WorkshopDistributionService.normalizeWeeks(1));
            assertEquals(12, WorkshopDistributionService.normalizeWeeks(12));
        }
    }

    @Nested
    @DisplayName("нераспределяемая операция")
    class NonDistributableOperation {

        private static OrderScheduleRow row(
                OperationTypeCode type,
                String executorRoleCode
        ) {
            Operation operation = new Operation();

            if (type != null) {
                OperationType operationType = new OperationType();
                operationType.setCode(type.name());
                operation.setOperationType(operationType);
            }

            if (executorRoleCode != null) {
                UserRole executorRole = new UserRole();
                executorRole.setCode(executorRoleCode);
                operation.setExecutorRole(executorRole);
            }

            OrderScheduleRow row = new OrderScheduleRow();
            row.setOperation(operation);
            return row;
        }

        @Test
        @DisplayName("получение комплектации и распределение работ не распределяют")
        void byOperationType() {
            assertTrue(WorkshopDistributionService.isNonDistributableOperation(
                    row(OperationTypeCode.KIT_RECEIPT, null)));
            assertTrue(WorkshopDistributionService.isNonDistributableOperation(
                    row(OperationTypeCode.WORK_DISTRIBUTION, null)));
        }

        @Test
        @DisplayName("операцию руководящей роли ведёт сам руководитель")
        void byExecutorRole() {
            assertTrue(WorkshopDistributionService.isNonDistributableOperation(
                    row(OperationTypeCode.PRODUCTION,
                            "HEAD_OF_WORKSHOP_OR_DEPARTMENT")));
            assertTrue(WorkshopDistributionService.isNonDistributableOperation(
                    row(OperationTypeCode.PRODUCTION, "SERVICE_HEAD")));
        }

        @Test
        @DisplayName("обычную операцию цеха распределяют")
        void ordinaryOperationIsDistributable() {
            assertFalse(WorkshopDistributionService.isNonDistributableOperation(
                    row(OperationTypeCode.PRODUCTION, "WORKSHOP_EXECUTOR")));
            assertFalse(WorkshopDistributionService.isNonDistributableOperation(
                    row(OperationTypeCode.KITTING, null)));
        }

        @Test
        @DisplayName("пустая строка и строка без операции не считаются нераспределяемыми")
        void nullSafe() {
            assertFalse(WorkshopDistributionService
                    .isNonDistributableOperation(null));
            assertFalse(WorkshopDistributionService
                    .isNonDistributableOperation(new OrderScheduleRow()));
        }
    }

    @Nested
    @DisplayName("справочные коды, общие с другими сервисами")
    class SharedCodes {

        @Test
        @DisplayName("типы операций без распределения")
        void nonDistributionTypes() {
            assertEquals(Set.of("KIT_RECEIPT", "WORK_DISTRIBUTION"),
                    WorkshopDistributionService.NON_DISTRIBUTION_TYPE_CODES);
        }

        @Test
        @DisplayName("роли-исполнители, которые носит руководитель")
        void supervisorExecutorRoles() {
            assertEquals(
                    Set.of("HEAD_OF_WORKSHOP_OR_DEPARTMENT", "SERVICE_HEAD"),
                    WorkshopDistributionService.SUPERVISOR_EXECUTOR_ROLE_CODES);
        }

        @Test
        @DisplayName("срок по умолчанию и верхняя граница")
        void weekBounds() {
            assertEquals(2, WorkshopDistributionService.DEFAULT_WEEKS);
            assertEquals(12, WorkshopDistributionService.MAX_WEEKS);
        }
    }
}
