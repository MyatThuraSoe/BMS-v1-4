import { useTranslation } from 'react-i18next';
import { Select, MenuItem, Box, Typography } from '@mui/material';
import { Language as LanguageIcon } from '@mui/icons-material';
import { userService } from '../api/services';

const LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'my', label: 'မြန်မာ' },
  { code: 'ja', label: '日本語' },
  { code: 'th', label: 'ไทย' },
  { code: 'fr', label: 'Français' },
];

export default function LanguageSwitcher({ compact = false }) {
  const { i18n, t } = useTranslation('common');

  const current = i18n.language?.split('-')[0] || 'en';

  const changeLanguage = async (code) => {
    await i18n.changeLanguage(code);           // switches UI immediately
    document.documentElement.lang = code;       // updates <html lang="..."> for accessibility/fonts

    // Persist to backend so preference follows the user across devices
    try {
      await userService.updateLanguage(code);
    } catch (e) {
      // fail silently — localStorage already has it cached via i18next-browser-languagedetector
      console.warn('Could not save language preference to server', e);
    }
  };

  if (compact) {
    return (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        <LanguageIcon sx={{ fontSize: 18, opacity: 0.7 }} />
        <Select
          value={current}
          onChange={(e) => changeLanguage(e.target.value)}
          size="small"
          variant="outlined"
          aria-label={t('language')}
          sx={{ fontSize: '0.8rem', minWidth: 86, color: 'inherit', '.MuiOutlinedInput-notchedOutline': { borderColor: 'currentColor', opacity: 0.35 } }}
        >
          {LANGUAGES.map((lang) => (
            <MenuItem key={lang.code} value={lang.code}>{lang.label}</MenuItem>
          ))}
        </Select>
      </Box>
    );
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, minWidth: 220 }}>
      <Typography variant="body2" color="text.secondary">{t('language')}</Typography>
      <Select
        value={current}
        onChange={(e) => changeLanguage(e.target.value)}
        fullWidth
        aria-label={t('language')}
      >
        {LANGUAGES.map((lang) => (
          <MenuItem key={lang.code} value={lang.code}>{lang.label}</MenuItem>
        ))}
      </Select>
    </Box>
  );
}
