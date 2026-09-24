package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.TechProcessClassifier;

import java.util.List;
import java.util.Optional;

/**
 * Связки «техпроцесс — изделие».
 *
 * Отдельный репозиторий нужен из-за признака базовости: базовый
 * техпроцесс один на изделие, поэтому при назначении базового
 * прежние отметки снимают одним запросом по всем маршрутам изделия.
 */
@Repository
public interface TechProcessClassifierRepository
        extends JpaRepository<TechProcessClassifier, Long> {

    List<TechProcessClassifier> findByClassifier_Id(Long classifierId);

    List<TechProcessClassifier> findByTechProcess_Id(Long techProcessId);

    Optional<TechProcessClassifier> findByTechProcess_IdAndClassifier_Id(
            Long techProcessId,
            Long classifierId
    );

    /**
     * Снимает базовость со всех маршрутов изделия.
     *
     * Массовый update (а не правка загруженных сущностей) держит
     * единственность базового одним обращением к базе: два клика
     * «сделать базовым» от разных технологов не оставят изделию два
     * основных маршрута.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TechProcessClassifier link
            set link.basic = false
            where link.classifier.id = :classifierId
              and link.basic = true
            """)
    int clearBasicForClassifier(@Param("classifierId") Long classifierId);

    /** Базовый маршрут изделия, если он назначен. */
    @Query("""
            select link
            from TechProcessClassifier link
            where link.classifier.id = :classifierId
              and link.basic = true
              and link.techProcess.active = true
            """)
    Optional<TechProcessClassifier> findBasicForClassifier(
            @Param("classifierId") Long classifierId
    );

    /**
     * Изделия, к которым применён техпроцесс, с пометкой базовости.
     *
     * Базовое изделие ставят на первое место: на странице применения
     * маршрута технологу нужно сразу видеть, для какого из изделий
     * этот маршрут основной.
     */
    @Query("""
            select
                classifier.id          as classifierId,
                classifier.code        as code,
                classifier.productType as productType,
                classifier.name        as name,
                sensor.name            as sensorClassName,
                link.basic             as basic
            from TechProcessClassifier link
            join link.classifier classifier
            left join classifier.sensorCatalog sensor
            where link.techProcess.id = :processId
            order by link.basic desc, classifier.code
            """)
    List<AppliedClassifierView> findAppliedClassifiers(
            @Param("processId") Long processId
    );
}
