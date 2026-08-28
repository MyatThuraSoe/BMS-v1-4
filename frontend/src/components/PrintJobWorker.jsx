import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../context/AuthContext';
import { notifyError } from '../utils/notify';
import { counterPrintService, receiptService, shopInfoService, receiptCustomizationService } from '../api/services';
import directPrint from '../services/directPrintService';
import { generatePrintHtml, generateQRDataUrl } from './ReceiptDocument';

const PrintJobWorker = () => {
  const { user } = useAuth();
  const { t } = useTranslation('pos');

  const { data: shopInfoResponse } = useQuery({
    queryKey: ['print-worker-shop-info'],
    queryFn: () => shopInfoService.get(),
    enabled: Boolean(user) && directPrint.isAvailable(),
    staleTime: 60000,
  });
  const { data: customizationResponse } = useQuery({
    queryKey: ['print-worker-customization'],
    queryFn: () => receiptCustomizationService.get(),
    enabled: Boolean(user) && directPrint.isAvailable(),
    staleTime: 60000,
  });

  const shopInfo = shopInfoResponse?.data || {};
  const customization = customizationResponse?.data || {};
  const printConfigLoaded = Boolean(shopInfoResponse?.data && customizationResponse?.data);

  useEffect(() => {
    if (!user || !directPrint.isAvailable() || !printConfigLoaded) return undefined;

    let stopped = false;
    let checking = false;

    const processPrintJob = async () => {
      if (stopped || checking) return;
      checking = true;
      try {
        const response = await counterPrintService.claimNextReceipt();
        const job = response?.data;
        if (!job || job.status === 'EMPTY') return;

        let success = false;
        try {
          const receiptResponse = await receiptService.getByInvoiceNumber(job.invoiceNumber);
          const receipt = receiptResponse?.data;
          if (!receipt) throw new Error(`Receipt data was not returned for ${job.invoiceNumber}`);

          let logoDataUrl = null;
          if (shopInfo.hasLogo) {
            try {
              const blob = await shopInfoService.getLogo();
              logoDataUrl = await new Promise((resolve) => {
                const reader = new FileReader();
                reader.onload = () => resolve(reader.result);
                reader.onerror = () => resolve(null);
                reader.readAsDataURL(blob);
              });
            } catch { /* print without logo */ }
          }

          const qrDataUrl = customization.showQRCode
            ? await generateQRDataUrl(receipt.invoiceNumber)
            : null;
          const html = generatePrintHtml(receipt, shopInfo, customization, logoDataUrl, qrDataUrl);
          const paperWidthMm = Math.max(40,
            parseInt(String(customization.paperSize || '58').replace(/\D/g, ''), 10) || 58);
          const result = await directPrint.print(html, job.printerName || null, paperWidthMm);
          success = Boolean(result?.success);
          if (!success) {
            const reason = result?.error || 'The printer rejected the print job';
            console.error('[Print queue] Print failed', { jobId: job.jobId, invoiceNumber: job.invoiceNumber, reason });
            notifyError(`Print failed for ${job.invoiceNumber}: ${reason}`);
          }
        } catch (error) {
          const reason = error.friendlyMessage || error.response?.data?.message || error.message || 'Unknown print error';
          console.error('[Print queue] Job failed', { jobId: job.jobId, invoiceNumber: job.invoiceNumber, reason, error });
          notifyError(`Print failed for ${job.invoiceNumber}: ${reason}`);
        } finally {
          try {
            await counterPrintService.completeReceipt(job.jobId, success);
          } catch (error) {
            console.warn('[Print queue] Completion failed:', error.message);
          }
        }
      } catch (error) {
        if (!stopped && error.response?.status !== 401) {
          console.warn('[Print queue] Poll failed:', error.message);
        }
      } finally {
        checking = false;
      }
    };

    processPrintJob();
    const timer = window.setInterval(processPrintJob, 1000);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [customization, shopInfo, printConfigLoaded, t, user]);

  return null;
};

export default PrintJobWorker;
