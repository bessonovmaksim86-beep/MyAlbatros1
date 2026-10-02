package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.StaffBindingView;
import ru.company.production.dto.StaffMemberView;
import ru.company.production.dto.StaffOperationGroup;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.Role;
import ru.company.production.entity.StaffBindingType;
import ru.company.production.entity.WorkPlace;
import ru.company.production.entity.WorkshopStaffBinding;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkPlaceRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.util.*;

/**
 * Сотрудники цеха и их привязки к операциям и рабочим местам.
 *
 * Модуль принадлежит руководителю цеха: он видит только сотрудников
 * своего подразделения и только те операции и рабочие места, которые
 * к этому подразделению отнесены. Граница цеха определяется
 * подразделением учётной записи руководителя, а если оно не заполнено —
 * цехом, соответствующим его роли (см. {@link #workshopUnit(AppUser)}).
 */
@Service
@RequiredArgsConstructor
public class WorkshopStaffService {

    /** Код роли «Исполнитель» в справочнике user_roles. */
    private static final String EXECUTOR_ROLE_CODE = "EXECUTOR";

    /**
     * Подразделение, которым руководит каждая из руководящих ролей.
     * Нужен как запасной вариант, когда у учётной записи подразделение
     * не указано, и для списка сотрудников: руководителю видны
     * исполнители своего подразделения.
     *
     * Значение — список кодов-кандидатов, а не один код. У бюро входного
     * контроля в справочнике две записи с разными кодами: «INPUT_CONTROL_
     * BUREAU» появилась в начальных данных, «Input Control Bureau» —
     * отдельной миграцией, когда модулю входного контроля понадобилось
     * своё подразделение. Коды различаются не регистром, а написaniem,
     * поэтому регистронезависимый поиск одного кода не определил бы,
     * какая из записей настоящая, и руководитель бюро получил бы пустой
     * список сотрудников. Кандидаты перебираются по порядку, первый
     * найденный выигрывает.
     */
    private static final Map<Role, List<String>> ROLE_UNIT_CODES =
            Map.ofEntries(
                    Map.entry(
                            Role.SDP_SPP_MASTER,
                            List.of("WORKSHOP_PRODUCTION_PREPARATION")
                    ),
                    Map.entry(
                            Role.SDP_SA_MASTER,
                            List.of("WORKSHOP_ASSEMBLY")
                    ),
                    Map.entry(
                            Role.SDP_SC_MASTER,
                            List.of("WORKSHOP_SETUP")
                    ),
                    Map.entry(
                            Role.SDP_ST_MASTER,
                            List.of("WORKSHOP_TEST")
                    ),
                    Map.entry(
                            Role.OMTSIK_MASTER,
                            List.of("SEMI_FINISHED_PRODUCT_WAREHOUSE")
                    ),
                    Map.entry(
                            Role.OMTSIK_С_MASTER,
                            List.of("ASSEMBLY_WAREHOUSE")
                    ),
                    Map.entry(
                            Role.BVK_MASTER,
                            List.of("Input Control Bureau", "INPUT_CONTROL_BUREAU")
                    )
            );

    /**
     * Роли, которым принадлежит сам модуль руководителя подразделения.
     *
     * Распределение работ и «Мои операции» открывают и другие
     * руководители — технологического бюро, диспетчерского отдела,
     * службы качества: у них своего цехового модуля нет, но операции
     * своего подразделения закрепляются за ними. Для них модуль
     * работает в урезанном виде — без сотрудников, календаря и
     * справочника операций, доступ к которым закрыт их роли.
     */

    private static final Set<Role> WORKSHOP_MODULE_ROLES = Set.of(
            Role.SDP_SPP_MASTER,
            Role.SDP_SA_MASTER,
            Role.SDP_SC_MASTER,
            Role.SDP_ST_MASTER,
            Role.OMTSIK_MASTER,
            Role.OMTSIK_С_MASTER,
            Role.BVK_MASTER
    );

    /**
     * Полный род подразделения: цех, склад, бюро или отдел.
     *
     * Отдел добавлен для руководителей вне цехового модуля — подписать
     * их страницу «Руководитель цеха» значило бы сообщить пользователю
     * чужую роль.
     */
    public enum UnitKind {

        WORKSHOP("цех", "цеха", "Руководитель цеха"),
        WAREHOUSE("склад", "склада", "Руководитель склада"),
        BUREAU("бюро", "бюро", "Руководитель бюро"),
        DEPARTMENT("отдел", "отдела", "Руководитель отдела");

