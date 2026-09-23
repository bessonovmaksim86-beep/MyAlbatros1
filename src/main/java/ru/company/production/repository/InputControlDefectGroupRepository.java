package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.InputControlDefectGroup;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.QuarantineCellKind;

import java.util.List;

@Repository
public interface InputControlDefectGroupRepository
        extends JpaRepository<InputControlDefectGroup, Long> {

    /**
     * Группы брака карточки по порядку.
     *
     * Порядок задаёт контролёр: дефекты перечисляют сверху вниз, и в
     * закрытой карточке перечень обязан читаться в том же виде.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            where grp.card.id = :cardId
            order by grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findByCardIdOrdered(
            @Param("cardId") Long cardId
    );

    /** Пакетная очистка групп перед перезаписью карточки. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from InputControlDefectGroup grp
            where grp.card.id = :cardId
            """)
    int deleteByCardId(@Param("cardId") Long cardId);

    /**
     * Группы брака сразу многих карточек.
     *
     * Нужен складу: чтобы понять, какие ячейки партии на склад не
     * вернутся, состав читают и по отдельным номерам, и по отметке
     * «вся партия» — без неё группу восстановили бы только по одной
     * карточке.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            where grp.card.id in :cardIds
            order by grp.card.id asc, grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findByCardIds(
            @Param("cardIds") java.util.Collection<Long> cardIds
    );

    /**
     * Группы брака, закрытые возвратом поставщику.
     *
     * Реестр возвратов собирают по группам, а не по карточкам: партию
     * бракуют частями, и возвратом уходит только та часть, по которой
     * принято решение {@code RETURN_TO_SUPPLIER}. Карточка, партия и
     * контролёр подтягиваются {@code fetch} — {@code open-in-view}
     * выключен, а реестру нужны реквизиты рекламации (поставщик,
     * ордер, диапазон) и подпись контролёра. Берут только закрытые
     * карточки: открытая проверка ещё не вынесла решения.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            join fetch grp.card card
            join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch card.checkedBy checker
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.RETURN_TO_SUPPLIER
              and card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            order by card.completedAt desc, card.id desc,
                     grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findReturnGroupsDetailed();

    /**
     * Число партий, отправленных на возврат: счётчик в боковом меню.
     *
     * Считают карточки, а не группы: на партию возвратных групп может
     * быть несколько, а в меню счётчик отвечает на вопрос «сколько
     * партий ждать к возврату».
     */
    @Query("""
            select count(distinct grp.card.id)
            from InputControlDefectGroup grp
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.RETURN_TO_SUPPLIER
              and grp.card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            """)
    long countReturnCards();

    /**
     * Группы брака, оставленные в изоляторе брака.
     *
     * Реестр устроен так же, как возврат: собирают по группам, а не
     * по карточкам, потому что партию бракуют частями и в изолятор
     * уходит только та часть, по которой принято решение
     * {@code QUARANTINE}. Отличие в назначении перечня: возврат —
     * основание рекламации поставщику, изолятор — перечень ячеек,
     * которые остались на предприятии и в производство не пойдут.
     * {@code fetch} повторяет возвратный запрос: {@code open-in-view}
     * выключен, а строке реестра нужны реквизиты партии и подпись
     * контролёра. Открытые карточки не берут — решения ещё нет.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            join fetch grp.card card
            join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch card.checkedBy checker
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.QUARANTINE
              and card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            order by card.completedAt desc, card.id desc,
                     grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findQuarantineGroupsDetailed();

    /**
     * Число партий с ячейками в изоляторе брака: счётчик в боковом
     * меню.
     *
     * Считают карточки, а не группы: изоляторных групп у партии может
     * быть несколько (по числу дефектов), а счётчик отвечает на вопрос
     * «по скольким партиям в изоляторе что-то лежит».
     */
    @Query("""
            select count(distinct grp.card.id)
            from InputControlDefectGroup grp
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.QUARANTINE
              and grp.card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            """)
    long countQuarantineCards();

    /**
     * Классификатор изделий, ячейки которых лежат в изоляторе.
     *
     * Опции фильтра берут из самого изолятора, а не из справочника
     * классификаторов целиком: в перечне руководителю службы качества
     * нужны те изделия, по которым брак действительно есть, иначе
     * выпадающий список на сотни позиций читался бы как поиск вслепую.
     */
    @Query("""
            select distinct
                   classifier.id           as id,
                   classifier.code         as code,
                   classifier.productType  as productType,
                   classifier.name         as name,
                   sensor.name             as sensorName
            from InputControlDefectGroup grp
            join grp.card card
            join card.batch batch
            join batch.cellClassifier classifier
            left join classifier.sensorCatalog sensor
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.QUARANTINE
              and card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            order by classifier.code asc
            """)
    List<QuarantineClassifierOption> findQuarantineClassifierOptions();

    /**
     * Группы изолятора брака для перечня руководителя службы качества.
     *
     * Отличие от {@link #findQuarantineGroupsDetailed()} — отбор:
     * разновидность изделия, классификатор и причина брака. Разновидность
     * выводят из приёмки складом (см. {@link QuarantineCellKind}), а не
     * из типа классификатора, поэтому условие построено по
     * {@code batch.acceptedAt}.
     *
     * Причина задаётся поддеревом: выбранный тип верхнего уровня
     * захватывает и его уточнения, иначе фильтр по типу «Механическое
     * повреждение» обошёл бы группы, оформленные по уточнению «Скол
     * корпуса». Пустое поддерево в запрос не попадает — сервис подставляет
     * служебный идентификатор, и условие по причине просто не срабатывает.
     *
     * Порядок тот же, что у реестра БВК: свежие проверки сверху.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            join fetch grp.card card
            join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch card.checkedBy checker
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.QUARANTINE
              and card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
              and (:kind is null
                   or (:kind =
                           ru.company.production.entity.QuarantineCellKind.CELL
                       and batch.acceptedAt is not null)
                   or (:kind =
                           ru.company.production.entity.QuarantineCellKind.SUBASSEMBLY
                       and batch.acceptedAt is null))
              and (:classifierId is null
                   or batch.cellClassifier.id = :classifierId)
              and (
                    :reasonId is null
                    or exists (
                        select 1
                        from InputControlCardReason chosen
                        where chosen.group.id = grp.id
                          and (
                                chosen.reason.id = :reasonId
                                or chosen.reason.parent.id = :reasonId
                              )
                    )
                  )
            order by card.completedAt desc, card.id desc,
                     grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findQuarantineGroupsFiltered(
            @Param("kind") QuarantineCellKind kind,
            @Param("classifierId") Long classifierId,
            @Param("reasonId") Long reasonId
    );

    /**
     * Опция фильтра «класс/имя ячейки»: классификатор изделия,
     * присутствующего в изоляторе.
     */
    interface QuarantineClassifierOption {

        Long getId();

        Integer getCode();

        ProductType getProductType();

        String getName();

        String getSensorName();
    }
}
