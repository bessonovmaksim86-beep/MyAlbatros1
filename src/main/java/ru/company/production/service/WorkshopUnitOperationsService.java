package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.WorkPlaceForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.WorkPlace;
import ru.company.production.repository.OrganizationUnitRepository;

import java.util.List;

/**
 * Рабочие места подразделения руководителя.
 *
 * Раздел даёт руководителю склада, бюро или цеха заводить рабочие места
 * своего подразделения. Граница при этом не мягче цеховой — служба и
 * подразделение не берутся из формы, а подставляются из учётной записи
 * руководителя, поэтому отправить рабочее место в чужое подразделение
 * нельзя даже вручную изменив запрос.
 *
 * Само сохранение выполняет {@link WorkPlaceService}: проверка дублей,
 * нормализация наименований и кодов одна на систему.
 */
@Service
@RequiredArgsConstructor
public class WorkshopUnitOperationsService {

    private final WorkshopStaffService staffService;
    private final OperationService operationService;
    private final WorkPlaceService workPlaceService;
    private final OrganizationUnitRepository organizationUnitRepository;

    /**
     * Операции подразделения — список раздела
     * «Операции и рабочие места».
     */
    @Transactional(readOnly = true)
    public List<Operation> findOperations(AppUser supervisor) {
        return staffService.findAllUnitOperations(requireUnitId(supervisor));
    }

    /**
     * Действующие рабочие места подразделения.
     */
    @Transactional(readOnly = true)
    public List<WorkPlace> findWorkPlaces(AppUser supervisor) {
        return staffService.findWorkshopWorkPlaces(requireUnitId(supervisor));
    }

    /**
     * Форма нового рабочего места — фиксированные служба и
     * подразделение руководителя.
     *
     * Поля заполняются заранее, а не только подставляются при
     * сохранении: тогда они переживают возврат формы с ошибкой, и
     * руководителю не приходится выбирать подразделение заново.
     */
    @Transactional(readOnly = true)
    public WorkPlaceForm newWorkPlaceForm(AppUser supervisor) {
        OrganizationUnit unit = requireUnit(supervisor);

        WorkPlaceForm form = new WorkPlaceForm();

        form.setServiceId(unit.getService().getId());
        form.setOrganizationUnitId(unit.getId());

        return form;
    }

    /**
     * Создание рабочего места своего подразделения.
     */
    @Transactional
    public WorkPlace createWorkPlace(WorkPlaceForm form, AppUser supervisor) {
        OrganizationUnit unit = requireUnit(supervisor);

        form.setServiceId(unit.getService().getId());
        form.setOrganizationUnitId(unit.getId());

        return workPlaceService.create(form);
    }

    /**
     * Удаление операции — только своей.
     *
     * Проверяется принадлежность, а не только существование:
     * идентификатор операции приходит из формы, и без проверки
     * руководитель одного склада удалил бы операцию другого.
     */
    @Transactional
    public void deleteOperation(Long operationId, AppUser supervisor) {
        if (operationId == null) {
            throw new IllegalArgumentException("Операция не указана");
        }

        Operation operation = operationService.get(operationId);

        Long unitId = operation.getOrganizationUnit() == null
                ? null
                : operation.getOrganizationUnit().getId();

        if (!requireUnitId(supervisor).equals(unitId)) {
            throw new IllegalArgumentException(
                    "Операция относится к другому подразделению"
            );
        }

        operationService.delete(operationId);
    }

    /**
     * Удаление рабочего места — только своего, та же проверка границ.
     */
    @Transactional
    public void deleteWorkPlace(Long workPlaceId, AppUser supervisor) {
        if (workPlaceId == null) {
            throw new IllegalArgumentException("Рабочее место не указано");
        }

        WorkPlace place = workPlaceService.get(workPlaceId);

        Long unitId = place.getOrganizationUnit() == null
                ? null
                : place.getOrganizationUnit().getId();

        if (!requireUnitId(supervisor).equals(unitId)) {
            throw new IllegalArgumentException(
                    "Рабочее место относится к другому подразделению"
            );
        }

        workPlaceService.delete(workPlaceId);
    }

    /*
     * Подразделение руководителя с загруженной службой.
     *
     * Служба нужна форме как обязательное поле, а open-in-view отключён,
     * поэтому подразделение дочитывается отдельным запросом: у ссылки из
     * учётной записи lazy-связь со службой вне транзакции не раскрылась бы.
     */
    private OrganizationUnit requireUnit(AppUser supervisor) {
        return organizationUnitRepository.findById(requireUnitId(supervisor))
                .orElseThrow(() -> new EntityNotFoundException(
                        "Подразделение руководителя не найдено"
                ));
    }

    private Long requireUnitId(AppUser supervisor) {
        Long unitId = staffService.workshopUnitId(supervisor);

        if (unitId == null) {
            throw new IllegalStateException(
                    "Вашему подразделению не сопоставлен цех, склад или бюро: "
                            + "обратитесь к администратору"
            );
        }

        return unitId;
    }
}
