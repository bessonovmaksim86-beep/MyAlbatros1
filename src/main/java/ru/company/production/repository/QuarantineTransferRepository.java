package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.QuarantineTransfer;

import java.util.List;

/**
 * Репозиторий перемещений ячеек из изолятора брака на склад
 * полуфабрикатов.
 *
 * Основной путь чтения — незакрытые перемещения: по ним кладовщик
 * принимает ячейки на склад. Второй путь — перечень уже принятых
 * номеров: по ним изолятор брака убирает ячейку из своего перечня,
 * потому что лежать в изоляторе и на складе одновременно она не
 * может.
 */
@Repository
public interface QuarantineTransferRepository
        extends JpaRepository<QuarantineTransfer, Long> {

    /**
     * Ячейки, которые БВК передал, но склад ещё не принял.
     *
     * Принятые из перечня уходят: приёмку проводят один раз, и
     * оставленная строка подталкивала бы кладовщика провести её
     * повторно. Порядок — по времени перемещения, чтобы склад разбирал
     * очередь сверху вниз.
     */
    @Query("""
            select transfer
            from QuarantineTransfer transfer
            left join fetch transfer.transferredBy transferredBy
            left join fetch transfer.productClassifier classifier
            left join fetch transfer.receivingBatch batch
            where transfer.accepted = false
            order by transfer.transferredAt asc, transfer.id asc
            """)
    List<QuarantineTransfer> findPending();

    /**
     * Номера, принятые складом: их изолятор брака больше не показывает.
     *
     * Читают целиком, а не по отобранной странице: перечень изолятора
     * строят пакетом, и запрос на каждую строку раздул бы его до
     * сотен обращений к базе.
     */
    @Query("""
            select transfer.fullNumber
            from QuarantineTransfer transfer
            where transfer.accepted = true
            """)
    List<String> findAcceptedFullNumbers();

    /**
     * Перемещённых ячеек, ожидающих приёмки: счётчик в боковом меню склада.
     */
    long countByAcceptedFalse();

    /**
     * Все незакрытые номера: проверка перед перемещением.
     *
     * Нужен контроллеру изолятора, чтобы отклонить повторное перемещение
     * одной и той же ячейки.
     */
    @Query("""
            select transfer.fullNumber
            from QuarantineTransfer transfer
            where transfer.accepted = false
            """)
    List<String> findPendingFullNumbers();
}
