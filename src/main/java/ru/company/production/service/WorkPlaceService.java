package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.WorkPlaceForm;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductionService;
import ru.company.production.entity.UserRole;
import ru.company.production.entity.WorkPlace;
import ru.company.production.entity.WorkPlaceType;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;
import ru.company.production.repository.UserRoleRepository;
import ru.company.production.repository.WorkPlaceRepository;
import ru.company.production.repository.WorkPlaceTypeRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkPlaceService {

    private final WorkPlaceRepository workPlaceRepository;
    private final WorkPlaceTypeRepository workPlaceTypeRepository;
    private final ProductionServiceRepository productionServiceRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final UserRoleRepository roleUserRepository;

    @Transactional(readOnly = true)
    public List<WorkPlace> findAll() {
        return workPlaceRepository.findAllDetailed();
    }

    @Transactional(readOnly = true)
    public WorkPlace get(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор рабочего места"
            );
        }

        return workPlaceRepository
                .findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Рабочее место с идентификатором "
                                + id
                                + " не найдено"
                ));
    }

    @Transactional(readOnly = true)
    public WorkPlaceForm getForm(Long id) {
        WorkPlace place = get(id);

        WorkPlaceForm form = new WorkPlaceForm();

        form.setCode(place.getCode());
        form.setName(place.getName());
        form.setNote(place.getNote());

        form.setWorkPlaceTypeId(
                place.getWorkPlaceType().getId()
        );

        form.setExecutorRoleId(
                place.getExecutorRole().getId()
        );

        form.setServiceId(
                place.getService().getId()
        );

        if (place.getOrganizationUnit() != null) {
            form.setOrganizationUnitId(
                    place.getOrganizationUnit().getId()
            );
        }

        return form;
    }

    @Transactional
    public WorkPlace create(WorkPlaceForm form) {
        String normalizedCode = normalizeCode(form.getCode());

        if (workPlaceRepository.existsByCode(normalizedCode)) {
            throw new IllegalArgumentException(
                    "Рабочее место с кодом «"
                            + normalizedCode
                            + "» уже существует"
            );
        }

        WorkPlace place = new WorkPlace();

        applyForm(place, form, normalizedCode);

        return workPlaceRepository.save(place);
    }

    @Transactional
    public WorkPlace update(Long id, WorkPlaceForm form) {
        WorkPlace place = get(id);

        String normalizedCode = normalizeCode(form.getCode());

        if (workPlaceRepository
                .existsByCodeAndIdNot(normalizedCode, id)) {

            throw new IllegalArgumentException(
                    "Рабочее место с кодом «"
                            + normalizedCode
                            + "» уже существует"
            );
        }

        applyForm(place, form, normalizedCode);

        return workPlaceRepository.save(place);
    }

    @Transactional
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор рабочего места"
            );
        }

        WorkPlace place = workPlaceRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Рабочее место с идентификатором "
                                + id
                                + " не найдено"
                ));

        workPlaceRepository.delete(place);
        workPlaceRepository.flush();
    }

    private void applyForm(
            WorkPlace place,
            WorkPlaceForm form,
            String normalizedCode
    ) {
        requireId(
                form.getWorkPlaceTypeId(),
                "Выберите тип рабочего места"
        );

        requireId(
                form.getExecutorRoleId(),
                "Выберите тип исполнителя"
        );

        requireId(
                form.getServiceId(),
                "Выберите службу"
        );

        WorkPlaceType workPlaceType =
                workPlaceTypeRepository
                        .findById(form.getWorkPlaceTypeId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Выбранный тип рабочего места не найден"
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

        place.setCode(normalizedCode);
        place.setName(normalizeName(form.getName()));
        place.setWorkPlaceType(workPlaceType);
        place.setExecutorRole(executorRole);
        place.setService(service);
        place.setOrganizationUnit(organizationUnit);
        place.setNote(normalizeNote(form.getNote()));
    }

    private static void requireId(Long id, String message) {
        if (id == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException(
                    "Введите код рабочего места"
            );
        }

        String normalized = code
                .trim()
                .replaceAll("\\s+", " ");

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Введите код рабочего места"
            );
        }

        if (normalized.length() > 50) {
            throw new IllegalArgumentException(
                    "Код рабочего места не должен превышать 50 символов"
            );
        }

        return normalized;
    }

    private static String normalizeName(String name) {
        if (name == null) {
            throw new IllegalArgumentException(
                    "Введите наименование рабочего места"
            );
        }

        String normalized = name
                .trim()
                .replaceAll("\\s+", " ");

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Введите наименование рабочего места"
            );
        }

        if (normalized.length() > 255) {
            throw new IllegalArgumentException(
                    "Наименование рабочего места не должно превышать 255 символов"
            );
        }

        return normalized;
    }

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
}
