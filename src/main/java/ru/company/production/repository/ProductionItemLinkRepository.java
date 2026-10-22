package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductionItemLink;

import java.util.Collection;
import java.util.List;

/**
 * Связи конкретных экземпляров: ячейка ⇄ прибор, система ⇄ состав.
 *
 * Репозиторий нужен разделу «Очистка данных»: ключи таблицы висят на
 * {@code product_serials} с ON DELETE CASCADE (V17), и удаление номера
 * из реестра молча забрало бы с собой чужую сборку. Поэтому перед
 * удалением раздел обязан спросить, стоит ли номер в такой связи.
 */
@Repository
public interface ProductionItemLinkRepository
        extends JpaRepository<ProductionItemLink, Long> {

    /**
     * Связи, где удаляемый номер — родитель или ребёнок.
     *
     * Проверяются обе стороны: ячейка фигурирует в таблице и как
     * родитель (собрана с прибором), и как ребёнок (вошла в систему),
     * и пропуск любой из ветвей означал бы, что номер удалили посреди
     * собранного изделия.
     */
    @Query("""
            select link
            from ProductionItemLink link
            where link.parentSerial.id in :serialIds
               or link.childSerial.id in :serialIds
            """)
    List<ProductionItemLink> findBySerialIdIn(
            @Param("serialIds") Collection<Long> serialIds
    );

    @Query("""
            select count(link)
            from ProductionItemLink link
            where link.parentSerial.id in :serialIds
               or link.childSerial.id in :serialIds
            """)
    long countBySerialIdIn(
            @Param("serialIds") Collection<Long> serialIds
    );
}
