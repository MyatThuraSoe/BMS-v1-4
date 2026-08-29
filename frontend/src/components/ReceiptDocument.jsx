/**
 * ReceiptDocument.jsx
 *
 * A single shared component that renders a complete receipt.
 * Used by:
 *  - ReceiptCustomization page (live WYSIWYG preview with mock data)
 *  - ReceiptPreview page (real receipt data)
 *  - POS post-sale receipt dialog
 *  - generatePrintHtml() helper for direct/silent printing
 *
 * The rendered HTML is identical between screen and paper so what you
 * see in the preview IS what prints on the physical paper.
 */

import { useEffect, useState } from 'react';
import { Box, Typography } from '@mui/material';
import ShopLogo from './ShopLogo';
import { formatCurrency, formatAmountPlain, formatReceiptDateTime } from '../utils/helpers';

import QRCode from 'qrcode';

// --- Constants ---
const FONT_SIZE_MAP = { small: '0.72rem', normal: '0.83rem', large: '0.95rem' };
const FONT_SIZE_PX_MAP = { small: '11px', normal: '13px', large: '15px' };

function getQRSize(paperSize) {
  const mm = Math.max(40, parseInt(String(paperSize || '58').replace(/\D/g, ''), 10) || 58);
  return Math.min(120, Math.round(mm * 1.6));
}

/**
 * Renders a QR code as a data-URL image. Works both on screen and in print HTML.
 */
function QRImage({ value, size }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    let mounted = true;
    if (value) {
      QRCode.toDataURL(String(value), { width: 400, margin: 1, color: { dark: '#111', light: '#ffffff' } })
        .then((u) => { if (mounted) setUrl(u); })
        .catch(() => { if (mounted) setUrl(''); });
    } else {
      setUrl('');
    }
    return () => { mounted = false; };
  }, [value]);

  if (!url) return null;
  return (
    <img
      src={url}
      width={size}
      height={size}
      alt="QR"
      style={{ display: 'block', imageRendering: 'pixelated' }}
    />
  );
}

/**
 * Resolves divider border CSS from a style string.
 */
function getDividerBorder(style) {
  if (!style || style === 'none') return 'none';
  return `1px ${style} #999`;
}

/**
 * The receipt content itself. Renders identically for screen and print.
 *
 * Props:
 *   receipt       – { invoiceNumber, saleDate, customerName, items[], subTotal,
 *                     taxAmount, discountAmount, totalAmount, amountPaid }
 *   shopInfo      – { shopName, address, phone, hasLogo }
 *   customization – { logoSize, showLogo, showAddress, showPhone, headerAlign,
 *                     fontSize, dividerStyle, boldShopName, headerText,
 *                     mainMessage, footerText, paperSize, timeFormat }
 *   isMockPreview – if true, show sample data where receipt fields are missing
 *   logoPreview   – optional object-URL for a logo preview image (used in ShopInfo edit)
 */
