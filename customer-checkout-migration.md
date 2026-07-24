# Customer Checkout Flow — Migration to Toggle + Snapshot Design

## What this document does

Your current code has a **complete, working implementation of an earlier design** — quick-add creates a real row in the `customers` table. That was the right call at the time, but we refined the idea further: no new row at all, just a name captured directly on the sale. This document replaces the old implementation with the new one, file by file.

**Read this whole introduction before starting** — this isn't a bug fix, it's a deliberate redesign, and a couple of steps involve *deleting* working code, not just editing it.

---

## The end result, in one sentence per case

| Cashier action | `sale.customer_id` | `sale.customer_display_name` |
|---|---|---|
| Leaves field blank | `null` | `"Walk-in"` |
| Toggle OFF, types "David" | `null` | `"David"` |
| Toggle ON, searches, selects a real customer | `123` | `"David (09332211)"` |

Every place that currently displays a sale's customer already reads a field called `customerName` on the API response (`ReceiptPreview.jsx`, `Sales.jsx`) — **you're keeping that exact field name**, just changing what feeds it. This means the frontend display code needs almost no changes; the work is in how the value gets set at sale-creation time, plus building the new input control.

---

## PART 1 — Backend

### Step 1: Database migration

```sql
ALTER TABLE sales ADD COLUMN customer_display_name VARCHAR(255) NOT NULL DEFAULT 'Walk-in';
```
Run this against your actual database. If you're relying on `bms_schema.sql` for fresh installs, add the column there too, right after the existing `customer_id` column in the `sales` table definition.

### Step 2: `bms-backend/src/main/java/com/bms/entity/Sale.java`

**Find this:**
```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;
```
**Replace with:**
```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "customer_display_name", nullable = false)
    private String customerDisplayName;
```
Add the getter/setter wherever this entity's other getters/setters live:
```java
    public String getCustomerDisplayName() { return customerDisplayName; }
    public void setCustomerDisplayName(String customerDisplayName) { this.customerDisplayName = customerDisplayName; }
```

### Step 3: `bms-backend/src/main/java/com/bms/dto/request/SaleCreateRequest.java`

**Find this:**
```java
    private Long customerId;

    @NotNull(message = "Amount paid is required")
```
**Replace with:**
```java
    private Long customerId;

    private String customerName;

    @NotNull(message = "Amount paid is required")
```
**Find this:**
```java
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
```
**Replace with:**
```java
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
```

### Step 4: `bms-backend/src/main/java/com/bms/service/SaleService.java` — the actual logic

**Find this:**
```java
        // Set customer if provided (walk-in if null)
        if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            sale.setCustomer(customer);
        }
```
**Replace with:**
```java
        // Set customer: registered (by ID), quick-typed name, or Walk-in — exactly one of these three
        if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            sale.setCustomer(customer);
            String contact = customer.getPhone() != null ? customer.getPhone()
                    : (customer.getEmail() != null ? customer.getEmail() : null);
            sale.setCustomerDisplayName(
                customer.getFirstName() + " " + customer.getLastName() +
                (contact != null ? " (" + contact + ")" : "")
            );
        } else if (request.getCustomerName() != null && !request.getCustomerName().isBlank()) {
            sale.setCustomerDisplayName(request.getCustomerName().trim());
        } else {
            sale.setCustomerDisplayName("Walk-in");
        }
```
(Note: falls back to email if there's no phone, since a registered customer could plausibly have one but not the other — either way the parenthetical only appears when there's something real to show.)

**Now find this** (in `convertToResponse`, further down the same file):
```java
        if (sale.getCustomer() != null) {
            response.setCustomerId(sale.getCustomer().getId());
            response.setCustomerName(sale.getCustomer().getFirstName() + " " + sale.getCustomer().getLastName());
        } else {
            response.setCustomerName("Walk-in");
        }
```
**Replace with:**
```java
        if (sale.getCustomer() != null) {
            response.setCustomerId(sale.getCustomer().getId());
        }
        response.setCustomerName(sale.getCustomerDisplayName());
```
This is the key simplification: `customerName` on the response now **always** just echoes the stored snapshot, whatever it is — no more re-deriving it from a live customer lookup. `customerId` is still set when there's a real customer (Document 3's store-credit feature and anything else that needs the real link still works exactly as before) — it's just no longer used to *compute* the display text.

### Step 5: Delete the old quick-add feature entirely

**File: `bms-backend/src/main/java/com/bms/dto/request/QuickAddCustomerRequest.java`** — delete this file.

**File: `bms-backend/src/main/java/com/bms/service/CustomerService.java`** — find and delete the entire `quickAddCustomer(...)` method (the one that generates a `customerCode`, splits the name into first/last, and saves a new `Customer` with `isQuickAdd = true`).

**File: `bms-backend/src/main/java/com/bms/controller/CustomerController.java`** — find and delete the whole endpoint:
```java
    @PostMapping("/quick-add")
    ...
    public ResponseEntity<ApiResponse<CustomerResponse>> quickAddCustomer(@Valid @RequestBody QuickAddCustomerRequest request) {
        ...
    }
```