        private final String word;
        private final String genitive;
        private final String moduleTitle;

        UnitKind(String word, String genitive, String moduleTitle) {
            this.word = word;
            this.genitive = genitive;
            this.moduleTitle = moduleTitle;
        }

        public String getWord() {
            return word;
        }

        public String getGenitive() {
            return genitive;
        }

        public String getModuleTitle() {
            return moduleTitle;
        }
    }

    private final UserRepository userRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final OperationRepository operationRepository;
    private final WorkPlaceRepository workPlaceRepository;
    private final WorkshopStaffBindingRepository bindingRepository;

    /**
     * Сотрудники цеха вместе с их привязками.
     *
     * Привязки читаются одним запросом по всем идентификаторам и
     * раскладываются в карту: обход списка в шаблоне не должен
     * порождать запрос на каждого сотрудника.
     */
    @Transactional(readOnly = true)
    public List<StaffMemberView> findStaff(Long unitId) {
        List<AppUser> employees = findEmployees(unitId);

        if (employees.isEmpty()) {
            return List.of();
        }

        Map<Long, List<StaffBindingView>> bindingsByEmployee =
                new LinkedHashMap<>();

        for (WorkshopStaffBinding binding
                : bindingRepository.findAllByEmployeeIds(
                employees.stream().map(AppUser::getId).toList())) {

            bindingsByEmployee
                    .computeIfAbsent(
                            binding.getEmployee().getId(),
                            key -> new ArrayList<>()
                    )
                    .add(toView(binding));
        }

        List<StaffMemberView> staff = new ArrayList<>();

        for (AppUser employee : employees) {
            staff.add(new StaffMemberView(
                    employee.getId(),
                    displayName(employee),
                    employee.getSpecialty(),
                    employee.getUsername(),
                    bindingsByEmployee.getOrDefault(
                            employee.getId(),
                            List.of()
                    )
            ));
        }

        return staff;
    }

    /**
     * Сотрудники цеха для выпадающего списка календаря.
     */
    @Transactional(readOnly = true)
    public List<AppUser> findEmployees(Long unitId) {
        if (unitId == null) {
            return List.of();
        }

        return userRepository
                .findByOrganizationUnitIdAndActiveTrueOrderByFullNameAscUsernameAsc(
                        unitId
                );
    }

    /**
     * Операции цеха с ролью «Исполнитель».
     */
    @Transactional(readOnly = true)
    public List<Operation> findWorkshopOperations(Long unitId) {
        if (unitId == null) {
            return List.of();
        }

        return operationRepository.findUnitExecutorOperations(
                unitId,
                EXECUTOR_ROLE_CODE
        );
    }

    /**
     * Операции, доступные для закрепления, сгруппированные по природе.
     *
     * Кроме цеховых работ с ролью «Исполнитель» в список попадают
     * операции получения комплектации того же подразделения. Раньше их
     * не отдавали вовсе: они отнесены к цеху или отделу напрямую и роль
     * «Исполнитель» не носят, поэтому запрос цеховых работ их не
     * возвращал, и закрепить получение комплектации за сотрудником было
     * нельзя — хотя по плану комплектацию под свои работы получает
     * именно этот цех.
     *
     * Граница та же, что у остальных выборок модуля: только своё
     * подразделение, поэтому чужие операции руководителю не видны.
     *
     * @param unitGenitive род подразделения в родительном падеже —
     *                     подпись первой группы («операции склада»
     *                     вместо «операции цеха»)
     */
    @Transactional(readOnly = true)
    public List<StaffOperationGroup> findBindingOperationGroups(
            Long unitId,
            String unitGenitive
    ) {
        if (unitId == null) {
            return List.of();
        }

        return StaffOperationGroup.of(
                findWorkshopOperations(unitId),
                operationRepository.findUnitKitReceiptOperations(
                        unitId,
                        OperationTypeCode.KIT_RECEIPT.name()
                ),
                unitGenitive
        );
    }

    /**
     * Все операции подразделения без фильтра по роли исполнителя —
     * список раздела «Операции и рабочие места».
     *
     * От выборки для закрепления отличается принципиально: там нужны
     * только те операции, которые сотрудник подразделения и выполняет,
     * а здесь руководитель видит весь свой перечень, включая операции
     * приёма, выдачи и перемещения, и заводит новые.
     */
    @Transactional(readOnly = true)
    public List<Operation> findAllUnitOperations(Long unitId) {
        if (unitId == null) {
            return List.of();
        }

        return operationRepository.findAllByOrganizationUnitId(unitId);
    }

