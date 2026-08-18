import { notifySuccess, notifyError } from './notify';
import { formatCurrency, formatReceiptDateTime } from './helpers'; 

// Receipt paper width in characters (12 dots/char @ 203dpi ≈ 1.47mm/char).
// Derived from the paper width in mm set in Shop Info.
const CHARS_PER_MM = 1 / 1.47;

function parsePaperWidthMm(paperSize) {
  if (paperSize == null || paperSize === '') return 58;
  const digits = String(paperSize).replace(/\D/g, '');
  const mm = parseInt(digits, 10);
  if (!Number.isFinite(mm) || mm < 20 || mm > 200) return 58;
  return mm;
}

export function getReceiptLineWidth(paperSize) {
  const mm = parsePaperWidthMm(paperSize);
  return Math.max(16, Math.round(mm * CHARS_PER_MM));
}

export function getReceiptPreviewWidth(paperSize) {
  const mm = parsePaperWidthMm(paperSize);
  return Math.round(mm * (400 / 58));
} 

// Ensure we reference the global qz object correctly if loaded via <script> tag
const qz = window.qz;

export function isQZSupported() {
  return typeof qz !== 'undefined' && qz.websocket !== undefined;
}

export async function connectQZ() {
  if (!isQZSupported()) return false;

  // ✅ FIX 3: Always check the ACTUAL websocket status, not a manual boolean
  if (qz.websocket.isActive()) {
    return true;
  }

  try {
    // ✅ FIX 2: Corrected Promise Type setup. 
    // Standard QZ expects a function that returns a valid Promise object.
    if (qz.api) {
      qz.api.setPromiseType((resolve, reject) => new Promise(resolve, reject));
    }
    
    await qz.websocket.connect();
    return true;
  } catch (err) {
    console.error("QZ Tray connection failed:", err);
    // Note: Removed notifyError here to prevent toast spam on page load if QZ is just closed
    return false;
  }
}

export async function getAvailablePrinters() {
  if (!isQZSupported()) return [];

  try {
    // ✅ FIX 1: Check the connection result BEFORE calling find()
    const isConnected = await connectQZ();
    if (!isConnected) {
      console.warn("Cannot fetch printers: QZ Tray is not connected.");
      return []; // Exit gracefully instead of crashing
    }

    const printers = await qz.printers.find();
    return printers || [];
  } catch (err) {
    console.error("Failed to find printers:", err);
    return [];
  }
}

export async function printReceiptViaQZ(receiptData, shopInfo, printerName = null, timeFormat = '12', paperSize = '58') {
  if (!isQZSupported()) {
    notifyError("QZ Tray is not loaded.");
    return;
  }

  try {
    const isConnected = await connectQZ();
    if (!isConnected) {
      throw new Error("QZ Tray is not running. Please open the desktop app and try again.");
    }

    // If no printer specified, prefer the system default printer
    let targetPrinter = printerName;
    if (!targetPrinter) {
      try {
        targetPrinter = await qz.printers.getDefault();
      } catch (err) {
        targetPrinter = null;
      }
    }
    if (!targetPrinter) {
      const printers = await qz.printers.find();
      targetPrinter = printers[0];
    }
    
    if (!targetPrinter) {
      throw new Error("No printers found. Please install a printer and ensure QZ Tray is running.");
    }

    // Create config
    const config = qz.configs.create(targetPrinter, { encoding: 'UTF-8' });
    
    // Build ESC/POS commands as an array of strings (normalized receipt layout)
    let commands = [];
    
    // Initialize printer
    commands.push('\x1B\x40'); 
    
    // Center align, bold
    commands.push('\x1B\x61\x01'); // Center align
    commands.push('\x1B\x45\x01'); // Bold on
    commands.push((shopInfo?.shopName || 'My Shop') + '\n');
    commands.push('\x1B\x45\x00'); // Bold off
    
    if (shopInfo?.address) {
      commands.push((shopInfo.address || '') + '\n');
    }
    if (shopInfo?.phone) {
      commands.push((shopInfo.phone || '') + '\n');
    }
    
    const lineWidth = getReceiptLineWidth(paperSize);
    commands.push('-'.repeat(lineWidth) + '\n');
    
    // Left align
    commands.push('\x1B\x61\x00');
    commands.push('Invoice No: ' + receiptData.invoiceNumber + '\n');
    commands.push('Date: ' + formatReceiptDateTime(receiptData.saleDate, timeFormat) + '\n');
    
    if (receiptData.customerName && receiptData.customerName !== 'Walk-in') {
      commands.push('Customer: ' + receiptData.customerName + '\n');
    }
    commands.push('-'.repeat(lineWidth) + '\n');

    // Items (4-column: Item, Qty, Price, Amount)
    const qtyW = 4;
    const priceW = Math.max(7, formatCurrency(9999999.99).length);
    const amountW = Math.max(9, formatCurrency(9999999.99).length);
    const gap = 1;
    const nameW = Math.max(6, lineWidth - qtyW - priceW - amountW - gap * 3);

    const padRight = (s, w) => { s = String(s); return s.length >= w ? s : s + ' '.repeat(w - s.length); };
    const padLeft = (s, w) => { s = String(s); return s.length >= w ? s : ' '.repeat(w - s.length) + s; };
    const truncate = (s, w) => { s = String(s); if (s.length <= w) return s; return w <= 1 ? s.slice(0, w) : s.slice(0, w - 1) + '.'; };
    const fourCol = (n, q, p, a) =>
      padRight(truncate(n, nameW), nameW) + ' '.repeat(gap)
      + padLeft(q, qtyW) + ' '.repeat(gap)
      + padLeft(p, priceW) + ' '.repeat(gap)
      + padLeft(a, amountW);

    commands.push(fourCol('Item', 'Qty', 'Price', 'Amount') + '\n');
    (receiptData.items || []).forEach((item) => {
      const total = item.totalPrice != null ? Number(item.totalPrice) : (Number(item.unitPrice || 0) * Number(item.quantity || 0));
      commands.push(fourCol(item.productName || '', item.quantity, formatCurrency(item.unitPrice || 0), formatCurrency(total)) + '\n');
    });

    const totalAmount = Number(receiptData.totalAmount || 0);
    const amountPaid = Number(receiptData.amountPaid || 0);
    const change = amountPaid - totalAmount;

    commands.push('-'.repeat(lineWidth) + '\n');
    commands.push('\x1B\x61\x02'); // Right align
    commands.push('\x1B\x45\x01'); // Bold on
    commands.push(`TOTAL: ${formatCurrency(totalAmount)}\n`);
    commands.push('\x1B\x45\x00'); // Bold off
    commands.push(`Paid: ${formatCurrency(amountPaid)}\n`);
    commands.push(`Change: ${formatCurrency(change)}\n`);
    commands.push('\x1B\x61\x00'); // Left align
    commands.push('\n\n');
    commands.push('Thank you!\n\n\n');
    
    // Cut paper (Full cut)
    commands.push('\x1D\x56\x00'); 

    // Send to printer
    await qz.print(config, commands);
    notifySuccess("Printed successfully via QZ Tray!");
  } catch (err) {
    console.error("QZ Print failed:", err);
    notifyError("Printing failed: " + err.message);
    throw err;
  }
}