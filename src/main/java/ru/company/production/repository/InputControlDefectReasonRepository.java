package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.InputControlDefectReason;

import java.util.List;
import java.util.Optional;

@Repository
public interface InputControlDefectReasonRepository
        extends JpaRepository<InputControlDefectReason, Long> {

    /**
     * Действующие причины вместе со ступенью выше.
     *
     * Дерево в справочнике открытое по глубине, поэтому целиком оно
     * читается одним запросом, а не рекурсией по parent: список
     * небольшой, а обращение к ленивой связи вне транзакции
     * (open-in-view выключен) прервало бы рендеринг страницы.
     */
    @Query("""
            select reason
            from InputControlDefectReason reason
            left join fetch reason.parent parent
            where reason.active = true
            order by parent.id asc, reason.name asc
            """)
    List<InputControlDefectReason> findAllActiveWithParent();

    /** Причины первого уровня. */
    @Query("""
            select reason
            from InputControlDefectReason reason
            where reason.active = true
              and reason.parent is null
            order by reason.name asc
            """)
    List<InputControlDefectReason> findRoots();

    /** Уточнения выбранной причины — следующая ступень дерева. */
    @Query("""
            select reason
            from InputControlDefectReason reason
            where reason.active = true
              and reason.parent.id = :parentId
            order by reason.name asc
            """)
    List<InputControlDefectReason> findChildren(
            @Param("parentId") Long parentId
    );

    Optional<InputControlDefectReason> findByIdAndActiveTrue(Long id);

    /**
     * Запись той же ступени с тем же написанием.
     *
     * Уникальность в базе держится на collation utf8mb4_unicode_ci,
     * но проверка нужна до вставки: сообщение об ошибке должно быть
     * понятным, а не нарушением ограничения.
     */
    @Query("""
            select reason
            from InputControlDefectReason reason
            where lower(trim(reason.name)) = lower(trim(:name))
              and (
                    (:parentId is null and reason.parent is null)
                    or reason.parent.id = :parentId
                  )
            """)
    Optional<InputControlDefectReason> findByLevelAndName(
            @Param("parentId") Long parentId,
            @Param("name") String name
    );
}
