const form = document.getElementById('loginForm');

if (form && (location.protocol === 'blob:' || location.protocol === 'file:')) {
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    document.getElementById('demoNote').style.display = 'block';
  });
}