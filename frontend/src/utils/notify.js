import { enqueueSnackbar } from 'notistack';

export const notifySuccess = (message) => {
  enqueueSnackbar(message, { variant: 'success' });
};

export const notifyError = (message) => {
  enqueueSnackbar(message, { variant: 'error' });
};

export const notifyWarning = (message) => {
  enqueueSnackbar(message, { variant: 'warning' });
};

export const notifyInfo = (message) => {
  enqueueSnackbar(message, { variant: 'info' });
};