**File: `bms-backend/src/main/java/com/bms/entity/Customer.java`** — the `isQuickAdd` field can stay or go; since nothing will set it to `true` anymore after this change, it'll just always be `false` going forward. Simplest to leave it as dead weight for now rather than also editing the DB schema for it in this same pass — clean it up separately later if you want, it's harmless either way.

**File: `bms-backend/src/main/java/com/bms/dto/response/CustomerResponse.java`** — same call: `isQuickAdd` can stay, harmless, not worth bundling into this change.

---

## PART 2 — Frontend

### Step 6: `frontend/src/api/services.js`

**Find this** (inside `customerService`):
```js
  quickAdd: async (data) => {
    const response = await apiClient.post('/customers/quick-add', data);
    return response.data;
  },
```
**Delete it entirely** — this endpoint no longer exists on the backend.

### Step 7: `frontend/src/pages/POS.jsx` — remove the old Quick Add UI

**Find this** (near the top, in the icon imports):
```js
  PersonAdd as CustomerIcon,
  PersonAddAlt as QuickAddIcon,
```
**Replace with:**
```js
  PersonAdd as CustomerIcon,
```

**Find and delete these four state declarations:**
```js
  const [quickAddDialogOpen, setQuickAddDialogOpen] = useState(false);
  const [quickAddName, setQuickAddName] = useState('');
  const [quickAddPhone, setQuickAddPhone] = useState('');
```
(Leave any other state declarations near these alone — only remove these three lines.)

**Find and delete the entire `quickAddMutation` block:**
```js
  const quickAddMutation = useMutation({
    mutationFn: (data) => customerService.quickAdd(data),
    onSuccess: (response) => {
      const newCustomer = response.data;
      setSelectedCustomer(newCustomer);
      setQuickAddDialogOpen(false);
      setQuickAddName('');
      setQuickAddPhone('');
      queryClient.invalidateQueries(['customers-pos']);
      notifySuccess(`Customer "${newCustomer.firstName} ${newCustomer.lastName}" added`);
    },
    onError: (err) => {
      notifyError(err.friendlyMessage || 'Failed to add customer');
    },
  });

  const handleQuickAdd = () => {
    if (!quickAddName.trim()) return;
    quickAddMutation.mutate({ name: quickAddName.trim(), phone: quickAddPhone.trim() || undefined });
  };
```

**Find and delete the Quick Add button** (the one using `QuickAddIcon`, around where the customer Autocomplete is):
```jsx
              <Button
                ...
                startIcon={<QuickAddIcon />}
                onClick={() => setQuickAddDialogOpen(true)}
                ...
              >
                ...
              </Button>
```
*(Match against your actual file — the exact surrounding JSX may have more props than shown here; the identifying details are `startIcon={<QuickAddIcon />}` and `onClick={() => setQuickAddDialogOpen(true)}`.)*

**Find and delete the entire Quick Add Dialog**, near the bottom of the file:
```jsx
      <Dialog open={quickAddDialogOpen} onClose={() => setQuickAddDialogOpen(false)} maxWidth="xs" fullWidth>
        ...
        <TextField ... value={quickAddName} onChange={(e) => setQuickAddName(e.target.value)} ... />
        ...
        <TextField ... value={quickAddPhone} onChange={(e) => setQuickAddPhone(e.target.value)} ... />
        ...
        <Button onClick={() => setQuickAddDialogOpen(false)}>Cancel</Button>
        ...
      </Dialog>
```

### Step 8: `frontend/src/pages/POS.jsx` — build the new toggle + field control

**Add new state**, near your other `useState` declarations:
```js
  const [registeredMode, setRegisteredMode] = useState(false); // toggle: off = plain name, on = search registered
  const [customerNameInput, setCustomerNameInput] = useState(''); // used only when registeredMode is off
```
(`selectedCustomer` and `customers`/the Autocomplete's options already exist in this file from the current registered-customer search — you're reusing them, not rebuilding them.)

