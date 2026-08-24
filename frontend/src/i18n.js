import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import HttpBackend from 'i18next-http-backend';
import LanguageDetector from 'i18next-browser-languagedetector';

i18n
  .use(HttpBackend)
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    fallbackLng: 'en',
    supportedLngs: ['en', 'my', 'ja', 'th', 'fr'],
    ns: ['common', 'nav', 'pos', 'inventory', 'sales', 'purchases', 'customers', 'accounting', 'reports', 'dashboard', 'cash', 'settings', 'users', 'auth', 'errors', 'about', 'ar', 'orders'],
    defaultNS: 'common',
    backend: {
      loadPath: '/locales/{{lng}}/{{ns}}.json',
    },
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
      lookupLocalStorage: 'bms_language',
    },
    interpolation: {
      escapeValue: false, // React already escapes output
    },
    react: {
      useSuspense: true,
    },
  });

export default i18n;
