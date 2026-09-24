(() => {
    'use strict';

    /*
     * Выпадающие меню кнопок (вид заказа-комплектующего и т. п.).
     *
     * Разметка одинаковая на всех страницах: контейнер [data-menu],
     * кнопка [data-menu-toggle] и панель. Держать обработчик в каждой
     * странице нельзя — меню появляется там, где скрипт страницы о нём
     * не знает (список заказов подключает только этот файл).
     *
     * Закрытие по щелчку вне меню и по Escape обязательно: пункты —
     * ссылки, и открытая панель осталась бы висеть после перехода
     * назад по истории.
     */
    function initializeMenus(root) {
        const scope = root || document;

        const menus = scope.querySelectorAll('[data-menu]');

        if (!menus.length) {
            return;
        }

        function close(menu) {
            menu.classList.remove('is-open');

            const toggle = menu.querySelector('[data-menu-toggle]');

            if (toggle) {
                toggle.setAttribute('aria-expanded', 'false');
            }
        }

        function closeAll(except) {
            menus.forEach((menu) => {
                if (menu !== except) {
                    close(menu);
                }
            });
        }

        menus.forEach((menu) => {
            const toggle = menu.querySelector('[data-menu-toggle]');

            if (!toggle) {
                return;
            }

            toggle.addEventListener('click', (event) => {
                event.preventDefault();
                event.stopPropagation();

                const willOpen = !menu.classList.contains('is-open');

                closeAll(menu);

                menu.classList.toggle('is-open', willOpen);
                toggle.setAttribute(
                    'aria-expanded',
                    willOpen ? 'true' : 'false'
                );
            });

            /*
             * Пункт меню — ссылка: панель закрывается до перехода,
             * иначе при возврате «назад» она осталась бы раскрытой.
             */
            menu.addEventListener('click', (event) => {
                if (event.target.closest('a')) {
                    close(menu);
                }
            });
        });

        document.addEventListener('click', () => closeAll(null));

        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                closeAll(null);
            }
        });
    }

    function initializeInterface() {
        const dateTimeElement = document.getElementById(
            'dispatcherDateTime'
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
         */
        if (mainElement) {
            mainElement.addEventListener(
                'pointerdown',
                () => {
                    const activeElement =
                        document.activeElement;

                    const sidebar =
                        document.getElementById(
                            'dispatcherSidebar'
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

        initializeMenus(document);

        updateDateTime();

        window.setInterval(
            updateDateTime,
            1000
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
