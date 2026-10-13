(() => {
    'use strict';

    /*
     * Пауза между последним нажатием клавиши и запросом.
     *
     * Без неё на каждое изменение текста уходил бы запрос к базе,
     * а поисковых выборок четыре: набор из восьми букв превращался
     * бы в восемь прогонов подряд.
     */
    const SEARCH_DEBOUNCE_DELAY = 250;

    /*
     * Короче двух символов поиск бессмыслен: подстрока из одной
     * буквы выбирает почти весь классификатор и только шумит
     * в выпадающем списке.
     */
    const MIN_QUERY_LENGTH = 2;

    function initializeInterface() {
        const dateTimeElement = document.getElementById(
            'technologyDateTime'
        );

        const mainElement = document.querySelector('.main');

        /*
         * Обновление текущей даты и времени.
         */
        function updateDateTime() {
            if (!dateTimeElement) {
                return;
            }

            const now = new Date();

            dateTimeElement.dateTime = now.toISOString();

            dateTimeElement.textContent =
                new Intl.DateTimeFormat('ru-RU', {
                    day: '2-digit',
                    month: '2-digit',
                    year: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                    second: '2-digit'
                }).format(now);
        }

        /*
         * Если меню было раскрыто с клавиатуры через focus-within,
         * щелчок по основному полю снимает фокус и сворачивает меню.
         *
         * При работе мышью меню сворачивается автоматически,
         * как только курсор покидает левую панель.
         */
        if (mainElement) {
            mainElement.addEventListener(
                'pointerdown',
                () => {
                    const activeElement =
                        document.activeElement;

                    const sidebar =
                        document.getElementById(
                            'technologySidebar'
                        );

                    if (
                        sidebar &&
                        activeElement &&
                        sidebar.contains(activeElement) &&
                        typeof activeElement.blur === 'function'
                    ) {
                        activeElement.blur();
                    }
                }
            );
        }

        updateDateTime();

        window.setInterval(
            updateDateTime,
            1000
        );

        initializeGlobalSearch();
    }

    /* ============================================================
       ГЛОБАЛЬНЫЙ ПОИСК
       ============================================================ */

    /*
     * Поиск по техпроцессам, изделиям, операциям и рабочим местам.
     *
     * Работает с любой страницы отдела: разметка поля и панели
     * результатов приходит из фрагмента topbar, а адрес API подставляет
     * Thymeleaf в data-search-url — зашивать путь в скрипте нельзя,
     * иначе поиск развалится при выкладке не на корень сервера.
     */
    function initializeGlobalSearch() {
        const input = document.getElementById(
            'technologyGlobalSearch'
        );

        const panel = document.getElementById(
            'technologySearchResults'
        );

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
         * Нужно, чтобы ответ пришла раньше, чем пользователь успел
         * дописать букву.
         */
        let shownQuery = '';

        let activeIndex = -1;

        function items() {
            return Array.from(
                panel.querySelectorAll('.technology-search__item')
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
             * поэтому упираиться в край и застревать бесполезно.
             */
            const next = (activeIndex + step + total) % total;

            markActive(next);
        }

        /*
         * Строка рисуют узлами, а не готовым HTML: заголовок и подпись
         * приходят из базы и могут содержать <, & и кавычки.
         */
        function buildItem(result) {
            const link = document.createElement('a');

            link.className = 'technology-search__item';
            link.href = result.url;
            link.setAttribute('role', 'option');
            link.setAttribute('aria-selected', 'false');

            /*
             * Сводку открывают в новой вкладке: страница отдела, где
             * набран запрос, не должна теряться — поиск по системе не
             * обязан выкидывать пользователя из его интерфейса.
             */
            link.target = '_blank';
            link.rel = 'noopener';

            const data = document.createElement('span');

            data.className = 'technology-search__item-data';

            const title = document.createElement('span');

            title.className = 'technology-search__item-title';
            title.textContent = result.title || '';

            const subtitle = document.createElement('span');

            subtitle.className = 'technology-search__item-subtitle';
            subtitle.textContent = result.subtitle || '';

            data.append(title, subtitle);
            link.append(data);

            if (result.categoryLabel) {
                const kind = document.createElement('span');

                kind.className = 'technology-search__item-kind';
                kind.textContent = result.categoryLabel;

                link.append(kind);
            }

            return link;
        }

        function render(results, query) {
            panel.textContent = '';

            if (results.length === 0) {
                const empty = document.createElement('div');

                empty.className = 'technology-search__empty';
                empty.textContent =
                    'Ничего не найдено по запросу «' + query + '»';

                panel.append(empty);
                panel.hidden = false;
                activeIndex = -1;

                return;
            }

            /*
             * Группы идут в порядке ответа сервера: техпроцессы,
             * изделия, операции, рабочие места. Заголовок группы
             * вставляют один раз — при смене категории.
             */
            let currentCategory = null;

            const fragment = document.createDocumentFragment();

            results.forEach(result => {
                if (result.category !== currentCategory) {
                    currentCategory = result.category;

                    const label = document.createElement('div');

                    label.className = 'technology-search__group-label';
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
                 * Пока шёл запрос, пользователь мог стёр текст или
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
                             * target="_blank"). Текущая страница отдела
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
            initializeInterface
        );
    } else {
        initializeInterface();
    }
})();