const ReceiptDocument = ({
  receipt = {},
  shopInfo = {},
  customization = {},
  isMockPreview = false,
  logoPreview,
  qrDataUrl, // optional — rendered under the footer text when provided
}) => {
  const {
    logoSize = 80,
    showLogo = true,
    showAddress = true,
    showPhone = true,
    headerAlign = 'center',
    fontSize = 'normal',
    dividerStyle = 'dashed',
    boldShopName = true,
    showQRCode = false,
    showShopName = true,
    showCreditInfo = true,
    showTax = true,
    showDiscount = true,
    paperSize = '58',
    headerText = '',
    mainMessage = 'Please keep this receipt for your records.',
    footerText = 'Thank you for your business!',
    timeFormat = '12',
  } = customization;

  const shopName  = shopInfo.shopName  || (isMockPreview ? 'Your Shop Name' : '');
  const address   = shopInfo.address   || (isMockPreview ? '123 Market Street' : '');
  const phone     = shopInfo.phone     || (isMockPreview ? '+1 234 567 890' : '');

  const dividerBorder = getDividerBorder(dividerStyle);
  const fontSizeVal   = FONT_SIZE_MAP[fontSize] || FONT_SIZE_MAP.normal;

  // --- Mock data for preview ---
  const mockItems = [
    { productName: 'Coffee Latte',  quantity: 2, unit: 'pcs', unitPrice: 5.00,  totalPrice: 10.00 },
    { productName: 'Green Tea',      quantity: 1, unit: 'pcs', unitPrice: 3.50,  totalPrice: 3.50 },
    { productName: 'Cheese Cake',    quantity: 1, unit: 'pcs', unitPrice: 4.50,  totalPrice: 4.50 },
  ];
  const mockTotal    = 18.00;
  const mockPaid     = 20.00;
  const mockSubtotal = 18.00;
  const mockBalanceDue   = 18.00;
  const mockDueDate      = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString().slice(0, 10);

  const items          = (isMockPreview ? mockItems   : receipt.items)     || [];
  const invoiceNumber  = isMockPreview  ? 'INV-1001'  : (receipt.invoiceNumber || '');
  const saleDate       = isMockPreview  ? new Date()  : receipt.saleDate;
  const customerName   = isMockPreview  ? null        : receipt.customerName;
  const subTotal       = isMockPreview  ? mockSubtotal : (receipt.subTotal ?? receipt.totalAmount ?? 0);
  const taxAmount      = isMockPreview  ? 0            : (receipt.taxAmount ?? 0);
  const discountAmount = isMockPreview  ? 0            : (receipt.discountAmount ?? 0);
  const totalAmount    = isMockPreview  ? mockTotal    : (receipt.totalAmount ?? 0);
  const amountPaid     = isMockPreview  ? mockPaid     : (receipt.amountPaid ?? 0);
  const change         = amountPaid - totalAmount;

  // Credit sale info (shown for CREDIT sales only, when enabled in customization)
  const isCredit        = isMockPreview ? true : (receipt.saleType || receipt.paymentStatus) === 'CREDIT';
  const showCreditInfo_ = Boolean(showCreditInfo) && isCredit;
  const balanceDue      = isMockPreview ? mockBalanceDue : (receipt.balanceDue ?? (Number(totalAmount) - Number(amountPaid)));
  const dueDate         = isMockPreview ? mockDueDate     : receipt.dueDate;
  const dueDateLabel    = dueDate ? String(dueDate).slice(0, 10) : '';

  const baseStyle = {
    fontFamily: '"Courier New", Courier, monospace',
    fontSize: fontSizeVal,
    color: '#111',
    lineHeight: 1.5,
  };

  const rowStyle = {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    mb: '2px',
  };

  const dividerStyle_ = dividerBorder !== 'none'
    ? { borderTop: dividerBorder, my: '6px' }
    : { my: '6px' };

  return (
    <Box sx={baseStyle}>
      {/* ===== HEADER: Logo + Shop Name + Contact ===== */}
      <Box sx={{ textAlign: headerAlign, mb: '8px' }}>
        {showLogo && (
          <Box sx={{ display: 'flex', justifyContent: headerAlign === 'left' ? 'flex-start' : headerAlign === 'right' ? 'flex-end' : 'center', mb: '6px' }}>
            <ShopLogo
              size={logoSize}
              preview={logoPreview}
            />
          </Box>
        )}

        {showShopName && (
          <Box
            sx={{
              fontWeight: boldShopName ? 700 : 400,
              fontSize: `calc(${fontSizeVal} + 0.15rem)`,
              letterSpacing: '0.02em',
              mb: '2px',
            }}
          >
            {shopName}
          </Box>
        )}

        {showAddress && address && (
          <Box sx={{ fontSize: fontSizeVal }}>{address}</Box>
        )}
        {showPhone && phone && (
          <Box sx={{ fontSize: fontSizeVal }}>{phone}</Box>
        )}
      </Box>

      {/* ===== HEADER BANNER TEXT ===== */}
      {headerText?.trim() ? (
        <Box sx={{ ...dividerStyle_, textAlign: 'center', fontWeight: 700, py: '4px' }}>
          {headerText}
        </Box>
      ) : (
        <Box sx={dividerStyle_} />
      )}

      {/* ===== INVOICE META ===== */}
      <Box sx={{ mb: '8px' }}>
        <Box sx={rowStyle}>
          <span>Invoice No:</span>
          <span>{invoiceNumber}</span>
        </Box>
        <Box sx={rowStyle}>
          <span>Date:</span>
          <span>{formatReceiptDateTime(saleDate, timeFormat)}</span>
        </Box>
        {customerName && customerName !== 'Walk-in' && (
          <Box sx={rowStyle}>
            <span>Customer:</span>
            <span>{customerName}</span>
          </Box>
        )}
      </Box>

      {/* ===== QR CODE (invoice number) ===== */}
      {showQRCode && invoiceNumber && (
        <Box sx={{ display: 'flex', justifyContent: 'center', my: '8px' }}>
          <QRImage value={invoiceNumber} size={getQRSize(paperSize)} />
        </Box>
      )}

      {/* ===== MAIN MESSAGE (above items) ===== */}
      {mainMessage?.trim() && (
        <Box sx={{ textAlign: 'center', mb: '6px', fontStyle: 'italic' }}>
          {mainMessage}
        </Box>
      )}

      {/* ===== ITEMS ===== */}
      <Box sx={dividerStyle_} />
      <Box sx={{ mb: '6px' }}>
        {items.map((item, idx) => {
          const itemTotal = item.totalPrice != null
            ? Number(item.totalPrice)
            : Number(item.unitPrice || 0) * Number(item.quantity || 0);
          const isRefunded = (item.quantityRefunded || 0) > 0;

          return (
            <Box key={idx} sx={{ mb: '4px' }}>
              <Box sx={{ ...rowStyle, alignItems: 'flex-start' }}>
                <span style={{ flex: 1, minWidth: 0, marginRight: '8px', overflowWrap: 'anywhere', whiteSpace: 'normal' }}>
                  {item.productName}
                </span>
                <span style={{ flexShrink: 0, fontWeight: 600, whiteSpace: 'nowrap' }}>
                  {formatCurrency(itemTotal)}
                </span>
              </Box>
              <Box sx={{ pl: '8px', color: '#555' }}>
                {item.quantity} {item.unit || ''} x {formatAmountPlain(item.unitPrice || 0)}
              </Box>
              {isRefunded && (
                <Box sx={{ pl: '8px', color: '#b45309' }}>
                  Refunded: {item.quantityRefunded}
                </Box>
              )}
            </Box>
          );
        })}
      </Box>

      {/* ===== SUBTOTALS ===== */}
      <Box sx={dividerStyle_} />
      {Number(subTotal) > 0 && Number(subTotal) !== Number(totalAmount) && (
        <Box sx={rowStyle}>
          <span>Subtotal:</span>
          <span>{formatCurrency(subTotal)}</span>
        </Box>
      )}
      {showTax !== false && Number(taxAmount) > 0 && (
        <Box sx={rowStyle}>
          <span>Tax:</span>
          <span>{formatCurrency(taxAmount)}</span>
        </Box>
      )}
      {showDiscount !== false && Number(discountAmount) > 0 && (
        <Box sx={rowStyle}>
          <span>Discount:</span>
          <span>-{formatCurrency(discountAmount)}</span>
        </Box>
      )}

      {/* ===== TOTAL ===== */}
      <Box sx={{ ...dividerStyle_, py: '4px' }}>
        <Box sx={{ ...rowStyle, fontWeight: 700, fontSize: `calc(${fontSizeVal} + 0.05rem)` }}>
          <span>TOTAL:</span>
          <span>{formatCurrency(totalAmount)}</span>
        </Box>
        <Box sx={rowStyle}>
          <span>Paid:</span>
          <span>{formatCurrency(amountPaid)}</span>
        </Box>
        {change > 0 && (
          <Box sx={rowStyle}>
            <span>Change:</span>
            <span>{formatCurrency(change)}</span>
          </Box>
        )}
      </Box>

      {/* ===== CREDIT INFO ===== */}
      {showCreditInfo_ && (
        <Box sx={{ ...dividerStyle_, py: '4px' }}>
          <Box sx={{ textAlign: 'center', fontWeight: 700, mb: '4px' }}>*** CREDIT SALE ***</Box>
          <Box sx={rowStyle}>
            <span>Balance Due:</span>
            <span>{formatCurrency(balanceDue)}</span>
          </Box>
          {dueDateLabel && (
            <Box sx={rowStyle}>
              <span>Due Date:</span>
              <span>{dueDateLabel}</span>
            </Box>
          )}
        </Box>
      )}

      {/* ===== FOOTER ===== */}
      {footerText?.trim() && (
        <Box sx={{ textAlign: 'center', mt: '10px', fontWeight: 600 }}>
          {footerText}
        </Box>
      )}

      {/* ===== QR (under the bottom text, when enabled + provided) ===== */}
      {showQRCode && qrDataUrl && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: '8px' }}>
          <Box
            component="img"
            src={qrDataUrl}
            alt="QR"
            sx={{ width: 96, height: 96, imageRendering: 'pixelated' }}
          />
        </Box>
      )}
    </Box>
  );
};

