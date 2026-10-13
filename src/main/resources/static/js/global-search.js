(() => {
    'use strict';

    /*
     * Общий выпадающий поиск для всех интерфейсов системы.
     *
     * Повторяет поиск технологического отдела один в один: тот же
     * адрес API (/technology/search), та же выдача и то же открытие
     * найденного — сводка по объекту открывается в новой вкладке,
     * поэтому страница модуля, где набран запрос, не теряется.
     *
     * Скрипт один на все разделы, а не копия на модуль: разметку
     * даёт фрагмент fragments/global-search, а адрес API подставляет
     * Thymeleaf в data-search-url — зашивать путь в скрипте нельзя,
     * иначе поиск развалится при выкладке приложения не на корень
     * сервера.
     */

    /*
     * Пауза между последним нажатием клавиши и запросом.
     *
     * Без неё на каждое изменение текста уходил бы запрос к базе,
     * а поисковых выборок шесть: набор из восьми букв превращался
     * бы в восемь прогонов подряд.
     */
    const SEARCH_DEBOUNCE_DELAY = 250;

    /*
     * Короче двух символов поиск бессмыслен: подстрока из одной
     * буквы выбирает почти весь классификатор и только шумит
     * в выпадающем списке.
     */
    const MIN_QUERY_LENGTH = 2;

    const INPUT_ID = 'globalSearchInput';
    const RESULTS_ID = 'globalSearchResults';

    function initializeGlobalSearch() {
        const input = document.getElementById(INPUT_ID);
        const panel = document.getElementById(RESULTS_ID);
        const searchUrl = panel && panel.dataset.searchUrl;

        if (!input || !panel || !searchUrl) {
            return;
        }

        let timer = null;

        /*
         * Последний запрос отменяют: ответ на уже стёртый текст
         * не должен перерисовывать список поверх актуального.
         */
        let pending = null;

        /*
         * Запрошенное слово, результат которого показан на экране.
         * Нужно, чтобы не дёргать базу повторно, если пользователь
         * вернул фокус в то же поле.
         */
        let shownQuery = '';

        let activeIndex = -1;

        function items() {
            return Array.from(
                panel.querySelectorAll('.gs__item')
            );
        }

        function hide() {
            panel.hidden = true;
            panel.textContent = '';
            activeIndex = -1;
            shownQuery = '';
        }

        function markActive(index) {
            const list = items();

            list.forEach((item, position) => {
                item.classList.toggle(
                    'is-active',
                    position === index
                );

                item.setAttribute(
                    'aria-selected',
                    position === index ? 'true' : 'false'
                );
            });

            activeIndex = index;

            const current = list[index];

            if (current && typeof current.scrollIntoView === 'function') {
                current.scrollIntoView({ block: 'nearest' });
            }
        }

        function moveActive(step) {
            const total = items().length;

            if (total === 0) {
                return;
            }

            /*
             * Ход по кругу: от последней строки вверх — к первой,
             * и наоборот. Строки в списке меняются на каждом символе,
             * поэтому упираться в край и застревать бесполезно.
             */
            const next = (activeIndex + step + total) % total;

            markActive(next);
        }

        /*
         * Строку рисуют узлами, а не готовым HTML: заголовок и подпись
         * приходят из базы и могут содержать <, & и кавычки.
         */
        function buildItem(result) {
            const link = document.createElement('a');

            link.className = 'gs__item';
            link.href = result.url;
            link.setAttribute('role', 'option');
            link.setAttribute('aria-selected', 'false');

            /*
             * Сводку открывают в новой вкладке: страница модуля, где
             * набран запрос, не должна теряться — поиск по системе не
             * обязан выкидывать пользователя из его интерфейса.
             */
            link.target = '_blank';
            link.rel = 'noopener';

            const data = document.createElement('span');

            data.className = 'gs__item-data';

            const title = document.createElement('span');

            title.className = 'gs__item-title';
            title.textContent = result.title || '';

            const subtitle = document.createElement('span');

            subtitle.className = 'gs__item-subtitle';
            subtitle.textContent = result.subtitle || '';

            data.append(title, subtitle);
            link.append(data);

            if (result.categoryLabel) {
                const kind = document.createElement('span');

                kind.className = 'gs__item-kind';
                kind.textContent = result.categoryLabel;

                link.append(kind);
            }

            return link;
        }

        function render(results, query) {
            panel.textContent = '';

            if (results.length === 0) {
                const empty = document.createElement('div');

                empty.className = 'gs__empty';
                empty.textContent =
                    'Ничего не найдено по запросу «' + query + '»';

                panel.append(empty);
                panel.hidden = false;
                activeIndex = -1;

                return;
            }

            /*
             * Группы идут в порядке ответа сервера: заказы,
             * техпроцессы, изделия, операции, рабочие места. Заголовок
             * группы вставляют один раз — при смене категории.
             */
            let currentCategory = null;

            const fragment = document.createDocumentFragment();

            results.forEach(result => {
                if (result.category !== currentCategory) {
                    currentCategory = result.category;

                    const label = document.createElement('div');

                    label.className = 'gs__group-label';
                    label.textContent = result.categoryLabel || '';

                    fragment.append(label);
                }

                fragment.append(buildItem(result));
            });

            panel.append(fragment);
            panel.hidden = false;

            markActive(-1);
        }

        async function request(query) {
            if (pending) {
                pending.abort();
            }

            pending = new AbortController();

            try {
                const response = await fetch(
                    searchUrl + '?q=' + encodeURIComponent(query),
                    {
                        signal: pending.signal,
                        headers: {
                            'Accept': 'application/json'
                        }
                    }
                );

                if (!response.ok) {
                    return;
                }

                const results = await response.json();

                /*
                 * Пока шёл запрос, пользователь мог стереть текст или
                 * закрыть панель: показывать устаревший ответ нельзя.
                 */
                if (query !== input.value.trim()) {
                    return;
                }

                shownQuery = query;
                render(results, query);

            } catch (error) {
                /*
                 * Отмена предыдущего запроса — штатная ситуация,
                 * а не сбой: молча выходим.
                 */
                if (error && error.name === 'AbortError') {
                    return;
                }

                hide();
            }
        }

        function schedule(query) {
            if (timer) {
                window.clearTimeout(timer);
            }

            if (query.length < MIN_QUERY_LENGTH) {
                if (pending) {
                    pending.abort();
                    pending = null;
                }

                hide();

                return;
            }

            timer = window.setTimeout(() => {
                timer = null;
                request(query);
            }, SEARCH_DEBOUNCE_DELAY);
        }

        input.addEventListener(
            'input',
            () => {
                schedule(input.value.trim());
            }
        );

        input.addEventListener(
            'keydown',
            event => {
                switch (event.key) {
                    case 'ArrowDown':
                        event.preventDefault();
                        moveActive(1);
                        break;

                    case 'ArrowUp':
                        event.preventDefault();
                        moveActive(-1);
                        break;

                    case 'Enter': {
                        const list = items();
                        const target = activeIndex >= 0
                            ? list[activeIndex]
                            : list[0];

                        if (target) {
                            event.preventDefault();

                            /*
                             * Enter открывает сводку в новой вкладке —
                             * так же, как щелчок по строке (у ссылки
                             * target="_blank"). Текущая страница модуля
                             * при этом остаётся открытой.
                             */
                            window.open(
                                target.href,
                                '_blank',
                                'noopener'
                            );
                        }

                        break;
                    }

                    case 'Escape':
                        hide();
                        break;

                    default:
                        break;
                }
            }
        );

        /*
         * Фокус возвращает на экран уже готовый список: повторно
         * набирать то же слово незачем.
         */
        input.addEventListener(
            'focus',
            () => {
                const query = input.value.trim();

                if (query.length < MIN_QUERY_LENGTH) {
                    return;
                }

                if (shownQuery === query && panel.textContent) {
                    panel.hidden = false;
                    return;
                }

                request(query);
            }
        );

        document.addEventListener(
            'pointerdown',
            event => {
                const target = event.target;

                if (
                    target instanceof Node &&
                    (
                        panel.contains(target) ||
                        input.contains(target)
                    )
                ) {
                    return;
                }

                hide();
            }
        );
    }

    if (document.readyState === 'loading') {
        document.addEventListener(
            'DOMContentLoaded',
            initializeGlobalSearch
        );
    } else {
        initializeGlobalSearch();
    }
})();
