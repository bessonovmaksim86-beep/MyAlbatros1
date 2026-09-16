package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.OperationForm;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationName;
import ru.company.production.entity.OperationType;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductionService;
import ru.company.production.entity.UserRole;
import ru.company.production.repository.OperationNameRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OperationTypeRepository;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;
import ru.company.production.repository.UserRoleRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OperationService {

    private final OperationRepository operationRepository;
    private final OperationNameRepository operationNameRepository;
    private final OperationTypeRepository operationTypeRepository;
    private final ProductionServiceRepository productionServiceRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final UserRoleRepository roleUserRepository;

    @Transactional(readOnly = true)
    public List<Operation> findAll() {
        return operationRepository
                .findAllByOrderByOperationName_NameAsc();
    }

    @Transactional(readOnly = true)
    public Operation get(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор операции"
            );
        }

        return operationRepository
                .findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Операция с идентификатором "
                                + id
                                + " не найдена"
                ));
    }

    @Transactional(readOnly = true)
    public OperationForm getForm(Long id) {
        Operation operation = get(id);

        OperationForm form = new OperationForm();

        form.setOperationName(
                operation.getOperationName().getName()
        );

        form.setOperationTypeId(
                operation.getOperationType().getId()
        );

        form.setExecutorRoleId(
                operation.getExecutorRole().getId()
        );

        form.setServiceId(
                operation.getService().getId()
        );

        if (operation.getOrganizationUnit() != null) {
            form.setOrganizationUnitId(
                    operation.getOrganizationUnit().getId()
            );
        }

        form.setNote(operation.getNote());

        return form;
    }

    @Transactional
    public Operation create(OperationForm form) {
        String normalizedName =
                normalizeOperationName(form.getOperationName());

        validateDuplicateForCreate(form, normalizedName);

        Operation operation = new Operation();

        applyForm(
                operation,
                form,
                normalizedName
        );

        return operationRepository.save(operation);
    }

    @Transactional
    public Operation update(
            Long id,
            OperationForm form
    ) {
        Operation operation = get(id);

        String normalizedName =
                normalizeOperationName(form.getOperationName());

        validateDuplicateForUpdate(
                id,
                form,
                normalizedName
        );

        applyForm(
                operation,
                form,
                normalizedName
        );

        return operationRepository.save(operation);
    }

    @Transactional
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор операции"
            );
        }

        Operation operation = operationRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Операция с идентификатором "
                                + id
                                + " не найдена"
                ));

        operationRepository.delete(operation);
        operationRepository.flush();
    }

    private void applyForm(
            Operation operation,
            OperationForm form,
            String normalizedName
    ) {
        requireId(
                form.getOperationTypeId(),
                "Выберите тип операции"
        );

        requireId(
                form.getExecutorRoleId(),
                "Выберите тип исполнителя"
        );

        requireId(
                form.getServiceId(),
                "Выберите службу"
        );

        OperationName operationName =
                findOrCreateOperationName(normalizedName);

        OperationType operationType =
                operationTypeRepository
                        .findById(form.getOperationTypeId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Выбранный тип операции не найден"
                        ));

        UserRole executorRole =
                roleUserRepository
                        .findById(form.getExecutorRoleId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Выбранная роль исполнителя не найдена"
                        ));

        ProductionService service =
                productionServiceRepository
                        .findById(form.getServiceId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Выбранная служба не найдена"
                        ));

        OrganizationUnit organizationUnit = null;

        if (form.getOrganizationUnitId() != null) {
            organizationUnit = organizationUnitRepository
                    .findById(form.getOrganizationUnitId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Выбранное подразделение не найдено"
                    ));

            if (organizationUnit.getService() == null
                    || !organizationUnit
                    .getService()
                    .getId()
                    .equals(service.getId())) {

                throw new IllegalArgumentException(
                        "Выбранное подразделение не относится "
                                + "к выбранной службе"
                );
            }
        }

        operation.setOperationName(operationName);
        operation.setOperationType(operationType);
        operation.setExecutorRole(executorRole);
        operation.setService(service);
        operation.setOrganizationUnit(organizationUnit);
        operation.setNote(normalizeNote(form.getNote()));
    }

    private OperationName findOrCreateOperationName(
            String normalizedName
    ) {
        return operationNameRepository
                .findFirstByNameIgnoreCase(normalizedName)
                .map(operationName -> {
                    if (!operationName.isActive()) {
                        operationName.setActive(true);
                    }

                    return operationName;
                })
                .orElseGet(() -> {
                    OperationName operationName =
                            new OperationName();

                    operationName.setName(normalizedName);
                    operationName.setCode(generateOperationCode());
                    operationName.setActive(true);

                    return operationNameRepository.save(operationName);
                });
    }

    private void validateDuplicateForCreate(
            OperationForm form,
            String normalizedName
    ) {
        long duplicateCount =
                operationRepository.countDuplicates(
                        normalizedName,
                        form.getOperationTypeId(),
                        form.getServiceId(),
                        form.getOrganizationUnitId(),
                        form.getExecutorRoleId()
                );

        if (duplicateCount > 0) {
            throw new IllegalArgumentException(
                    "Операция с выбранными параметрами уже существует"
            );
        }
    }

    private void validateDuplicateForUpdate(
            Long operationId,
            OperationForm form,
            String normalizedName
    ) {
        long duplicateCount =
                operationRepository.countDuplicatesForUpdate(
                        normalizedName,
                        form.getOperationTypeId(),
                        form.getServiceId(),
                        form.getOrganizationUnitId(),
                        form.getExecutorRoleId(),
                        operationId
                );

        if (duplicateCount > 0) {
            throw new IllegalArgumentException(
                    "Операция с выбранными параметрами уже существует"
            );
        }
    }

    private static void requireId(
            Long id,
            String message
    ) {
        if (id == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /*
     * Пустое примечание храним как null, чтобы в БД не попадал
     * пробельный мусор, а поле в интерфейсе оставалось пустым.
     */
    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }

        String normalized = note.trim();

        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.length() > 2000) {
            throw new IllegalArgumentException(
                    "Примечание не должно превышать 2000 символов"
            );
        }

        return normalized;
    }

    private static String normalizeOperationName(
            String operationName
    ) {
        if (operationName == null) {
            throw new IllegalArgumentException(
                    "Введите наименование операции"
            );
        }

        String normalized = operationName
                .trim()
                .replaceAll("\\s+", " ");

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Введите наименование операции"
            );
        }

        if (normalized.length() > 255) {
            throw new IllegalArgumentException(
                    "Наименование операции не должно превышать 255 символов"
            );
        }

        return normalized;
    }

    private static String generateOperationCode() {
        return "OP-"
                + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase();
    }
}