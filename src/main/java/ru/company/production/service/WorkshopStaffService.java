package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.StaffBindingView;
import ru.company.production.dto.StaffMemberView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.Role;
import ru.company.production.entity.StaffBindingType;
import ru.company.production.entity.WorkPlace;
import ru.company.production.entity.WorkshopStaffBinding;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkPlaceRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
     * Цех, которым руководит каждая из руководящих ролей. Нужен как
     * запасной вариант, когда у учётной записи подразделение не указано,
     * и для списка сотрудников: руководителю видны исполнители своего цеха.
     */
    private static final Map<Role, String> ROLE_UNIT_CODES = Map.of(
            Role.SDP_SPP_MASTER, "WORKSHOP_PRODUCTION_PREPARATION",
            Role.SDP_SA_MASTER, "WORKSHOP_ASSEMBLY",
            Role.SDP_SC_MASTER, "WORKSHOP_SETUP",
            Role.SDP_ST_MASTER, "WORKSHOP_TEST"
    );

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
     * Подразделение цеха руководителя.
     *
     * Приоритет у фактического подразделения учётной записи. Если оно
     * не заполнено, цех берётся из роли: поиск по службе и коду, а
     * когда служба тоже не указана — по коду цеха, который уникален в
     * справочнике. Иначе руководители цехов без заполненной карточки
     * подразделения получали пустой модуль вместо своего цеха.
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

        String unitCode = ROLE_UNIT_CODES.get(supervisor.getRole());

        if (unitCode == null) {
            return Optional.empty();
        }

        if (supervisor.getService() != null) {
            return organizationUnitRepository.findByServiceIdAndCodeIgnoreCase(
                    supervisor.getService().getId(),
                    unitCode
            );
        }

        return organizationUnitRepository.findByCodeIgnoreCase(unitCode);
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
                            : operation.getExecutorRole().getDisplayName()
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
                        : place.getExecutorRole().getDisplayName()
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
