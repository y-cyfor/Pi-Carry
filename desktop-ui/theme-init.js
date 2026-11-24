  // 首屏前先套用本地镜像，避免强制白/黑主题时闪一帧。
  (() => {
    try {
      const mode = localStorage.getItem('pi_carry_theme') || 'auto';
      const dark = mode === 'dark' || (mode === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
      document.documentElement.dataset.theme = dark ? 'dark' : 'light';
      document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
    } catch { document.documentElement.dataset.theme = 'dark'; }
  })();
