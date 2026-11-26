// Kept in a separate file so the splash works under the strict
// `script-src 'self'` CSP installed by electron/main.js.
window.splashAPI.onProgress((data) => {
    const bar = document.getElementById('progressBar');
    const text = document.getElementById('statusText');

    if (data.percent !== undefined) {
        bar.style.animation = 'none';
        bar.style.width = data.percent + '%';
    }
    if (data.message) {
        text.textContent = data.message;
    }
});
