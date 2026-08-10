import { notifySuccess, notifyError } from './notify'; 

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

export async function printReceiptViaQZ(receiptData, shopInfo, printerName = null) {
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

    // Create config (Use 'CP437' or 'GBK' instead of 'UTF-8' if special characters print weirdly)
    const config = qz.configs.create(targetPrinter, { encoding: 'UTF-8' });
    
    // Build ESC/POS commands as an array of strings
    let commands = [];
    
    // Initialize printer
    commands.push('\x1B\x40'); 
    
    // Center align, bold
    commands.push('\x1B\x61\x01'); // Center align
    commands.push('\x1B\x45\x01'); // Bold on
    commands.push((shopInfo?.shopName || 'My Shop') + '\n');
    commands.push('\x1B\x45\x00'); // Bold off
    commands.push((shopInfo?.address || '') + '\n');
    commands.push((shopInfo?.phone || '') + '\n');
    commands.push('--------------------------------\n');
    
    // Left align
    commands.push('\x1B\x61\x00'); 
    commands.push(`Invoice: ${receiptData.invoiceNumber}\n`);
    commands.push(`Date: ${new Date(receiptData.saleDate).toLocaleString()}\n`);
    if (receiptData.customerName && receiptData.customerName !== 'Walk-in') {
      commands.push(`Customer: ${receiptData.customerName}\n`);
    }
    commands.push('--------------------------------\n');

    // Items (name and total on the SAME line, price right-aligned)
    (receiptData.items || []).forEach((item) => {
      const total = item.totalPrice != null ? Number(item.totalPrice) : (Number(item.unitPrice || 0) * Number(item.quantity || 0));
      const name = `${item.productName} x${item.quantity}`;
      const price = `$${total.toFixed(2)}`;
      const lineWidth = 32; // standard 58mm @ 12 dots/char
      const pad = Math.max(1, lineWidth - name.length - price.length);
      commands.push(`${name}${' '.repeat(pad)}${price}\n`);
    });

    const totalAmount = Number(receiptData.totalAmount || 0);
    const amountPaid = Number(receiptData.amountPaid || 0);
    const change = amountPaid - totalAmount;

    commands.push('--------------------------------\n');
    commands.push('\x1B\x61\x02'); // Right align
    commands.push('\x1B\x45\x01'); // Bold on
    commands.push(`Total: $${totalAmount.toFixed(2)}\n`);
    commands.push('\x1B\x45\x00'); // Bold off
    commands.push(`Paid: $${amountPaid.toFixed(2)}\n`);
    commands.push(`Change: $${change.toFixed(2)}\n`);
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