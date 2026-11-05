package com.bms.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** A drawer security/expense operation (safe drop, pay in/out, no-sale open, adjustment). */
public class DrawerMovementRequest {

    /** SAFE_DROP, PAY_IN, PAY_OUT, NO_SALE_OPEN or ADJUSTMENT. */
    @NotNull(message = "Movement type is required")
    private String movementType;

    /** Always sent as a positive magnitude; the backend applies the sign. */
    @NotNull(message = "Amount is required")
    @PositiveOrZero(message = "Amount must be zero or positive")
    private BigDecimal amount;

    /**
     * Only used by ADJUSTMENT: INCREASE adds cash to the drawer, DECREASE
     * removes it. Defaults to INCREASE.
     */
    private String direction;

    private String reason;

    private String externalReference;

    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }
}
