(() => {
    'use strict';

    /*
     * Склад живёт рядом с цехом, диспетчерской и технологическим
     * отделом: селекторы и ID собственные (warehouse*), иначе
     * скрипты разделов перехватывали бы друг друга при случайной
     * склейке разметки.
     */
    function initializeInterface() {
        const dateTimeElement = document.getElementById('warehouseDateTime');
        const mainElement = document.querySelector('.main');
        const sidebar = document.getElementById('warehouseSidebar');

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
         * Меню раскрывается по :focus-within; щелчок по рабочей
         * области снимает фокус, чтобы свёрнутое состояние
         * возвращалось без необходимости наводить курсор куда-то
         * специально.
         */
        if (mainElement && sidebar) {
            mainElement.addEventListener('pointerdown', () => {
                const activeElement = document.activeElement;

                if (
                    sidebar.contains(activeElement) &&
                    typeof activeElement.blur === 'function'
                ) {
                    activeElement.blur();
                }
            });
        }

        /*
         * Выпадающие подменю: могут быть несколько (Подсборки,
         * Комплектование). Каждый .warehouse-dropdown-trigger
         * управляет следующим за ним .warehouse-submenu. Скрипт
         * собирает все такие пары и обрабатывает независимо.
         * Если пользователь зашёл на страницу подменю, оно
         * автоматически раскрывается при загрузке.
         */
        initializeDropdowns(sidebar);

        updateDateTime();
        window.setInterval(updateDateTime, 1000);
    }

    function initializeDropdowns(sidebar) {
        if (!sidebar) {
            return;
        }

        const triggers = sidebar.querySelectorAll('.warehouse-dropdown-trigger');

        triggers.forEach((trigger) => {
            /*
             * Каждый .warehouse-submenu идёт сразу после своего
             * .warehouse-dropdown-trigger, поэтому берём nextElementSibling.
             */
            let submenu = trigger.nextElementSibling;
            while (submenu && !submenu.classList.contains('warehouse-submenu')) {
                submenu = submenu.nextElementSibling;
            }

            if (!submenu) {
                return;
            }

            function setOpen(isOpen) {
                trigger.classList.toggle('is-open', isOpen);
                submenu.classList.toggle('is-open', isOpen);
                trigger.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
            }

            /*
             * Раздел активен — подменю открыто. Признак берётся по
             * подпункту с is-active.
             */
            if (submenu.querySelector('.warehouse-submenu__item.is-active')) {
                setOpen(true);
            }

            trigger.addEventListener('click', (event) => {
                event.preventDefault();
                setOpen(!trigger.classList.contains('is-open'));
            });

            /*
             * Клик вне подменю закрывает его.
             */
            document.addEventListener('click', (event) => {
                if (trigger.contains(event.target) || submenu.contains(event.target)) {
                    return;
                }

                if (trigger.classList.contains('is-open')) {
                    setOpen(false);
                }
            });

            document.addEventListener('keydown', (event) => {
                if (event.key !== 'Escape') {
                    return;
                }

                if (trigger.classList.contains('is-open')) {
                    setOpen(false);
                    trigger.focus();
                }
            });
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeInterface);
    } else {
        initializeInterface();
    }
})();