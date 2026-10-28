package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.company.production.entity.ClassifierInclusion;

import java.util.List;

public interface ClassifierInclusionRepository
        extends JpaRepository<ClassifierInclusion, Long> {

    boolean existsByTargetId(Long targetId);

    /**
     * Состав изделия: чем именно и в каком количестве один владелец
     * допускает другое изделие.
     *
     * Нужен складу как потолок комплектования: заказ-комплектующий
     * входит в изделие-носителя, и «сколько таких ячеек допускается»
     * задано здесь, а не числом строк плана (см.
     * {@code WarehouseKittingService#attachCellToZnp}).
     *
     * Список, а не одна строка: пара «владелец + изделие» формально не
     * уникальна, и молча взять первую из двух значило бы применить
     * произвольный потолок.
     */
    @Query("""
            select inclusion
            from ClassifierInclusion inclusion
            where inclusion.owner.id = :ownerId
              and inclusion.target.id = :targetId
            """)
    List<ClassifierInclusion> findByOwnerAndTarget(
            @Param("ownerId") Long ownerId,
            @Param("targetId") Long targetId
    );
}