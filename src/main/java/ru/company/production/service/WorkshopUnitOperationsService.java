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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     *
     * Граница — {@link WorkshopStaffService#unitScope(AppUser)}: у
     * руководителя службы качества операции сразу двух подразделений
     * (БТК и БВК), и выборка одного показала бы половину перечня.
     */
    @Transactional(readOnly = true)
    public List<Operation> findOperations(AppUser supervisor) {
        return staffService.findAllUnitOperations(requireUnitScope(supervisor));
    }

    /**
     * Действующие рабочие места подразделения.
     */
    @Transactional(readOnly = true)
    public List<WorkPlace> findWorkPlaces(AppUser supervisor) {
        return staffService.findWorkshopWorkPlaces(requireUnitScope(supervisor));
    }

    /**
     * Можно ли пользователю заводить и удалять позиции раздела.
     *
     * Контроллеру нужен отдельный признак, а не исключение: форма new
     * рабочего места строится при каждой загрузке страницы, и для
     * роли «только чтение» её не должно быть вовсе — иначе запрос
     * страницы падал бы вместо списка без кнопок.
     *
     * Для SK_GENERAL (руководитель службы качества) возвращает true —
     * он может управлять операциями и рабочими местами обоих
     * подразделений (БТК и БВК), так как эти подразделения входят
     * в его зону ответственности.
     */
    public boolean canManage(AppUser supervisor) {
        return staffService.canViewUnit(supervisor);
    }

    /**
     * Форма нового рабочего места — фиксированные служба и
     * подразделение руководителя.
     *
     * Поля заполняются заранее, а не только подставляются при
     * сохранении: тогда они переживают возврат формы с ошибкой, и
     * руководителю не приходится выбирать подразделение заново.
     *
     * Значение по умолчанию — первое подразделение границы. Для
     * мастера цеха оно единственное, для руководителя службы — БТК
     * либо БВК, и второе он выбирает в форме (см.
     * {@link #formUnits(AppUser)}).
     */
    @Transactional(readOnly = true)
    public WorkPlaceForm newWorkPlaceForm(AppUser supervisor) {
        List<OrganizationUnit> units = formUnits(supervisor);

        if (units.isEmpty()) {
            throw new IllegalStateException(
                    "Вашему подразделению не сопоставлен цех, склад или бюро: "
                            + "обратитесь к администратору"
            );
        }

        OrganizationUnit unit = units.get(0);

        WorkPlaceForm form = new WorkPlaceForm();

        form.setServiceId(unit.getService().getId());
        form.setOrganizationUnitId(unit.getId());

        return form;
    }

    /**
     * Подразделения, доступные форме рабочего места.
     *
     * Мастеру возвращается его единственное подразделение — форма не
     * предлагает выбор там, где выбирать не из чего. Руководителю
     * службы — все действующие подразделения службы по алфавиту:
     * рабочее место БТК и рабочее место БВК нужны оба, и зашивать
     * одно из них значило бы лишить службу половины перечня.
     */
    @Transactional(readOnly = true)
    public List<OrganizationUnit> formUnits(AppUser supervisor) {
        List<Long> scope = requireUnitScope(supervisor);

        Map<Long, OrganizationUnit> byId = new HashMap<>();

        for (OrganizationUnit unit
                : organizationUnitRepository.findAllById(scope)) {
            byId.put(unit.getId(), unit);
        }

        /*
         * Порядок берётся из scope: unitScope уже отдаёт подразделения
         * службы по наименованию, а findAllById возвращает их в порядке
         * ключа, где первым шёл бы идентификатор записи.
         */
        List<OrganizationUnit> units = new ArrayList<>();

        for (Long unitId : scope) {
            OrganizationUnit unit = byId.get(unitId);

            if (unit != null && unit.isActive()) {
                units.add(unit);
            }
        }

        return units;
    }

    /**
     * Создание рабочего места своего подразделения.
     *
     * Мастеру цеха подразделение подставляется из учётной записи.
     * Руководителю службы (SK_GENERAL) — берётся из формы, но
     * проверяется по границе службы: нельзя создать рабочее место
     * в чужом подразделении.
     */
    @Transactional
    public WorkPlace createWorkPlace(WorkPlaceForm form, AppUser supervisor) {
        OrganizationUnit unit;

        if (staffService.hasFullModule(supervisor)) {
            unit = requireUnit(requireOwnUnit(supervisor));
        } else {
            unit = requireUnit(requireChosenUnit(form, supervisor));
        }

        form.setServiceId(unit.getService().getId());
        form.setOrganizationUnitId(unit.getId());

        return workPlaceService.create(form);
    }

    /**
     * Подразделение, выбранное в форме, проверяется по границе
     * службы руководителя.
     */
    private Long requireChosenUnit(WorkPlaceForm form, AppUser supervisor) {
        Long chosenUnitId = form.getOrganizationUnitId();

        if (chosenUnitId == null) {
            throw new IllegalArgumentException(
                    "Выберите подразделение"
            );
        }

        List<Long> scope = requireUnitScope(supervisor);

        if (!scope.contains(chosenUnitId)) {
            throw new IllegalArgumentException(
                    "Выбранное подразделение не входит в зону "
                            + "ответственности вашей службы"
            );
        }

        return chosenUnitId;
    }

    /**
     * Подразделение из учётной записи — для мастеров цехов, складов
     * и бюро. У SK_GENERAL workshopUnitId() возвращает null.
     */
    private Long requireOwnUnit(AppUser supervisor) {
        Long unitId = staffService.workshopUnitId(supervisor);

        if (unitId == null) {
            throw new IllegalStateException(
                    "Вашему подразделению не сопоставлен цех, склад или бюро: "
                            + "обратитесь к администратору"
            );
        }

        return unitId;
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

        requireWritableScope(supervisor);

        Operation operation = operationService.get(operationId);

        Long unitId = operation.getOrganizationUnit() == null
                ? null
                : operation.getOrganizationUnit().getId();

        if (!requireUnitScope(supervisor).contains(unitId)) {
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

        requireWritableScope(supervisor);

        WorkPlace place = workPlaceService.get(workPlaceId);

        Long unitId = place.getOrganizationUnit() == null
                ? null
                : place.getOrganizationUnit().getId();

        if (!requireUnitScope(supervisor).contains(unitId)) {
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
    private OrganizationUnit requireUnit(Long unitId) {
        return organizationUnitRepository.findById(unitId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Подразделение руководителя не найдено"
                ));
    }

    /*
     * Граница раздела: список подразделений руководителя.
     *
     * Пустой список означает, что карточка учётной записи не заполнена,
     * и показывать в этом случае пустой раздел вместо внятной ошибки
     * значило бы оставить руководителя без объяснения причины.
     */
    private List<Long> requireUnitScope(AppUser supervisor) {
        List<Long> scope = staffService.unitScope(supervisor);

        if (scope.isEmpty()) {
            throw new IllegalStateException(
                    "Вашему подразделению не сопоставлен цех, склад или бюро: "
                            + "обратитесь к администратору"
            );
        }

        return scope;
    }

    /*
     * Право на запись в раздел: пользователю недостаточно видеть
     * подразделения, он должен уметь их менять.
     *
     * Проверяется так же, как {@link #canManage(AppUser)}, — через
     * {@code canViewUnit}, а не через {@code hasFullModule}. Раздел
     * «Операции и рабочие места» нужен и мастерам цехов, и руководителю
     * службы качества: справочник операций у них общий, и план,
     * выпущенный технологом, видит те же операции. Раньше запись была
     * закрыта перед службой, и её контролёры работали операциями чужих
     * цехов.
     *
     * Граница содержимого остаётся прежней — {@link #requireUnitScope}:
     * служба не получит доступ к подразделению вне своей службы, даже
     * изменив идентификатор в форме.
     */
    private void requireWritableScope(AppUser supervisor) {
        if (!canManage(supervisor)) {
            throw new IllegalStateException(
                    "У вас нет прав на изменение операций и рабочих мест"
            );
        }

        requireUnitScope(supervisor);
    }
}
