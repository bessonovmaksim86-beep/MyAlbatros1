package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.StartSerialRowView;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Раздел администратора «Стартовые номера серий» (миграция V86).
 *
 * Серийные номера в пред-БД уже были выданы, и новая база не имеет права
 * их задваивать. Ряд нумерации привязан к классификатору, а у датчиков —
 * к роду (V27), поэтому стартовый номер задают вручную и именно по
 * каждому классификатору; без задания ряд начинается с единицы.
 *
 * ПОЧЕМУ ЭФФЕКТИВНЫЙ СТАРТ СЧИТАЕТСЯ ПО РОДУ. Классификаторы одного рода
 * датчиков выдают номера из одной последовательности: если соседу по роду
 * поставить старт ниже, он получит номер, который другой классификатор
 * того же рода уже считает пройденным. Поэтому для SENSOR действует
 * наибольший из заданных по роду стартов, и раздел показывает это
 * отдельно (поле effectiveStart), чтобы администратор видел, какое
 * значение действительно определит выдачу.
 *
 * ПОЧЕМУ РЯД НЕ ОТКАТЫВАЮТ. Стартовый номер — нижняя граница выдачи, а
 * не текущее положение счётчика: занятые номера реестра остаются занятыми.
 * Поэтому значение ниже пройденного максимума не ошибочно, а просто
 * избыточно, и раздел предупреждает об этом в таблице, не отклоняя
 * сохранение.
 */
@Service
@RequiredArgsConstructor
public class StartSerialService {

    /** Верхняя граница пятизначной серии (chk_product_serial_range). */
    private static final int MAX_SERIAL = SerialNumbering.MAX_SERIAL;

    private final ProductClassifierRepository classifierRepository;
    private final ProductSerialRepository serialRepository;

    /**
     * Стартовый номер, с которого реально начнётся выдача ряда.
     *
     * Нужен всем трём местам выдачи номера (SerialIssueService,
     * ProductionOrderService, CellReceivingService), иначе каждое
     * считало бы старт по-своему и ряд получил бы задвоенный номер
     * именно там, где этого не поймает уникальный ключ: он запрещает
     * повтор, а не возвращает ленту назад.
     *
     * Для датчика берётся наибольший из заданных по роду — ряд у
     * классификаторов одного рода сквозной (V27), и старт ниже чужого
     * не сработал бы: сосед по роду считает этот номер пройденным.
     */
    public static int resolve(
            ProductClassifier classifier,
            ProductClassifierRepository repository
    ) {

        if (classifier == null) {
            return 1;
        }

        if (classifier.getProductType() == ProductType.SENSOR
                && classifier.getSensorCatalog() != null
                && classifier.getSensorCatalog().getId() != null) {

            Integer kindMax = repository.findMaxStartSerialBySensorCatalog(
                    classifier.getSensorCatalog().getId()
            );

            return kindMax == null || kindMax < 1
                    ? 1
                    : kindMax;
        }

        return classifier.getStartSerialOrOne();
    }

    /**
     * Перечень раздела: все классификаторы с заданным стартом, занятым
     * максимумом ряда и номером, который выдаст система сейчас.
     */
    @Transactional(readOnly = true)
    public List<StartSerialRowView> findRows() {

        List<ProductClassifier> classifiers =
                classifierRepository.findAllForStartSerials();

        Map<String, Integer> maxByNumberingKey = maxSerialsOf(classifiers);

        Map<Long, Integer> startBySensorCatalog =
                sensorStarts(classifiers);

        return classifiers.stream()
                .map(classifier -> StartSerialRowView.of(
                        classifier,
                        effectiveStart(classifier, startBySensorCatalog),
                        maxByNumberingKey.getOrDefault(
                                SerialNumbering.keyFor(classifier), 0
                        )
                ))
                .toList();
    }

    /**
     * Ставит стартовый номер классификатора.
     *
     * Пустое значение снимает ограничение — ряд снова начинается с
     * единицы (или с наибольшего старта по роду для датчика).
     */
    @Transactional
    public ProductClassifier save(Long classifierId, Integer startSerial) {

        ProductClassifier classifier = classifierRepository
                .findById(classifierId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Классификатор не найден"
                ));

        Integer value = normalize(startSerial);

        if (value != null && value > MAX_SERIAL) {
            throw new IllegalArgumentException(
                    "Стартовый номер «" + value + "» больше допустимого "
                            + MAX_SERIAL + ": серия пятизначная"
            );
        }

        classifierRepository.updateStartSerial(classifierId, value);

        return classifier;
    }

    /**
     * Заданный старт, который реально определит выдачу.
     *
     * Для датчика это наибольший из заданных по роду (общий ряд), для
     * остальных типов — старт самого классификатора.
     */
    private static int effectiveStart(
            ProductClassifier classifier,
            Map<Long, Integer> startBySensorCatalog
    ) {

        if (classifier.getProductType() == ProductType.SENSOR
                && classifier.getSensorCatalog() != null
                && classifier.getSensorCatalog().getId() != null) {

            return startBySensorCatalog
                    .getOrDefault(
                            classifier.getSensorCatalog().getId(), 1
                    );
        }

        return classifier.getStartSerialOrOne();
    }

    /**
     * Наибольший заданный старт по каждому роду датчиков — одним
     * обращением к справочнику.
     *
     * Отдельный запрос на род означал бы столько же обращений, сколько
     * родов в справочнике, тогда как весь перечень читается одной
     * выборкой классификаторов.
     */
    private Map<Long, Integer> sensorStarts(
            List<ProductClassifier> classifiers
    ) {

        Set<Long> sensorCatalogIds = new LinkedHashSet<>();

        for (ProductClassifier classifier : classifiers) {
            if (classifier.getProductType() == ProductType.SENSOR
                    && classifier.getSensorCatalog() != null
                    && classifier.getSensorCatalog().getId() != null) {

                sensorCatalogIds.add(
                        classifier.getSensorCatalog().getId()
                );
            }
        }

        Map<Long, Integer> result = new LinkedHashMap<>();

        for (Long sensorCatalogId : sensorCatalogIds) {
            Integer max = classifierRepository
                    .findMaxStartSerialBySensorCatalog(sensorCatalogId);

            result.put(
                    sensorCatalogId,
                    max == null || max < 1 ? 1 : max
            );
        }

        return result;
    }

    /**
     * Занятые максимумы по всем областям нумерации перечня — одним
     * запросом.
     *
     * Классификаторов в справочнике сотни, и запрос на каждый превратил
     * бы страницу в сотни обращений к базе.
     */
    private Map<String, Integer> maxSerialsOf(
            List<ProductClassifier> classifiers
    ) {

        Set<String> keys = new LinkedHashSet<>();

        for (ProductClassifier classifier : classifiers) {
            keys.add(SerialNumbering.keyFor(classifier));
        }

        Map<String, Integer> result = new LinkedHashMap<>();

        if (keys.isEmpty()) {
            return result;
        }

        for (ProductSerialRepository.NumberingMaxView view
                : serialRepository.findMaxSerialsByNumberingKeys(keys)) {

            result.put(
                    view.getNumberingKey(),
                    view.getMaxSerial() == null ? 0 : view.getMaxSerial()
            );
        }

        return result;
    }

    /**
     * Разбор введённого значения: пустое и нулевое читаются как «не
     * задан», поэтому ряд стартует с единицы.
     */
    private static Integer normalize(Integer startSerial) {
        if (startSerial == null || startSerial < 1) {
            return null;
        }

        return startSerial;
    }
}