**Find the existing customer `Autocomplete` block:**
```jsx
              <Box sx={{ display: 'flex', gap: 1, mb: 2, alignItems: 'center' }}>
                <Autocomplete
                  options={customers}
                  getOptionLabel={(c) => `${c.firstName} ${c.lastName}`}
                  value={selectedCustomer}
                  onChange={(e, val) => setSelectedCustomer(val)}
                  inputValue={customerInputText}
                  onInputChange={(e, newValue) => setCustomerInputText(newValue)}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Customer (Optional)"
                      size="small"
                      ...
                    />
                  )}
                  ...
                />
              </Box>
```
**Replace the whole block with:**
```jsx
              <Box sx={{ display: 'flex', gap: 1, mb: 2, alignItems: 'center' }}>
                {registeredMode ? (
                  <Autocomplete
                    fullWidth
                    options={customers}
                    getOptionLabel={(c) => `${c.firstName} ${c.lastName}${c.phone ? ' (' + c.phone + ')' : ''}`}
                    value={selectedCustomer}
                    onChange={(e, val) => setSelectedCustomer(val)}
                    inputValue={customerInputText}
                    onInputChange={(e, newValue) => setCustomerInputText(newValue)}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label="Search registered customer"
                        size="small"
                        InputProps={{
                          ...params.InputProps,
                          startAdornment: <CustomerIcon sx={{ mr: 1, color: 'text.secondary' }} />,
                        }}
                      />
                    )}
                    noOptionsText={
                      <Box sx={{ p: 1 }}>
                        <Typography variant="body2" color="text.secondary">No match found.</Typography>
                        <Button size="small" onClick={() => setRegisteredMode(false)}>
                          Add as unregistered instead
                        </Button>
                      </Box>
                    }
                  />
                ) : (
                  <TextField
                    fullWidth
                    size="small"
                    label="Customer name (optional)"
                    placeholder="Leave blank for Walk-in"
                    value={customerNameInput}
                    onChange={(e) => setCustomerNameInput(e.target.value)}
                    InputProps={{
                      startAdornment: <CustomerIcon sx={{ mr: 1, color: 'text.secondary' }} />,
                    }}
                  />
                )}
                <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                  <Switch
                    checked={registeredMode}
                    onChange={(e) => {
                      setRegisteredMode(e.target.checked);
                      // Clear whichever side isn't active, so stale state can't leak into the sale
                      if (e.target.checked) { setCustomerNameInput(''); }
                      else { setSelectedCustomer(null); }
                    }}
                    size="small"
                  />
                  <Typography variant="caption" color="text.secondary">
                    {registeredMode ? 'Registered' : 'Unregistered'}
                  </Typography>
                </Box>
              </Box>
              {selectedCustomer && registeredMode && (
                <Chip
                  label={`${selectedCustomer.firstName} ${selectedCustomer.lastName}${selectedCustomer.phone ? ' (' + selectedCustomer.phone + ')' : ''}`}
                  onDelete={() => setSelectedCustomer(null)}
                  color="primary"
                  size="small"
                  sx={{ mb: 2 }}
                />
              )}
```
Add `Switch` to the MUI imports at the top of the file if it isn't already there:
```js
  Switch,
```

*(This is the straightforward version of the toggle — always available, not yet reacting to a shop-level "customer required" policy. If/when you build the policy setting from our earlier discussion, this is the exact spot it plugs into: default `registeredMode` based on the policy, and disable the `Switch` itself when the policy is `"Required (Full)"` so it can't be turned off.)*

### Step 9: `frontend/src/pages/POS.jsx` — send the right field at checkout

**Find this:**
```js
  const confirmCheckout = () => {
    const saleData = {
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
        price: item.price,
      })),
      customerId: selectedCustomer?.id,
      paymentMethod: 'CASH',
      amountPaid: parseFloat(cashAmount),
    };
    createSaleMutation.mutate(saleData);
  };
```
**Replace with:**
```js
  const confirmCheckout = () => {
    const saleData = {
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
        price: item.price,
      })),
      customerId: registeredMode ? (selectedCustomer?.id ?? null) : null,
      customerName: registeredMode ? null : (customerNameInput.trim() || null),
      paymentMethod: 'CASH',
      amountPaid: parseFloat(cashAmount),
    };
    createSaleMutation.mutate(saleData);
  };
```

### Step 10: Reset the new state after a successful sale

Find wherever `clearCart()` (or equivalent post-sale cleanup) currently resets `selectedCustomer` — likely something like `setSelectedCustomer(null);` inside `clearCart()` or `createSaleMutation`'s `onSuccess`. Add the two new pieces of state alongside it:
```js
setSelectedCustomer(null);
setCustomerNameInput('');
setRegisteredMode(false);
```
so the next sale starts clean rather than carrying over the previous customer's name/selection.

---

## PART 3 — What you *don't* need to touch

- `ReceiptPreview.jsx` and `Sales.jsx` — both already render `customerName`/`receipt.customerName` correctly. Since the backend now populates that field from the new snapshot logic, these pages update automatically with zero changes.
- `SaleResponse.java` — no changes needed, `customerName` already exists as a field, you're just changing what feeds it (Step 4 handles this).
- Document 3's store-credit feature — still keys off `customerId` being non-null exactly as designed; unaffected by this change.

---

## Testing checklist

- [ ] Leave the field blank, complete a sale → receipt shows "Walk-in".
- [ ] Toggle off, type "David", complete a sale → receipt shows "David", and no new row appears in the Customers page.
- [ ] Toggle on, search, select a real registered customer with a phone number → receipt shows "David (09332211)".
- [ ] Toggle on, search, select a registered customer with **no** phone or email on file → receipt shows just their name, no empty parentheses.
- [ ] Confirm the old `/customers/quick-add` endpoint is actually gone (calling it should now 404, not silently still work).
- [ ] Confirm the Customers page still works normally — this change shouldn't affect regular full customer registration at all, only the POS checkout flow.
