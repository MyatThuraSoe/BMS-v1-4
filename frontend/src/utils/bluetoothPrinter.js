import EscPosEncoder from 'esc-pos-encoder';

let connectedDevice = null;
let printCharacteristic = null;

// Common service/characteristic UUIDs for ESC/POS thermal printers —
// confirm against your actual printer model's documentation, this varies by manufacturer
const PRINTER_SERVICE_UUID = '000018f0-0000-1000-8000-00805f9b34fb';
const PRINTER_CHARACTERISTIC_UUID = '00002af1-0000-1000-8000-00805f9b34fb';

export function isBluetoothSupported() {
  return typeof navigator !== 'undefined' && !!navigator.bluetooth;
}

export async function connectPrinter() {
  const device = await navigator.bluetooth.requestDevice({
    filters: [{ services: [PRINTER_SERVICE_UUID] }],
  });
  const server = await device.gatt.connect();
  const service = await server.getPrimaryService(PRINTER_SERVICE_UUID);
  printCharacteristic = await service.getCharacteristic(PRINTER_CHARACTERISTIC_UUID);
  connectedDevice = device;

  device.addEventListener('gattserverdisconnected', () => {
    connectedDevice = null;
    printCharacteristic = null;
  });

  return device.name;
}

export function isPrinterConnected() {
  return connectedDevice?.gatt?.connected ?? false;
}

function lineTotal(item) {
  if (item.totalPrice != null) return Number(item.totalPrice);
  if (item.subtotal != null) return Number(item.subtotal);
  return Number(item.unitPrice || 0) * Number(item.quantity || 0);
}

export async function printReceipt(receiptData, shopInfo) {
  if (!printCharacteristic) throw new Error('No printer connected');

  const encoder = new EscPosEncoder();
  let result = encoder
    .initialize()
    .align('center')
    .bold(true)
    .line(shopInfo?.shopName || 'Shop')
    .bold(false)
    .line(shopInfo?.address || '')
    .line(shopInfo?.phone || '')
    .line('--------------------------------')
    .align('left')
    .line(`Invoice: ${receiptData.invoiceNumber}`)
    .line(`Date: ${new Date(receiptData.saleDate).toLocaleString()}`)
    .line(`Customer: ${receiptData.customerName || 'Walk-in'}`)
    .line('--------------------------------');

  (receiptData.items || []).forEach((item) => {
    const total = lineTotal(item);
    result = result
      .line(`${item.productName} x${item.quantity}`)
      .align('right')
      .line(`$${total.toFixed(2)}`)
      .align('left');
  });

  const totalAmount = Number(receiptData.totalAmount || 0);
  const amountPaid = Number(receiptData.amountPaid || 0);

  result = result
    .line('--------------------------------')
    .align('right')
    .bold(true)
    .line(`Total: $${totalAmount.toFixed(2)}`)
    .bold(false)
    .line(`Paid: $${amountPaid.toFixed(2)}`)
    .line(`Change: $${(amountPaid - totalAmount).toFixed(2)}`)
    .align('center')
    .newline()
    .line('Thank you!')
    .newline().newline().newline()
    .cut();

  const bytes = result.encode();

  // BLE has a small per-write size limit — send in chunks
  const chunkSize = 100;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    await printCharacteristic.writeValue(bytes.slice(i, i + chunkSize));
  }
}