    /**
     * Количество операций подразделения — счётчик бокового меню.
     */
    @Transactional(readOnly = true)
    public long countUnitOperations(Long unitId) {
        return unitId == null
                ? 0L
                : operationRepository.countByOrganizationUnitId(unitId);
    }

    /**
     * Действующие рабочие места цеха.
     */
    @Transactional(readOnly = true)
    public List<WorkPlace> findWorkshopWorkPlaces(Long unitId) {
        if (unitId == null) {
            return List.of();
        }

        return workPlaceRepository.findActiveByOrganizationUnitId(unitId);
    }

    /**
     * Закрепление за сотрудником операции или рабочего места.
     *
     * Оба объекта принимаются разом, потому что форма одна: пользователь
     * выбирает вид привязки переключателем, и «лишний» селект остаётся
     * пустым. Какая из сторон реальная — решает {@code bindingType},
     * вторая всегда обнуляется, иначе проверка схемы отклонила бы запись.
     */
    @Transactional
    public WorkshopStaffBinding bind(
            Long employeeId,
            StaffBindingType bindingType,
            Long operationId,
            Long workPlaceId,
            AppUser supervisor
    ) {
        if (employeeId == null) {
            throw new IllegalArgumentException("Выберите сотрудника");
        }

        if (bindingType == null) {
            throw new IllegalArgumentException(
                    "Выберите вид привязки: операция или рабочее место"
            );
        }

        AppUser employee = userRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Сотрудник не найден"
                ));

        requireSameWorkshop(employee, supervisor);

        Long unitId = workshopUnitId(supervisor);

        WorkshopStaffBinding binding = new WorkshopStaffBinding();

        binding.setEmployee(employee);
        binding.setBindingType(bindingType);

        if (bindingType == StaffBindingType.OPERATION) {
            if (operationId == null) {
                throw new IllegalArgumentException("Выберите операцию");
            }

            if (bindingRepository.existsByEmployeeIdAndOperationId(
                    employeeId, operationId)) {
                throw new IllegalArgumentException(
                        "Эта операция уже закреплена за сотрудником"
                );
            }

            Operation operation = operationRepository
                    .findDetailedById(operationId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Операция не найдена"
                    ));

            requireBelongsToWorkshop(
                    operation.getOrganizationUnit() == null
                            ? null
                            : operation.getOrganizationUnit().getId(),
                    unitId,
                    "Операция «" + operation.getOperationName().getName()
                            + "» относится к другому подразделению"
            );

            binding.setOperation(operation);

        } else {
            if (workPlaceId == null) {
                throw new IllegalArgumentException(
                        "Выберите рабочее место"
                );
            }

            if (bindingRepository.existsByEmployeeIdAndWorkPlaceId(
                    employeeId, workPlaceId)) {
                throw new IllegalArgumentException(
                        "Это рабочее место уже закреплено за сотрудником"
                );
            }

            WorkPlace place = workPlaceRepository
                    .findDetailedById(workPlaceId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Рабочее место не найдено"
                    ));

            requireBelongsToWorkshop(
                    place.getOrganizationUnit() == null
                            ? null
                            : place.getOrganizationUnit().getId(),
                    unitId,
                    "Рабочее место «" + place.getCode()
                            + "» относится к другому подразделению"
            );

            binding.setWorkPlace(place);
        }

        try {
            return bindingRepository.saveAndFlush(binding);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "Такая привязка уже существует",
                    exception
            );
        }
    }

    /**
     * Снятие привязки.
     *
     * Удаляется именно запись привязки, а не операция и не рабочее
     * место: справочники общие для всего предприятия, и удаление
     * закрепления не должно их затрагивать.
     */
    @Transactional
    public void unbind(Long bindingId, AppUser supervisor) {
        if (bindingId == null) {
            throw new IllegalArgumentException("Привязка не указана");
        }

        WorkshopStaffBinding binding = bindingRepository
                .findWithTargetsById(bindingId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Привязка не найдена"
                ));

        requireSameWorkshop(binding.getEmployee(), supervisor);

        bindingRepository.delete(binding);
        bindingRepository.flush();
    }

    /**
     * Подразделение цеха руководителя.
     *
     * Приоритет у фактического подразделения учётной записи: справочник
     * ведёт администратор, и если руководитель переведён в другой цех,
     * модуль должен показать новый цех, а не тот, что следует из роли.
     */
    @Transactional(readOnly = true)
    public Long workshopUnitId(AppUser supervisor) {
        return workshopUnit(supervisor)
                .map(unit -> unit.getId())
                .orElse(null);
    }

    /**
     * Наименование цеха для заголовков страниц.
     */
    @Transactional(readOnly = true)
    public String workshopName(AppUser supervisor) {
        return workshopUnit(supervisor)
                .map(unit -> unit.getName())
                .orElse("Подразделение не назначено");
    }

    /*
     * Подразделение руководителя.
     *
     * Приоритет у фактического подразделения учётной записи. Если оно
     * не заполнено, подразделение берётся из роли: поиск по службе и
     * коду, а когда служба тоже не указана — по коду, который уникален
     * в справочнике. Иначе руководители без заполненной карточки
     * подразделения получали пустой модуль вместо своего подразделения.
     *
     * Кодов у роли может быть несколько (см. {@link #ROLE_UNIT_CODES}),
     * поэтому перебор идёт по кандидатам, а не одним запросом: иначе
     * руководитель бюро входного контроля зависал бы на записи
     * справочника, к которой сотрудники не привязаны.
     */
    private Optional<ru.company.production.entity.OrganizationUnit> workshopUnit(
            AppUser supervisor
    ) {
        if (supervisor == null) {
            return Optional.empty();
        }

        if (supervisor.getOrganizationUnit() != null) {
            return Optional.of(supervisor.getOrganizationUnit());
        }

        List<String> unitCodes = ROLE_UNIT_CODES.get(supervisor.getRole());

        if (unitCodes == null || unitCodes.isEmpty()) {
            return Optional.empty();
        }

        for (String unitCode : unitCodes) {
            Optional<ru.company.production.entity.OrganizationUnit> unit =
                    supervisor.getService() != null
                            ? organizationUnitRepository
                            .findByServiceIdAndCodeIgnoreCase(
                                    supervisor.getService().getId(),
                                    unitCode
                            )
                            : organizationUnitRepository
                            .findByCodeIgnoreCase(unitCode);

            if (unit.isPresent()) {
                return unit;
            }
        }

        return Optional.empty();
    }

    /**
     * Род подразделения руководителя: влияет только на подписи, границы
     * модуля от него не зависят.
     *
     * Берётся из роли, а не из названия подразделения: справочник
     * ведёт администратор и может переименовать склад, тогда как право
     * на модуль у роли именно руководящая, и подписи вроде «Сотрудники
     * цеха» над списком склада читались бы как ошибка границ модуля.
     */
    public UnitKind unitKind(AppUser supervisor) {
        if (supervisor == null || supervisor.getRole() == null) {
            return UnitKind.WORKSHOP;
        }

        return ROLE_UNIT_KINDS.getOrDefault(
                supervisor.getRole(),
                UnitKind.WORKSHOP
        );
    }

    /*
     * Руководители вне цехового модуля (техбюро, диспетчерский отдел,
     * диспетчер, служба качества) подписываются отделом: назвать
     * начальника техбюро «руководителем цеха» — сообщить пользователю
     * чужую роль прямо в заголовке меню.
     */
    private static final Map<Role, UnitKind> ROLE_UNIT_KINDS =
            Map.ofEntries(
                    Map.entry(Role.SDP_SPP_MASTER,  UnitKind.WORKSHOP),
                    Map.entry(Role.SDP_SA_MASTER,   UnitKind.WORKSHOP),
                    Map.entry(Role.SDP_SC_MASTER,   UnitKind.WORKSHOP),
                    Map.entry(Role.SDP_ST_MASTER,   UnitKind.WORKSHOP),
                    Map.entry(Role.OMTSIK_MASTER,   UnitKind.WAREHOUSE),
                    Map.entry(Role.OMTSIK_С_MASTER, UnitKind.WAREHOUSE),
                    Map.entry(Role.BVK_MASTER,      UnitKind.BUREAU),
                    Map.entry(
                            Role.SDP_SERVICE_HEAD_TECHNOLOGY,
                            UnitKind.DEPARTMENT
                    ),
                    Map.entry(
                            Role.SDP_SERVICE_HEAD_DISPATCHER,
                            UnitKind.DEPARTMENT
                    ),
                    Map.entry(Role.DISPATCHER, UnitKind.DEPARTMENT),
                    Map.entry(Role.SK_GENERAL, UnitKind.DEPARTMENT)
            );

    /**
     * Ведёт ли пользователь полный цеховой модуль: сотрудники, операции
     * подразделения и календарь отсутствий.
     *
     * Нужен боковому меню, а не контроллеру: доступ к этим разделам
     * закрыт перед ролью в {@code SecurityConfig}, но пункт меню от
     * этого не исчезает, и руководитель вне цеха (техбюро, диспетчер,
     * служба качества) получил бы в своём меню заведомо запрещённые
     * ссылки. Граница берётся из того же набора ролей, что у модуля.
     */
    public boolean hasFullModule(AppUser supervisor) {
        return supervisor != null
                && supervisor.getRole() != null
                && WORKSHOP_MODULE_ROLES.contains(supervisor.getRole());
    }
    /**
     * Сотрудник и руководитель должны относиться к одному цеху — иначе
     * руководитель одного цеха мог бы закреплять операции чужого.
     * Метод публичный: та же граница нужна календарю отсутствий.
     */
    public void requireSameWorkshop(AppUser employee, AppUser supervisor) {
        Long supervisorUnitId = workshopUnitId(supervisor);

        if (supervisorUnitId == null) {
            throw new IllegalStateException(
                    "Вашему подразделению не сопоставлен цех: "
                            + "обратитесь к администратору"
            );
        }

        if (employee.getOrganizationUnit() == null
                || !supervisorUnitId.equals(
                employee.getOrganizationUnit().getId())) {

            throw new IllegalArgumentException(
                    "Сотрудник «" + displayName(employee)
                            + "» относится к другому подразделению"
            );
        }
    }

    private static void requireBelongsToWorkshop(
            Long targetUnitId,
            Long workshopUnitId,
            String message
    ) {
        if (workshopUnitId == null || !workshopUnitId.equals(targetUnitId)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static StaffBindingView toView(WorkshopStaffBinding binding) {
        if (binding.getBindingType() == StaffBindingType.OPERATION) {
            Operation operation = binding.getOperation();

            return new StaffBindingView(
                    binding.getId(),
                    StaffBindingType.OPERATION.getDisplayName(),
                    operation == null
                            || operation.getOperationName() == null
                            ? "—"
                            : operation.getOperationName().getCode(),
                    operation == null
                            || operation.getOperationName() == null
                            ? "Операция удалена из справочника"
                            : operation.getOperationName().getName(),
                    operation == null
                            || operation.getOperationType() == null
                            ? "—"
                            : operation.getOperationType().getName(),
                    operation == null
                            || operation.getExecutorRole() == null
                            ? "—"
                            : operation.getExecutorRole().getDisplayName(),
                    operation != null
                            && operation.getOperationType() != null
                            && OperationTypeCode.KIT_RECEIPT
                                    .name()
                                    .equals(
                                            operation
                                                    .getOperationType()
                                                    .getCode()
                                    )
            );
        }

        WorkPlace place = binding.getWorkPlace();

        return new StaffBindingView(
                binding.getId(),
                StaffBindingType.WORK_PLACE.getDisplayName(),
                place == null ? "—" : place.getCode(),
                place == null
                        ? "Рабочее место удалено из справочника"
                        : place.getName(),
                place == null
                        || place.getWorkPlaceType() == null
                        ? "—"
                        : place.getWorkPlaceType().getDisplayName(),
                place == null
                        || place.getExecutorRole() == null
                        ? "—"
                        : place.getExecutorRole().getDisplayName(),
                false
        );
    }

    /**
     * Фамилия и инициалы: список цеха читают быстро, полное ФИО
     * в узкой колонке таблицы не помещается.
     */
    public static String displayName(AppUser user) {
        String fullName = user.getFullName();

        if (fullName == null || fullName.isBlank()) {
            return user.getUsername();
        }

        String[] parts = fullName.trim().split("\\s+");

        if (parts.length == 1) {
            return parts[0];
        }

        Locale locale = Locale.forLanguageTag("ru");

        return parts[0]
                + " "
                + parts[1].substring(0, 1).toUpperCase(locale)
                + "."
                + (parts.length > 2
                ? " " + parts[2].substring(0, 1).toUpperCase(locale) + "."
                : "");
    }
}
