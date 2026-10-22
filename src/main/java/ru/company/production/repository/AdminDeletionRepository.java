package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.AdminDeletion;
import ru.company.production.entity.DeletionTargetKind;

import java.util.List;

/**
 * Лента журнала административных удалений.
 *
 * Раздел читает журнал целиком, а не постранично: удалений в системе
 * единицы (раздел нужен для разбора ошибок, а не для ежедневной
 * работы), и администратору важнее увидеть всю ленту, чем искать
 * нужную строку по страницам.
 */
@Repository
public interface AdminDeletionRepository extends JpaRepository<AdminDeletion, Long> {

    /** Свежие удаления сверху — общий порядок ленты на странице раздела. */
    List<AdminDeletion> findAllByOrderByPerformedAtDescIdDesc();

    /**
     * Последние удаления конкретной записи.
     *
     * Нужно не самому разделу, а проверке «запись уже удаляли»: если
     * заказ исчез из списка, а в журнале его нет — значит дело не в
     * работе раздела, и искать причину стоит в другом месте.
     */
    @Query("""
            select deletion
            from AdminDeletion deletion
            where deletion.targetKind = :kind
              and deletion.targetId = :targetId
            order by deletion.performedAt desc, deletion.id desc
            """)
    List<AdminDeletion> findRecentOfTarget(
            @Param("kind") DeletionTargetKind kind,
            @Param("targetId") Long targetId
    );
}
