(() => {
    'use strict';

    /*
     * Страница поиска в общем интерфейсе.
     *
     * Отличается от выпадающего поиска в шапке отдела только
     * выводом: здесь плоский список на всю ширину, а строка ведёт в
     * сводку по объекту (url результата), а не в раздел. Сервер один и
     * тот же — /technology/search, — поэтому выдача совпадает один в
     * один с выпадающим списком.
     */
    const SEARCH_DEBOUNCE_DELAY = 250;

    const MIN_QUERY_LENGTH = 2;

    function initializeSearchPage() {
        const input = document.getElementById('searchPageInput');
        const panel = document.getElementById('searchPageResults');
        const searchUrl = panel && panel.dataset.searchUrl;

        if (!input || !panel || !searchUrl) {
            return;
        }

        let timer = null;
        let pending = null;

        function hideResults() {
            panel.textContent = '';
        }

        function buildItem(result) {
            const link = document.createElement('a');

            link.className = 'search-page__item';
            link.href = result.url;

            const data = document.createElement('span');
            data.className = 'search-page__item-data';

            const title = document.createElement('span');
            title.className = 'search-page__item-title';
            title.textContent = result.title || '';

            const subtitle = document.createElement('span');
            subtitle.className = 'search-page__item-subtitle';
            subtitle.textContent = result.subtitle || '';

            data.append(title, subtitle);
            link.append(data);

            if (result.categoryLabel) {
                const kind = document.createElement('span');
                kind.className = 'search-page__item-kind';
                kind.textContent = result.categoryLabel;
                link.append(kind);
            }

            return link;
        }

        function render(results, query) {
            panel.textContent = '';

            if (results.length === 0) {
                const empty = document.createElement('div');

                empty.className = 'search-page__empty';
                empty.textContent =
                    'Ничего не найдено по запросу «' + query + '»';

                panel.append(empty);

                return;
            }

            let currentCategory = null;
            let list = null;

            const fragment = document.createDocumentFragment();

            results.forEach(result => {
                /*
                 * Группа рисуется при смене категории: заголовок
                 * категории и сетка строк под ним.
                 */
                if (result.category !== currentCategory) {
                    currentCategory = result.category;

                    const group = document.createElement('div');
                    group.className = 'search-page__group';

                    const label = document.createElement('div');
                    label.className = 'search-page__group-label';
                    label.textContent = result.categoryLabel || '';

                    list = document.createElement('div');
                    list.className = 'search-page__list';

                    group.append(label, list);
                    fragment.append(group);
                }

                list.append(buildItem(result));
            });

            panel.append(fragment);
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

                if (query !== input.value.trim()) {
                    return;
                }

                render(results, query);

            } catch (error) {
                if (error && error.name === 'AbortError') {
                    return;
                }

                hideResults();
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

                hideResults();

                return;
            }

            timer = window.setTimeout(() => {
                timer = null;
                request(query);
            }, SEARCH_DEBOUNCE_DELAY);
        }

        input.addEventListener('input', () => {
            schedule(input.value.trim());
        });

        /*
         * Кнопка «Найти» и Enter: форма не отправляется (onsubmit
         * возвращает false), поиск идёт тем же запросом.
         */
        const form = input.closest('form');

        if (form) {
            form.addEventListener('submit', event => {
                event.preventDefault();
                request(input.value.trim());
            });
        }

        /*
         * Строка поиска могла прийти в адресе (/search?q=...) — так
         * со сводки возвращаются к тому же запросу.
         */
        const initial = new URLSearchParams(window.location.search).get('q');

        if (initial && initial.trim().length >= MIN_QUERY_LENGTH) {
            input.value = initial.trim();
            request(input.value);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeSearchPage);
    } else {
        initializeSearchPage();
    }
})();
