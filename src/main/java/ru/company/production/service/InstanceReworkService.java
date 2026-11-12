package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.ReworkReturnRepository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Состояние доработки экземпляра — единый признак блокировки.
 *
 * Возврат с контроля запускает цикл, у которого четыре ступени: БТК
 *Signal вернула изделие, начальник цеха отметил операции переделки,
 * мастер их распределил, исполнитель провёл. До последней ступени
 * позиция не имеет ни своего результата, ни закрытого брака, поэтому
 * проводить её, отправлять в изолятор или возвращать на доработку
 * второй раз нельзя — любое из этих действий переписало бы маршрут
 * изделия, по которому решение ещё не принято.
 *
 * Признак держится в одном сервисе намеренно. Список работ считает
 * право проведения, панель контроля решает по нему, какие чекбоксы
 * рисовать, а проверка возврата обязана отвергнуть то же самое на
 * сервере. Держи его в трёх местах — и первое же изменение порядка
 * показало бы кнопку там, где сервер отвечает отказом, или наоборот:
 * работу нельзя было бы провести, хотя препятствие уже снято.
 *
 * Основание блокировки — незакрытый возврат ЛЮБОЙ строки экземпляра,
 * а не только самой строки: БТК возвращает изделие целиком, и
 * переделка монтажа касается контроля того же изделия. Ключ
 * экземпляра тот же, что у {@code ControlReturnService} и
 * {@code ReworkReturnService}: «основание плана + позиция + номер
 * внутри позиции».
 *
 * Исключение — строки, которые цех сам отметил на переделку: их
 * повторное проведение и есть закрытие доработки. Заблокировать их
 * означало бы запереть цикл намертво.
 */
@Service
@RequiredArgsConstructor
public class InstanceReworkService {

    private final ControlReturnRepository controlReturnRepository;
    private final ReworkReturnRepository reworkReturnRepository;

    /**
     * Строки, чья переделка не завершена: проводить и перемещать
     * нельзя.
     *
     * Считается одним пакетом на весь список, а не построчно: панель
     * экземпляров раскрывается на десятки строк графика, и три
     * запроса на каждую превратили бы один экран в сотни обращений
     * к базе.
     */
    @Transactional(readOnly = true)
    public Set<Long> incompleteRowIds(Collection<Long> rowIds) {

        if (rowIds == null || rowIds.isEmpty()) {
            return Set.of();
        }

        List<Long> ids = List.copyOf(new LinkedHashSet<>(rowIds));

        Set<Long> blocked = new LinkedHashSet<>(
                controlReturnRepository.findRowIdsOfSignaledInstances(ids));

        blocked.addAll(
                reworkReturnRepository.findRowIdsOfReworkInstances(ids));

        /*
         * Отмеченные цехом операции из блокировки выпадают: без этого
         * переделку нельзя было бы закрыть, а возврат повис бы над
         * сделанной работой.
         */
        blocked.removeAll(
                reworkReturnRepository.findRowIdsWithOpenReturn(ids));

        return blocked;
    }
}
