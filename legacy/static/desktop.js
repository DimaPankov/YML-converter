// Installed desktop mode; the browser-only development version is unchanged.
(async () => {
  const hash = new URLSearchParams(location.hash.slice(1));
  const launchToken = hash.get('desktop');
  if (launchToken) {
    sessionStorage.setItem('yml-desktop-token', launchToken);
    history.replaceState(null, '', location.pathname);
  }
  const token = sessionStorage.getItem('yml-desktop-token');
  if (!token) return;
  const control = async action => {
    const response = await fetch('/api/desktop/' + action, {
      method: 'POST', headers: {'Content-Type': 'application/json', 'X-Desktop-Token': token}, body: '{}'
    });
    if (!response.ok) throw new Error('Приложение недоступно. Откройте ярлык YML Студия повторно.');
    return response.json();
  };
  try { await control('ping'); } catch { return; }
  const button = document.createElement('button');
  button.textContent = 'Завершить работу';
  button.className = 'quiet';
  button.style.fontSize = '12px';
  document.querySelector('header .portal').replaceWith(button);
  let failed = false;
  const timer = setInterval(async () => {
    try { await control('ping'); failed = false; }
    catch {
      if (!failed) toast('Программа остановлена. Для продолжения откройте ярлык YML Студия.');
      failed = true;
    }
  }, 30000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) control('ping').catch(() => {});
  });
  button.addEventListener('click', async () => {
    const text = dirty ? 'Есть несохранённые изменения. Завершить работу без их сохранения?' : 'Закрыть программу? Сохранённые формы и товары останутся на компьютере.';
    if (!await confirmAction('Завершить работу?', text)) return;
    try {
      await control('stop');
      clearInterval(timer);
      dirty = false;
      document.body.innerHTML = '<main style="margin:auto;max-width:650px;padding-top:100px"><h1>Работа завершена</h1><p>Теперь можно закрыть это окно. Чтобы продолжить, откройте ярлык «YML Студия» на рабочем столе.</p></main>';
      window.close();
    } catch (error) { toast(error.message); }
  });
})();
