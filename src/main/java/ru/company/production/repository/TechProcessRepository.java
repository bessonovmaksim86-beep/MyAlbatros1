package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.TechProcess;

import java.util.List;
import java.util.Optional;

@Repository
public interface TechProcessRepository
        extends JpaRepository<TechProcess, Long> {

    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.productClassifier classifier
            left join fetch classifier.sensorCatalog
            order by process.code
            """)
    List<TechProcess> findAllDetailed();

    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where process.id = :id
            """)
    Optional<TechProcess> findHeaderById(@Param("id") Long id);

    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch process.operations operation
            left join fetch operation.operation baseOperation
            left join fetch baseOperation.operationName
            left join fetch baseOperation.operationType
            left join fetch baseOperation.service
            left join fetch baseOperation.organizationUnit
            left join fetch baseOperation.executorRole
            where process.id = :id
            order by operation.position
            """)
    Optional<TechProcess> findFullById(@Param("id") Long id);

    boolean existsByCode(String code);

    /*
     * Список для страницы техпроцессов: одна строка на пару
     * «техпроцесс — изделие». Маршрут, применённый к нескольким
     * изделиям, показан отдельно для каждого из них.
     *
     * Строки группируют по изделию, а внутри изделия базовый
     * маршрут идёт первым: технологу важнее состав маршрутов одного
     * изделия, чем общий алфавит кодов, и базовый должен бросаться
     * в глаза первым.
     */
    @Query("""
            select
                process.id as id,
                process.code as code,
                classifier.id as classifierId,
                classifier.code as classifierCode,
                classifier.productType as productType,
                classifier.name as classifierName,
                sensor.name as sensorClassName,
                link.basic as basic,
                (
                    select count(node)
                    from TechProcessOperation node
                    where node.techProcess = process
                ) as operationCount
            from TechProcess process
            join process.classifierLinks link
            join link.classifier classifier
            left join classifier.sensorCatalog sensor
            where process.active = true
            order by classifier.code, link.basic desc, process.code
            """)
    List<TechProcessListItemView> findAllWithAppliedClassifiers();

    /*
     * Все техпроцессы одного изделия для страницы выбора маршрута:
     * базовый всегда первым, остальные — по коду.
     */
    @Query("""
            select
                process.id as id,
                process.code as code,
                link.basic as basic,
                (
                    select count(node)
                    from TechProcessOperation node
                    where node.techProcess = process
                ) as operationCount
            from TechProcess process
            join process.classifierLinks link
            where process.active = true
              and link.classifier.id = :classifierId
            order by link.basic desc, process.code
            """)
    List<ClassifierRouteOptionView> findRoutesForClassifier(
            @Param("classifierId") Long classifierId
    );

    /* Все коды: префикс отбирается на стороне. */
    @Query("select process.code from TechProcess process")
    List<String> findAllCodes();

    /*
     * Техпроцесс со списком изделий, к которым он применён.
     * Используется при применении маршрута к новому изделию.
     */
    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.classifierLinks link
            left join fetch link.classifier
            where process.id = :id
            """)
    Optional<TechProcess> findWithAppliedClassifiers(
            @Param("id") Long id
    );

    /*
     * Сколько изделий использует каждый техпроцесс.
     * Отдельный запрос, чтобы не раздувать основной fetch-запрос.
     */
    @Query("""
            select process.id, count(link.classifier.id)
            from TechProcess process
            left join process.classifierLinks link
            group by process.id
            """)
    List<Object[]> countAppliedClassifiers();

    boolean existsByCodeAndIdNot(String code, Long id);

    long countByActiveTrue();

    /*
     * Есть ли у изделия активный техпроцесс — основной или
     * применённый к нему. Используется при создании заказа:
     * выпуск позиции по базовому техпроцессу возможен только
     * когда такой техпроцесс у изделия задан.
     *
     * Достаточно связки: изделие основного маршрута всегда входит
     * в classifierLinks с basic = true (см. V39), поэтому отдельно
     * проверять product_classifier_id не нужно.
     */
    @Query("""
            select count(distinct process.id)
            from TechProcess process
            join process.classifierLinks link
            where process.active = true
              and link.classifier.id = :classifierId
            """)
    long countActiveForClassifier(@Param("classifierId") Long classifierId);

    /**
     * Маршруты для заказа на ремонт силами СДП.
     *
     * Отдают активные маршруты с кодом и изделием: выбор в форме
     * строится по подписи «ТП-14 — 1212 ЯПР56», поэтому изделие берут
     * основным (productClassifier), а не списком применений — иначе
     * маршрут, применённый к трём изделиям, занял бы в списке три
     * строки и исполнитель выбирал бы наугад.
     */
    @Query("""
            select
                process.id           as id,
                process.code         as code,
                classifier.code      as classifierCode,
                classifier.name      as classifierName
            from TechProcess process
            join process.productClassifier classifier
            where process.active = true
            order by process.code
            """)
    List<RepairRouteOptionView> findRepairRouteOptions();

    /*
     * Рабочие места, на которых маршрут допускает операцию.
     *
     * Нужны распределению работ: список способных сотрудников
     * строится по паре «прямая привязка к операции + привязка к
     * одному из этих мест» — ровно так же, как его считает
     * планировщик (OrderSchedulingService#placeOperation). Иначе
     * мастер предлагал бы людей, которых расчёт в мощность этапа не
     * закладывал.
     *
     * Маршрутов у одной операции группы может быть несколько
     * (разные позиции заказа), поэтому техпроцессы передаются
     * списком, а выборка сразу даёт объединение.
     */
    @Query("""
            select distinct place.id
            from TechProcessOperation node
            join node.workPlaces place
            where node.operation.id = :operationId
              and node.techProcess.id in :processIds
            """)
    List<Long> findRouteWorkPlaceIds(
            @Param("operationId") Long operationId,
            @Param("processIds") java.util.Collection<Long> processIds
    );
}