export default ReceiptDocument;

/**
 * generatePrintHtml(receipt, shopInfo, customization, logoDataUrl)
 *
 * Generates a self-contained HTML string that exactly matches the
 * ReceiptDocument visual layout, suitable for silent/direct printing.
 *
 * @param {object} receipt         - Sale/receipt data
 * @param {object} shopInfo        - Shop info including name, address, phone
 * @param {object} customization   - All receipt customization settings
 * @param {string|null} logoDataUrl - Base64 data URL of logo image (or null)
 * @param {string|null} qrDataUrl   - Base64 data URL of invoice QR image (or null)
 * @returns {string} Complete HTML document
 */
export function generatePrintHtml(receipt = {}, shopInfo = {}, customization = {}, logoDataUrl = null, qrDataUrl = null) {
  const {
    logoSize = 80,
    showLogo = true,
    showAddress = true,
    showPhone = true,
    headerAlign = 'center',
    fontSize = 'normal',
    dividerStyle = 'dashed',
    boldShopName = true,
    showQRCode = false,
    showShopName = true,
    showCreditInfo = true,
    showTax = true,
    showDiscount = true,
    headerText = '',
    mainMessage = 'Please keep this receipt for your records.',
    footerText = 'Thank you for your business!',
    paperSize = '58',
    timeFormat = '12',
  } = customization;

  const paperWidthMm = Math.max(40, parseInt(String(paperSize).replace(/\D/g, ''), 10) || 58);
  const fontSizePx   = FONT_SIZE_PX_MAP[fontSize] || FONT_SIZE_PX_MAP.normal;
  const divBorder    = getDividerBorder(dividerStyle);
  const divHtml      = divBorder !== 'none'
    ? `<div style="border-top:${divBorder};margin:6px 0;"></div>`
    : `<div style="margin:6px 0;"></div>`;

  const shopName  = shopInfo.shopName || '';
  const address   = shopInfo.address  || '';
  const phone     = shopInfo.phone    || '';

  const items          = receipt.items || [];
  const subTotal       = receipt.subTotal ?? receipt.totalAmount ?? 0;
  const taxAmount      = receipt.taxAmount ?? 0;
  const discountAmount = receipt.discountAmount ?? 0;
  const totalAmount    = receipt.totalAmount ?? 0;
  const amountPaid     = receipt.amountPaid ?? 0;
  const change         = amountPaid - totalAmount;
  const customerName   = receipt.customerName;

  const isCredit        = (receipt.saleType || receipt.paymentStatus) === 'CREDIT';
  const showCreditInfo_ = Boolean(showCreditInfo) && isCredit;
  const balanceDue      = receipt.balanceDue != null
    ? receipt.balanceDue
    : (Number(receipt.totalAmount ?? 0) - Number(receipt.amountPaid ?? 0));
  const dueDateLabel    = receipt.dueDate ? String(receipt.dueDate).slice(0, 10) : '';

  const alignStyle = `text-align:${headerAlign};`;

  let logoHtml = '';
  if (showLogo && logoDataUrl) {
    const flexJustify = headerAlign === 'left' ? 'flex-start' : headerAlign === 'right' ? 'flex-end' : 'center';
    logoHtml = `
      <div style="display:flex;justify-content:${flexJustify};margin-bottom:6px;">
        <img src="${logoDataUrl}" alt="Logo" style="width:${logoSize}px;height:${logoSize}px;object-fit:contain;border-radius:4px;" />
      </div>`;
  }

  let headerHtml = `
    <div style="${alignStyle}margin-bottom:8px;">
      ${logoHtml}
      ${showShopName ? `<div style="font-weight:${boldShopName ? 700 : 400};font-size:calc(${fontSizePx} + 2px);letter-spacing:0.02em;margin-bottom:2px;">${escHtml(shopName)}</div>` : ''}
      ${showAddress && address ? `<div>${escHtml(address)}</div>` : ''}
      ${showPhone && phone ? `<div>${escHtml(phone)}</div>` : ''}
    </div>`;

  let bannerHtml = headerText?.trim()
    ? `<div style="${divBorder !== 'none' ? `border-top:${divBorder};border-bottom:${divBorder};` : ''}text-align:center;font-weight:700;padding:4px 0;margin:6px 0;">${escHtml(headerText)}</div>`
    : divHtml;

  let metaHtml = `
    <div style="margin-bottom:8px;">
      <div style="display:flex;justify-content:space-between;"><span>Invoice No:</span><span>${escHtml(receipt.invoiceNumber || '')}</span></div>
      <div style="display:flex;justify-content:space-between;"><span>Date:</span><span>${escHtml(formatReceiptDateTime(receipt.saleDate, timeFormat))}</span></div>
      ${customerName && customerName !== 'Walk-in' ? `<div style="display:flex;justify-content:space-between;"><span>Customer:</span><span>${escHtml(customerName)}</span></div>` : ''}
    </div>`;

  let messagHtml = mainMessage?.trim()
    ? `<div style="text-align:center;margin-bottom:6px;font-style:italic;">${escHtml(mainMessage)}</div>`
    : '';

  const qrSizePx = Math.min(120, Math.round(paperWidthMm * 1.6));
  let qrHtml = (showQRCode && qrDataUrl)
    ? `<div style="display:flex;justify-content:center;margin:8px 0;"><img src="${qrDataUrl}" alt="QR" width="${qrSizePx}" height="${qrSizePx}" style="image-rendering:pixelated;" /></div>`
    : '';
  // QR renders under the footer text (moved to the very end of the template)

  let itemsHtml = items.map(item => {
    const itemTotal = item.totalPrice != null
      ? Number(item.totalPrice)
      : Number(item.unitPrice || 0) * Number(item.quantity || 0);
    const refundedHtml = (item.quantityRefunded || 0) > 0
      ? `<div style="padding-left:8px;color:#b45309;">Refunded: ${item.quantityRefunded}</div>`
      : '';
    return `
      <div style="margin-bottom:4px;">
        <div style="display:flex;justify-content:space-between;align-items:flex-start;">
          <span style="flex:1;min-width:0;margin-right:8px;overflow-wrap:anywhere;white-space:normal;">${escHtml(item.productName)}</span>
          <span style="flex-shrink:0;font-weight:600;white-space:nowrap;">${escHtml(formatCurrency(itemTotal))}</span>
        </div>
        <div style="padding-left:8px;color:#555;">${item.quantity} ${escHtml(item.unit || '')} x ${escHtml(formatAmountPlain(item.unitPrice || 0))}</div>
        ${refundedHtml}
      </div>`;
  }).join('');

  const showSubLine = Number(subTotal) > 0 && Number(subTotal) !== Number(totalAmount);
  let subtotalsHtml = `
    ${showSubLine ? `<div style="display:flex;justify-content:space-between;"><span>Subtotal:</span><span>${escHtml(formatCurrency(subTotal))}</span></div>` : ''}
    ${showTax !== false && Number(taxAmount) > 0 ? `<div style="display:flex;justify-content:space-between;"><span>Tax:</span><span>${escHtml(formatCurrency(taxAmount))}</span></div>` : ''}
    ${showDiscount !== false && Number(discountAmount) > 0 ? `<div style="display:flex;justify-content:space-between;"><span>Discount:</span><span>-${escHtml(formatCurrency(discountAmount))}</span></div>` : ''}`;

  let totalsHtml = `
    <div style="${divBorder !== 'none' ? `border-top:${divBorder};border-bottom:${divBorder};` : ''}padding:4px 0;margin:6px 0;">
      <div style="display:flex;justify-content:space-between;font-weight:700;"><span>TOTAL:</span><span>${escHtml(formatCurrency(totalAmount))}</span></div>
      <div style="display:flex;justify-content:space-between;"><span>Paid:</span><span>${escHtml(formatCurrency(amountPaid))}</span></div>
      ${change > 0 ? `<div style="display:flex;justify-content:space-between;"><span>Change:</span><span>${escHtml(formatCurrency(change))}</span></div>` : ''}
    </div>`;

  let creditHtml = showCreditInfo_
    ? `<div style="${divBorder !== 'none' ? `border-top:${divBorder};border-bottom:${divBorder};` : ''}padding:4px 0;margin:6px 0;">
        <div style="text-align:center;font-weight:700;margin-bottom:4px;">*** CREDIT SALE ***</div>
        <div style="display:flex;justify-content:space-between;"><span>Balance Due:</span><span>${escHtml(formatCurrency(balanceDue))}</span></div>
        ${dueDateLabel ? `<div style="display:flex;justify-content:space-between;"><span>Due Date:</span><span>${escHtml(dueDateLabel)}</span></div>` : ''}
      </div>`
    : '';

  let footerHtml = footerText?.trim()
    ? `<div style="text-align:center;font-weight:600;margin-top:10px;">${escHtml(footerText)}</div>`
    : '';

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    @page {
      size: ${paperWidthMm}mm auto;
      margin: 0;
    }
    body {
      width: ${paperWidthMm - 6}mm;
      margin: 0 auto;
      padding: 3mm 3mm 8mm 3mm;
      font-family: 'Courier New', Courier, monospace;
      font-size: ${fontSizePx};
      color: #111;
      background: #fff;
      line-height: 1.5;
    }
    * { box-sizing: border-box; }
  </style>
</head>
<body>
  ${headerHtml}
  ${bannerHtml}
  ${metaHtml}
  ${messagHtml}
  ${divHtml}
  <div style="margin-bottom:6px;">${itemsHtml}</div>
  ${divHtml}
  ${subtotalsHtml}
  ${totalsHtml}
  ${creditHtml}
  ${footerHtml}
  ${qrHtml}
</body>
</html>`;
}

/** HTML-escape a string for safe injection into HTML */
function escHtml(str) {
  if (str == null) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * Generates a QR code data URL for direct/silent printing.
 * Returns null if the value is empty or generation fails.
 */
export async function generateQRDataUrl(text) {
  if (!text) return null;
  try {
    return await QRCode.toDataURL(String(text), { width: 400, margin: 1, color: { dark: '#111', light: '#ffffff' } });
  } catch {
    return null;
  }
}
