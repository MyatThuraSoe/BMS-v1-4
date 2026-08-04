import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

// Import translation files from src/locales/
import en from './locales/en.json';
import my from './locales/my.json';
import th from './locales/th.json';
import zh from './locales/zh.json';
import ru from './locales/ru.json';
import es from './locales/es.json'; 
import ja from './locales/ja.json';

const resources = {
  en: { translation: en },
  my: { translation: my },
  th: { translation: th },
  zh: { translation: zh },
  ru: { translation: ru },
  es: { translation: es },
  ja: { translation: ja },
};

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
    fallbackLng: 'en',
    debug: import.meta.env.DEV,
    interpolation: { escapeValue: false },
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
    },
  });

export default i18n;