package com.bms.service;

import com.bms.entity.ReceiptCustomization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptCustomizationServiceTest {

    @Test
    void shouldStoreAndReadReceiptCustomizationFields() {
        ReceiptCustomization customization = new ReceiptCustomization();
        customization.setHeaderText("Thank you for shopping with us");
        customization.setMainMessage("Your support keeps our business growing.");
        customization.setFooterText("Please keep this receipt for your records.");
        customization.setPaperSize("80");
        customization.setTimeFormat("24");

        assertEquals("Thank you for shopping with us", customization.getHeaderText());
        assertEquals("Your support keeps our business growing.", customization.getMainMessage());
        assertEquals("Please keep this receipt for your records.", customization.getFooterText());
        assertEquals("80", customization.getPaperSize());
        assertEquals("24", customization.getTimeFormat());
    }
}
