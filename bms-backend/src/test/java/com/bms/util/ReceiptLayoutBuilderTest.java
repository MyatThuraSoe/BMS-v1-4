package com.bms.util;

import com.bms.dto.receipt.ReceiptDto;
import com.bms.dto.receipt.ReceiptItemDto;
import com.bms.dto.response.ShopInfoResponse;
import com.bms.entity.ReceiptCustomization;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item list on a receipt omits the currency unit while the totals keep it.
 *
 * <p>The unit carries no information on a line item: the column is right
 * aligned in a fixed position, so repeating the symbol on every row is just
 * noise, and on a narrow 58mm roll it eats characters the product name needs.
 * The totals still need it, because there the number stands alone.
 *
 * <p>This is asserted against the built line list rather than the formatter
 * because the bug is a per-call-site mistake: the builder has both
 * {@code formatCurrency} and {@code formatPlain} available and the item total
 * was formatted with the wrong one.
 */
class ReceiptLayoutBuilderTest {

    private static ReceiptLayoutBuilder builderFor(String currencyCode) {
        ReceiptItemDto item = new ReceiptItemDto();
        item.setProductName("Coca Cola 500ml");
        item.setQuantity(3);
        item.setUnit("pcs");
        item.setUnitPrice(new BigDecimal("12.50"));
        item.setSubtotal(new BigDecimal("37.50"));

        ReceiptDto receipt = new ReceiptDto();
        receipt.setInvoiceNumber("INV-1001");
        receipt.setSaleDate(LocalDateTime.of(2026, 3, 4, 10, 30));
        receipt.setItems(List.of(item));
        receipt.setSubtotal(new BigDecimal("37.50"));
        receipt.setTotalAmount(new BigDecimal("37.50"));
        receipt.setAmountPaid(new BigDecimal("40.00"));
        receipt.setChangeGiven(new BigDecimal("2.50"));

        ShopInfoResponse shop = new ShopInfoResponse();
        shop.setShopName("Corner Store");
        shop.setCurrency(currencyCode);

        ReceiptCustomization customization = new ReceiptCustomization();
        customization.setPaperSize("80");

        return new ReceiptLayoutBuilder(receipt, shop, customization);
    }

    private static String itemSection(String currencyCode) {
        List<String> lines = builderFor(currencyCode).build();
        return String.join("\n", lines);
    }

    /**
     * The two lines that make up an item row: the name with its total, and the
     * quantity x unit price line. Scoped deliberately, because the totals
     * section below keeps its currency unit and a whole-receipt check would
     * trip over it.
     */
    private static List<String> itemLines(String currencyCode) {
        List<String> all = builderFor(currencyCode).build();
        List<String> itemLines = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).contains("Coca Cola")) {
                itemLines.add(all.get(i));
                if (i + 1 < all.size()) {
                    itemLines.add(all.get(i + 1));
                }
                break;
            }
        }
        assertFalse(itemLines.isEmpty(), "the item row should be present in the receipt");
        return itemLines;
    }

    @Test
    void itemLinesOmitTheCurrencyUnit() {
        String rows = String.join("\n", itemLines("USD"));

        assertTrue(rows.contains("37.50"), "the line total should still be shown");
        assertTrue(rows.contains("12.50"), "the unit price should still be shown");
        assertFalse(rows.contains("$"), "the item row must not carry a currency unit");
    }

    @Test
    void itemLinesOmitTheCurrencyUnitForMyanmarKyat() {
        // Ks is written after the number, so a leading-symbol check would miss it.
        String rows = String.join("\n", itemLines("MMK"));

        assertTrue(rows.contains("37.50"), "the line total should still be shown");
        assertFalse(rows.contains("Ks"), "the item row must not carry a Kyat unit");
    }

    @Test
    void itemLinesKeepThousandsSeparators() {
        // The unit is removed but grouping is not - the receipt is still a
        // financial document and a row of digits with no separators is harder
        // to read and to check by eye.
        ReceiptItemDto item = new ReceiptItemDto();
        item.setProductName("Bulk Order");
        item.setQuantity(10);
        item.setUnitPrice(new BigDecimal("1234.50"));
        item.setSubtotal(new BigDecimal("12345.00"));

        ReceiptDto receipt = new ReceiptDto();
        receipt.setInvoiceNumber("INV-BULK");
        receipt.setSaleDate(LocalDateTime.of(2026, 3, 4, 10, 30));
        receipt.setItems(List.of(item));
        receipt.setSubtotal(new BigDecimal("12345.00"));
        receipt.setTotalAmount(new BigDecimal("12345.00"));

        ShopInfoResponse shop = new ShopInfoResponse();
        shop.setShopName("Corner Store");
        shop.setCurrency("USD");

        ReceiptCustomization customization = new ReceiptCustomization();
        customization.setPaperSize("80");

        String output = String.join("\n", new ReceiptLayoutBuilder(receipt, shop, customization).build());

        assertTrue(output.contains("12,345.00"), "thousands separators should be kept on the item row");
        assertTrue(output.contains("1,234.50"), "thousands separators should be kept on the unit price");
    }

    @Test
    void totalsStillCarryTheCurrencyUnit() {
        // The total stands alone, so removing its unit would be genuinely
        // ambiguous rather than merely noisy.
        String receipt = itemSection("USD");

        assertTrue(receipt.contains("TOTAL:"), "the totals section should be present");
        assertTrue(receipt.contains("$"), "the totals section must keep the currency unit");
    }

    @Test
    void totalsKeepTheCurrencyUnitForMyanmarKyat() {
        String receipt = itemSection("MMK");

        assertTrue(receipt.contains("Ks"), "the Kyat total must keep its unit");
    }

    @Test
    void receiptWithNoTaxOrDiscountStillBuilds() {
        // A tax-exempt shop, or a sale recorded before those fields existed, has
        // null here. This used to throw an NPE part way through build(), so the
        // shop got no receipt at all rather than one without the tax line.
        ReceiptItemDto item = new ReceiptItemDto();
        item.setProductName("Tea");
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("500"));
        item.setSubtotal(new BigDecimal("500"));

        ReceiptDto receipt = new ReceiptDto();
        receipt.setInvoiceNumber("INV-NO-TAX");
        receipt.setSaleDate(LocalDateTime.of(2026, 3, 4, 10, 30));
        receipt.setItems(List.of(item));
        receipt.setSubtotal(new BigDecimal("500"));
        receipt.setTotalAmount(new BigDecimal("500"));
        // taxAmount and discountAmount deliberately left null.

        ShopInfoResponse shop = new ShopInfoResponse();
        shop.setShopName("Corner Store");
        shop.setCurrency("MMK");

        ReceiptCustomization customization = new ReceiptCustomization();
        customization.setPaperSize("80");

        List<String> lines = new ReceiptLayoutBuilder(receipt, shop, customization).build();
        String output = String.join("\n", lines);

        assertTrue(output.contains("TOTAL:"), "the receipt should still be produced");
        assertFalse(output.contains("Tax:"), "no tax line should appear when there is no tax");
    }
}
