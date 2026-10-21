// Compose reads navigator.languages through Intl.Locale. Some headless Linux
// environments expose POSIX tags (C, en_US.UTF-8) that Intl rejects.
(() => {
  const normalize = value => {
    if (typeof value !== "string") return null;
    const tag = value.split(/[.@]/, 1)[0].replaceAll("_", "-");
    try { return Intl.getCanonicalLocales(tag)[0] || null; }
    catch { return null; }
  };
  const original = Array.from(navigator.languages || []);
  const languages = [...new Set(original.map(normalize).filter(Boolean))];
  if (!languages.length) languages.push(normalize(navigator.language) || "en-US");
  if (original.length !== languages.length || original.some((tag, index) => tag !== languages[index])) {
    Object.defineProperty(navigator, "languages", {configurable: true, value: Object.freeze(languages)});
  }
  const language = normalize(navigator.language) || languages[0];
  if (navigator.language !== language) {
    Object.defineProperty(navigator, "language", {configurable: true, value: language});
  }
})();
