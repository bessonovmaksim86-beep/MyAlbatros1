(() => {
    'use strict';

    /*
     * Модуль администрирования использует собственные селекторы
     * (adminDateTime, adminSidebar), чтобы скрипты разделов не мешали
     * друг другу при случайной склейке разметки.
     */
    function initializeInterface() {
        const dateTimeElement = document.getElementById('adminDateTime');
        const mainElement = document.querySelector('.main');
        const sidebar = document.getElementById('adminSidebar');

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
         * Меню раскрывается по :focus-within; щелчок по рабочей области
         * снимает фокус, чтобы свёрнутое состояние возвращалось без
         * необходимости наводить курсор куда-то специально.
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
         * Выпадающие подменю: могут быть несколько. Каждый
         * .admin-dropdown-trigger управляет следующим за ним
         * .admin-submenu. Скрипт собирает все такие пары и
         * обрабатывает независимо.
         */
        initializeDropdowns(sidebar);

        updateDateTime();
        window.setInterval(updateDateTime, 1000);
    }

    function initializeDropdowns(sidebar) {
        if (!sidebar) {
            return;
        }

        const triggers = sidebar.querySelectorAll('.admin-dropdown-trigger');

        triggers.forEach((trigger) => {
            let submenu = trigger.nextElementSibling;
            while (submenu && !submenu.classList.contains('admin-submenu')) {
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
            if (submenu.querySelector('.admin-submenu__item.is-active')) {
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
