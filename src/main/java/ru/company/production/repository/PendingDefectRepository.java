package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.PendingDefect;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий заявок на отбраковку с согласованием БВК.
 *
 * Основной путь чтения — ожидающие решений: по ним представитель БВК
 * принимает решение о перемещении в изолятор или возврате на склад.
 * Второй путь — решённые заявки для реестра и аудита.
 */
@Repository
public interface PendingDefectRepository
        extends JpaRepository<PendingDefect, Long> {

    /**
     * Заявки, ожидающие решения.
     *
     * Порядок — по времени создания: свежие сверху, чтобы представитель
     * БВК разбирал очередь.
     */
    @Query("""
            select defect
            from PendingDefect defect
            left join fetch defect.requestedBy requestedBy
            left join fetch defect.productClassifier classifier
            left join fetch defect.receivingBatch batch
            where defect.decision is null
            order by defect.requestedAt asc, defect.id asc
            """)
    List<PendingDefect> findPending();

    /**
     * Решённые заявки — для реестра.
     *
     * Порядок — по времени решения: свежее сверху.
     */
    @Query("""
            select defect
            from PendingDefect defect
            left join fetch defect.requestedBy requestedBy
            left join fetch defect.approvedBy approvedBy
            left join fetch defect.productClassifier classifier
            left join fetch defect.receivingBatch batch
            where defect.decision is not null
            order by defect.approvedAt desc, defect.id desc
            """)
    List<PendingDefect> findResolved();

    /**
     * Заявки, ожидающие решения по конкретному номеру.
     *
     * Нужно, чтобы не создать вторую заявку на уже ожидающую ячейку.
     */
    @Query("""
            select defect
            from PendingDefect defect
            where defect.fullNumber = :fullNumber
              and defect.decision is null
            """)
    Optional<PendingDefect> findPendingByFullNumber(
            @Param("fullNumber") String fullNumber
    );

    /**
     * Количество ожидающих решений — счётчик в боковом меню БВК.
     */
    long countByDecisionIsNull();

    /**
     * Все номера, по которым есть решённые заявки.
     *
     * Используется для фильтрации в реестре отбраковки.
     */
    @Query("""
            select defect.fullNumber
            from PendingDefect defect
            where defect.decision is not null
            """)
    List<String> findResolvedFullNumbers();

    /*
     * ============================================================
     * Раздел «Очистка данных» (администратор)
     * ============================================================
     */

    /**
     * Заявки, оформленные на удаляемый классификатор.
     */
    @Query("""
            select defect
            from PendingDefect defect
            where defect.productClassifier.id = :classifierId
            order by defect.requestedAt asc, defect.id asc
            """)
    List<PendingDefect> findByClassifierId(
            @Param("classifierId") Long classifierId
    );

    long countByProductClassifier_Id(Long classifierId);

    /** Снимает ссылку на классификатор, строку сохраняя. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PendingDefect defect
            set defect.productClassifier = null
            where defect.productClassifier.id = :classifierId
            """)
    int detachClassifier(@Param("classifierId") Long classifierId);

    /** Удаляет заявки на удаляемый классификатор. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PendingDefect defect
            where defect.productClassifier.id = :classifierId
            """)
    int deleteByClassifierId(@Param("classifierId") Long classifierId);

    /**
     * Заявки, оформленные на удаляемую партию.
     */
    @Query("""
            select defect
            from PendingDefect defect
            where defect.receivingBatch.id = :batchId
            order by defect.requestedAt asc, defect.id asc
            """)
    List<PendingDefect> findByBatchId(
            @Param("batchId") Long batchId
    );

    long countByReceivingBatch_Id(Long batchId);

    /** Снимает ссылку на партию, строку сохраняя. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PendingDefect defect
            set defect.receivingBatch = null
            where defect.receivingBatch.id = :batchId
            """)
    int detachBatch(@Param("batchId") Long batchId);

    /** Удаляет заявки на удаляемую партию. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PendingDefect defect
            where defect.receivingBatch.id = :batchId
            """)
    int deleteByBatchId(@Param("batchId") Long batchId);
}
