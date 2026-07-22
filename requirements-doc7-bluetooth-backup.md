# BMS-v1-4 — Requirements Document 7 of 7
## Bluetooth Receipt Printing & Data Backup
Covers: Bluetooth thermal printer connection, CSV/Excel export, Google Drive backup with scheduling.

**Read the honesty notes in each section before committing to scope** — both of these features are more involved than most of what's in Documents 5 and 6, and one of them (Google Drive automated scheduling) genuinely doesn't fit a "keep Model 1 simple and stable" goal without a real trade-off. I'll lay out a simpler version of each that still solves your actual problem.

---

## 1. Bluetooth Receipt Printing

### 1.1 Honest scope note
This is a browser-and-hardware feature, not a typical "add an endpoint, add a page" feature — it depends on the **Web Bluetooth API**, which has real constraints worth knowing before you build on it:
- **Only works in Chrome/Edge on Android and desktop.** It does **not** work in Safari on iOS at all (Apple hasn't implemented Web Bluetooth in WebKit) — if any of your cashiers use iPhones/iPads, this feature will simply not be available to them, full stop, no workaround. Confirm what hardware your actual users run before investing here.
- Requires a user gesture (a button click) to initiate pairing — can't auto-connect silently in the background.
- Most small thermal receipt printers (58mm/80mm) that support Bluetooth use the **ESC/POS** command protocol, not "print a PDF" — you'll be sending raw byte commands, not an image/PDF file, for good print quality and speed.

### 1.2 Design decision — client-side only, no backend changes needed

The receipt data you need already exists (`GET /api/receipts/invoice/{invoiceNumber}` already returns the data as JSON, per the existing receipt feature). This feature is entirely about taking that data and pushing it to a Bluetooth device from the browser — no new backend work required.

### 1.3 Frontend implementation

Add the dependency: `npm install esc-pos-encoder` (formats text/formatting into the raw ESC/POS byte commands thermal printers expect, without you hand-writing byte sequences).

**New utility: `frontend/src/utils/bluetoothPrinter.js`:**
```js
import EscPosEncoder from 'esc-pos-encoder';

let connectedDevice = null;
let printCharacteristic = null;

// Common service/characteristic UUIDs for ESC/POS thermal printers —
// confirm against your actual printer model's documentation, this varies by manufacturer
const PRINTER_SERVICE_UUID = '000018f0-0000-1000-8000-00805f9b34fb';
const PRINTER_CHARACTERISTIC_UUID = '00002af1-0000-1000-8000-00805f9b34fb';

export async function connectPrinter() {
  const device = await navigator.bluetooth.requestDevice({
    filters: [{ services: [PRINTER_SERVICE_UUID] }],
  });
  const server = await device.gatt.connect();
  const service = await server.getPrimaryService(PRINTER_SERVICE_UUID);
  printCharacteristic = await service.getCharacteristic(PRINTER_CHARACTERISTIC_UUID);
  connectedDevice = device;
  return device.name;
}

export function isPrinterConnected() {
  return connectedDevice?.gatt?.connected ?? false;
}

export async function printReceipt(receiptData, shopInfo) {
  if (!printCharacteristic) throw new Error('No printer connected');

  const encoder = new EscPosEncoder();
  let result = encoder
    .initialize()
    .align('center')
    .bold(true)
    .line(shopInfo.shopName)
    .bold(false)
    .line(shopInfo.address || '')
    .line(shopInfo.phone || '')
    .line('--------------------------------')
    .align('left')
    .line(`Invoice: ${receiptData.invoiceNumber}`)
    .line(`Date: ${new Date(receiptData.saleDate).toLocaleString()}`)
    .line(`Customer: ${receiptData.customerName || 'Walk-in'}`)
    .line('--------------------------------');

  receiptData.items.forEach((item) => {
    result = result.line(`${item.productName} x${item.quantity}`)
                    .align('right')
                    .line(`$${item.totalPrice.toFixed(2)}`)
                    .align('left');
  });

  result = result
    .line('--------------------------------')
    .align('right')
    .bold(true)
    .line(`Total: $${receiptData.totalAmount.toFixed(2)}`)
    .bold(false)
    .line(`Paid: $${receiptData.amountPaid.toFixed(2)}`)
    .line(`Change: $${(receiptData.amountPaid - receiptData.totalAmount).toFixed(2)}`)
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
```

**`ReceiptPreview.jsx`** — add a "Print via Bluetooth" button alongside the existing Print/Download buttons:
```jsx
const [printerConnected, setPrinterConnected] = useState(false);

const handleBluetoothPrint = async () => {
  try {
    if (!printerConnected) {
      const deviceName = await connectPrinter();
      setPrinterConnected(true);
      notifySuccess(`Connected to ${deviceName}`);
    }
    await printReceipt(receiptData, shopInfo);
    notifySuccess('Printed successfully');
  } catch (err) {
    notifyError('Bluetooth printing failed: ' + err.message);
  }
};
```

### 1.4 Acceptance criteria
- On a supported device/browser, clicking "Print via Bluetooth" prompts a device picker, connects, and produces a physically correct printout with shop branding, items, and totals.
- On an unsupported browser (Safari/iOS), the button is either hidden or shows a clear "Not supported on this device" message — never a confusing silent failure.
- Reconnection isn't required for every single print during one shift — the connection should persist across multiple receipts until the page is closed/refreshed.

---

## 2. Data Backup

### 2.1 Splitting this into two tiers — do the first one now, treat the second as optional/later

You asked for two things that are quite different in complexity:
1. Export all data as CSV/Excel (straightforward, do this).
2. Automatically connect to Google Drive and back up on a schedule, daily/weekly/yearly (a genuinely significant undertaking — see below).

### 2.2 Tier 1 — Full CSV/Excel Export (do this now)

This extends the CSV export work already scoped for Products in earlier work — generalize it to a proper "Backup" page covering everything.

**Backend — one endpoint, multiple sheets, using Excel rather than plain CSV** for a full backup (a single `.xlsx` with one sheet per table is much more usable than a folder of separate `.csv` files for this purpose):

```java
@GetMapping("/api/backup/export")
@PreAuthorize("hasRole('ADMIN')")
public void exportFullBackup(HttpServletResponse response) throws IOException {
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename=\"bms-backup-" + LocalDate.now() + ".xlsx\"");
    backupService.exportFullBackup(response.getOutputStream());
}
```
Add `org.apache.poi:poi-ooxml` to `pom.xml` (the standard Java library for writing real `.xlsx` files). `BackupService.exportFullBackup()` writes one sheet per major table — Products, Categories, Customers, Suppliers, Sales, Sale Items, Purchases, Purchase Items, Expenses — each a straightforward "every column, every row" dump. Don't try to be clever about formatting here; the goal is a complete, restorable record, not a pretty report.

**Frontend** — a new **Settings → Backup** section (Admin only): a single "Download Full Backup" button. Simplest possible UI for this tier — it's an emergency-recovery feature, not something used daily, so it doesn't need to be elaborate.

### 2.3 Tier 2 — Google Drive integration — the honest version

**Before building this, be clear-eyed about what it actually involves:**
- Registering your application with Google Cloud Console and going through OAuth 2.0 consent — for an app that isn't in a Google-verified state, this can show scary "unverified app" warnings to users, or require a formal verification review from Google if you want that gone (a real process, not instant).
- Your backend needs to securely store and refresh OAuth tokens per shop/installation — a genuine security-sensitive piece of infrastructure, not a checkbox.
- "Automatically back up daily/weekly/yearly" needs a real scheduled job (Spring's `@Scheduled`, or an external cron), which needs to run **continuously on a server that's always on** — this doesn't work if "the app" is just something running on a shop's local PC that gets turned off every night, which is a very plausible deployment for exactly the SME customers you're targeting. Worth confirming your actual deployment model (always-on server vs. local machine) before committing to this, since it changes whether scheduled cloud backup is even achievable.

**Recommendation:** build Tier 1 (manual export) now, since it solves the actual underlying need — "I don't want to lose my data" — with a fraction of the effort and zero external dependencies. Treat Google Drive auto-backup as a genuine v2 feature, and in the meantime, a much simpler stopgap: **let the Admin manually upload the exported `.xlsx` file to their own Google Drive** (or Dropbox, or email it to themselves) — this costs you zero additional engineering and already achieves "my data is safely off-site," just with one manual step instead of full automation.

### 2.4 If you decide to proceed with Google Drive anyway — minimal viable version

If, after the above, you still want it, here's the smallest version that's honestly worth building rather than the full automated-scheduling vision:
- **Manual "Backup to Drive Now" button** (not automatic scheduling) — Admin clicks it, goes through Google's OAuth consent flow once (using Google's official `google-api-services-drive` Java client library, or a simpler pre-built OAuth flow on the frontend using Google Identity Services), the exported `.xlsx` from Tier 1 gets uploaded to a dedicated folder in their Drive.
- Store the refresh token encrypted in your database, tied to the shop (not per-user), so re-authorization isn't needed every single time.
- **Automatic scheduling is the part I'd defer** — even the manual-trigger version above is a meaningfully sized feature on its own; don't take on both the OAuth integration *and* the always-on scheduler in the same pass.

### 2.5 Acceptance criteria (Tier 1, the part to actually build now)
- Clicking "Download Full Backup" produces a single `.xlsx` file with one sheet per major table, opens correctly in Excel/Google Sheets, and contains a complete, faithful snapshot of the current data.
- The export doesn't lock up the app or time out for a shop with a realistic amount of data (test with however much sample data roughly matches your target shop size).
- (If you proceed with the minimal Drive version from §2.4) a manual "Backup to Drive Now" button successfully uploads the same file to the connected Drive account, with clear success/failure feedback.

---

## Suggested order

1. §2.2 (CSV/Excel export) — do this first, it's genuinely simple, high-value, and gives you a real safety net immediately.
2. §1 (Bluetooth printing) — confirm your actual hardware/browser situation first (the iOS limitation is a hard blocker, not a bug to fix), then build if it fits your real users.
3. §2.4 (manual Google Drive backup) — only pursue this if §2.2 alone doesn't feel sufficient; treat full automatic scheduling as an explicit later phase, not part of Model 1.
