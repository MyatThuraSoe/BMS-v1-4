package com.bms.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "receipt_customizations")
public class ReceiptCustomization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "header_text", length = 255)
    private String headerText = "Thank you for shopping with us";

    @Column(name = "main_message", length = 255)
    private String mainMessage = "Please keep this receipt for your records.";

    @Column(name = "footer_text", length = 255)
    private String footerText = "Thank you for your business!";

    @Column(name = "paper_size", length = 10)
    private String paperSize = "58";

    @Column(name = "time_format", length = 10)
    private String timeFormat = "12";

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHeaderText() {
        return headerText;
    }

    public void setHeaderText(String headerText) {
        this.headerText = headerText;
    }

    public String getMainMessage() {
        return mainMessage;
    }

    public void setMainMessage(String mainMessage) {
        this.mainMessage = mainMessage;
    }

    public String getFooterText() {
        return footerText;
    }

    public void setFooterText(String footerText) {
        this.footerText = footerText;
    }

    public String getPaperSize() {
        return paperSize;
    }

    public void setPaperSize(String paperSize) {
        this.paperSize = paperSize;
    }

    public String getTimeFormat() {
        return timeFormat;
    }

    public void setTimeFormat(String timeFormat) {
        this.timeFormat = timeFormat;
    }
}
