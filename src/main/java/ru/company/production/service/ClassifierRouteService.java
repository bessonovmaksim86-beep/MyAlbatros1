package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.repository.ActiveRouteOptionView;
import ru.company.production.repository.ClassifierRouteLinkView;
import ru.company.production.repository.TechProcessClassifierRepository;
import ru.company.production.repository.TechProcessRepository;

import java.util.List;

/**
 * Работа со связками «изделие — техпроцесс» на страницах
 * классификатора.
 *
 * Отдельный сервис нужен по двум причинам.
 *
 * Чтение: колонка «Техпроцессы» на сотни изделий и список маршрутов
 * для выбора читаются одним запросом каждая, чтобы страница не
 * уходила в N+1.
 *
 * Команда: добавить маршрутов к изделию и перенести базовость — два
 * разных действия модуля техпроцессов, а на странице классификатора
 * они выполняются одним нажатием (галочка «сделать базовым»). Здесь
 * они объединены в одну транзакцию, поэтому изделие не может остаться
 * с применённым маршрутом, но со старым базовым: если перенос
 * базовости не удался, откатывается и сама привязка.
 *
 * Сами правила (у изделия один базовый маршрут, прежняя отметка
 * снимается) не дублируются: их держит {@link TechProcessService},
 * иначе правила базовости разъехались бы по двум сервисам.
 */
@Service
@RequiredArgsConstructor
public class ClassifierRouteService {

    private final TechProcessRepository techProcessRepository;
    private final TechProcessClassifierRepository linkRepository;
    private final TechProcessService techProcessService;

    /**
     * Активные маршруты всех изделий одним списком — для колонок
     * «Техпроцессы» и базового маршрута на странице классификатора.
     */
    @Transactional(readOnly = true)
    public List<ClassifierRouteLinkView> findAllActiveRoutes() {
        return linkRepository.findAllActiveRoutes();
    }

    /**
     * Активные маршруты с основным изделием — выбор для страницы
     * «Добавить техпроцесс».
     */
    @Transactional(readOnly = true)
    public List<ActiveRouteOptionView> findAllActiveRouteOptions() {
        return techProcessRepository.findAllActiveRouteOptions();
    }

    /**
     * Применяет маршрут к изделию и, если запрошено, переносит на
     * него базовость.
     *
     * Базовость переносят отдельным действием, а не флагом при
     * добавлении: {@link TechProcessService#makeBasicForClassifier}
     * снимает отметку с прежнего маршрута тем же запросом, которым
     * пользуется модуль техпроцессов, поэтому поведение страницы
     * классификатора и страницы техпроцессов не разойдётся.
     */
    @Transactional
    public void applyRoute(
            Long classifierId,
            Long processId,
            boolean asBasic
    ) {
        techProcessService.applyToClassifier(
                processId,
                classifierId,
                false
        );

        if (asBasic) {
            techProcessService.makeBasicForClassifier(
                    processId,
                    classifierId
            );
        }
    }
}