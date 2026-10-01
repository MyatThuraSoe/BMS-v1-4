import { IconButton, Tooltip } from '@mui/material';
import { HelpOutline as HelpOutlineIcon } from '@mui/icons-material';

/**
 * Small "?" affordance that explains a card or control in plain language.
 * Help text lives in the i18n `common` namespace as `help_*` keys, shared by
 * Backup Settings and System Settings. Callers pass their own `t` so the
 * component stays namespace-agnostic.
 */
const HelpTip = ({ helpKey, t, maxWidth = 340, placement = 'right-end' }) => {
  if (!helpKey || !t) return null;
  const text = t(helpKey);
  // Missing translation (key comes back verbatim) — hide the icon instead of
  // showing a tooltip that reads "help_drive_connection".
  if (!text || text === helpKey) return null;

  return (
    <Tooltip
      title={text}
      arrow
      placement={placement}
      enterTouchDelay={0}
      componentsProps={{ tooltip: { sx: { maxWidth, whiteSpace: 'pre-line', textAlign: 'left' } } }}
    >
      <IconButton
        size="small"
        aria-label={text}
        sx={{
          ml: 0.75,
          color: 'text.disabled',
          '&:hover, &:focus-visible': { color: 'primary.main' },
        }}
      >
        <HelpOutlineIcon fontSize="inherit" />
      </IconButton>
    </Tooltip>
  );
};

export default HelpTip;