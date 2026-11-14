package com.bms.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * P10: cashier-facing product projection. It carries the same fields as the
 * privileged {@link ProductResponse} but guarantees {@code costPrice} is never
 * serialized, so a ROLE_CASHIER token cannot receive cost from any product
 * endpoint that builds this view. Selecting the DTO by role keeps the exclusion
 * in one place instead of nulling fields ad hoc across callers.
 */
@JsonIgnoreProperties({"costPrice"})
public class CashierProductResponse extends ProductResponse {
}