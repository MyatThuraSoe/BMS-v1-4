// import EscPosEncoder from 'esc-pos-encoder';

// let connectedDevice = null;
// let printCharacteristic = null;

// // Common service/characteristic UUIDs for ESC/POS thermal printers —
// // confirm against your actual printer model's documentation, this varies by manufacturer
// const PRINTER_SERVICE_UUID = '000018f0-0000-1000-8000-00805f9b34fb';
// const PRINTER_CHARACTERISTIC_UUID = '00002af1-0000-1000-8000-00805f9b34fb';

// export function isBluetoothSupported() {
//   return typeof navigator !== 'undefined' && !!navigator.bluetooth;
// }

// export async function connectPrinter() {
//   const device = await navigator.bluetooth.requestDevice({
//     filters: [{ services: [PRINTER_SERVICE_UUID] }],
//   });
//   const server = await device.gatt.connect();
//   const service = await server.getPrimaryService(PRINTER_SERVICE_UUID);
//   printCharacteristic = await service.getCharacteristic(PRINTER_CHARACTERISTIC_UUID);
//   connectedDevice = device;

//   device.addEventListener('gattserverdisconnected', () => {
//     connectedDevice = null;
//     printCharacteristic = null;
//   });

//   return device.name;
// }

// export function isPrinterConnected() {
//   return connectedDevice?.gatt?.connected ?? false;
// }

// function lineTotal(item) {
//   if (item.totalPrice != null) return Number(item.totalPrice);
//   if (item.subtotal != null) return Number(item.subtotal);
//   return Number(item.unitPrice || 0) * Number(item.quantity || 0);
// }

// export async function printReceipt(receiptData, shopInfo) {
//   if (!printCharacteristic) throw new Error('No printer connected');

//   const encoder = new EscPosEncoder();
//   let result = encoder
//     .initialize()
//     .align('center')
//     .bold(true)
//     .line(shopInfo?.shopName || 'Shop')
//     .bold(false)
//     .line(shopInfo?.address || '')
//     .line(shopInfo?.phone || '')
//     .line('--------------------------------')
//     .align('left')
//     .line(`Invoice: ${receiptData.invoiceNumber}`)
//     .line(`Date: ${new Date(receiptData.saleDate).toLocaleString()}`)
//     .line(`Customer: ${receiptData.customerName || 'Walk-in'}`)
//     .line('--------------------------------');

//   (receiptData.items || []).forEach((item) => {
//     const total = lineTotal(item);
//     result = result
//       .line(`${item.productName} x${item.quantity}`)
//       .align('right')
//       .line(`$${total.toFixed(2)}`)
//       .align('left');
//   });

//   const totalAmount = Number(receiptData.totalAmount || 0);
//   const amountPaid = Number(receiptData.amountPaid || 0);

//   result = result
//     .line('--------------------------------')
//     .align('right')
//     .bold(true)
//     .line(`Total: $${totalAmount.toFixed(2)}`)
//     .bold(false)
//     .line(`Paid: $${amountPaid.toFixed(2)}`)
//     .line(`Change: $${(amountPaid - totalAmount).toFixed(2)}`)
//     .align('center')
//     .newline()
//     .line('Thank you!')
//     .newline().newline().newline()
//     .cut();

//   const bytes = result.encode();

//   // BLE has a small per-write size limit — send in chunks
//   const chunkSize = 100;
//   for (let i = 0; i < bytes.length; i += chunkSize) {
//     await printCharacteristic.writeValue(bytes.slice(i, i + chunkSize));
//   }
// }

// frontend/src/utils/bluetoothPrinter.js
import { notifySuccess, notifyError } from './notify'; // Adjust this import to match your actual notify utility

let isQzConnected = false;

export async function connectQZ() {
  if (isQzConnected) return true;

  try {
    // Configure QZ Tray
    qz.api.setSha256Type("SHA-512");
    qz.api.setPromiseType(resolve => Promise.resolve(resolve));
    
    await qz.websocket.connect();
    isQzConnected = true;
    return true;
  } catch (err) {
    console.error("QZ Tray connection failed:", err);
    notifyError("QZ Tray is not running or blocked. Please open QZ Tray and trust this site.");
    isQzConnected = false;
    return false;
  }
}

export async function getAvailablePrinters() {
  try {
    await connectQZ();
    const printers = await qz.printers.find();
    return printers;
  } catch (err) {
    console.error("Failed to find printers:", err);
    return [];
  }
}

export async function printReceiptViaQZ(receiptData, shopInfo, printerName = null) {
  try {
    await connectQZ();

    // If no printer specified, try to find a default or use the first available
    const printers = await qz.printers.find();
    const targetPrinter = printerName || printers[0];
    
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
    commands.push(`Customer: ${receiptData.customerName || 'Walk-in'}\n`);
    commands.push('--------------------------------\n');

    // Items
    (receiptData.items || []).forEach((item) => {
      const total = item.totalPrice != null ? Number(item.totalPrice) : (Number(item.unitPrice || 0) * Number(item.quantity || 0));
      commands.push(`${item.productName} x${item.quantity}\n`);
      commands.push('\x1B\x61\x02'); // Right align
      commands.push(`$${total.toFixed(2)}\n`);
      commands.push('\x1B\x61\x00'); // Left align
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

export function isQZSupported() {
  return typeof qz !== 'undefined';
}