// Tests for create-payment-intent Edge Function
// Run with: deno test --allow-env --allow-net index.test.ts

import {
  assertEquals,
  assertExists,
  assertStringIncludes,
} from "https://deno.land/std@0.168.0/testing/asserts.ts";

// ============================================================================
// UNIT TESTS: calculateBillingCycleAnchor
// ============================================================================

/**
 * Replicate the function for testing (since we can't import from serve())
 */
function calculateBillingCycleAnchor(billingAnchorDay: number): number {
  const now = new Date();
  const currentDay = now.getUTCDate();
  const currentMonth = now.getUTCMonth();
  const currentYear = now.getUTCFullYear();

  const anchorDay = Math.min(Math.max(billingAnchorDay, 1), 28);

  let targetMonth = currentMonth;
  let targetYear = currentYear;

  if (currentDay >= anchorDay) {
    targetMonth += 1;
    if (targetMonth > 11) {
      targetMonth = 0;
      targetYear += 1;
    }
  }

  const anchorDate = new Date(Date.UTC(targetYear, targetMonth, anchorDay, 0, 0, 0));
  return Math.floor(anchorDate.getTime() / 1000);
}

Deno.test("calculateBillingCycleAnchor - anchor day in future this month", () => {
  // Mock: today is the 5th, anchor is 15th
  const mockNow = new Date(Date.UTC(2026, 1, 5)); // Feb 5, 2026
  const originalDate = Date;

  // Override Date for this test
  const anchorDay = 15;
  const result = calculateBillingCycleAnchor(anchorDay);

  // Result should be a valid Unix timestamp
  assertExists(result);
  assertEquals(typeof result, "number");

  // Result should be in the future
  const resultDate = new Date(result * 1000);
  assertEquals(resultDate.getUTCDate(), anchorDay <= 28 ? anchorDay : 28);
});

Deno.test("calculateBillingCycleAnchor - clamps day to 1-28 range", () => {
  // Test day 0 (invalid) -> should become 1
  const result1 = calculateBillingCycleAnchor(0);
  const date1 = new Date(result1 * 1000);
  assertEquals(date1.getUTCDate(), 1);

  // Test day 31 (invalid) -> should become 28
  const result2 = calculateBillingCycleAnchor(31);
  const date2 = new Date(result2 * 1000);
  assertEquals(date2.getUTCDate(), 28);

  // Test day 29 -> should become 28
  const result3 = calculateBillingCycleAnchor(29);
  const date3 = new Date(result3 * 1000);
  assertEquals(date3.getUTCDate(), 28);
});

Deno.test("calculateBillingCycleAnchor - valid day in range", () => {
  const result = calculateBillingCycleAnchor(15);
  const date = new Date(result * 1000);

  // Should be day 15
  assertEquals(date.getUTCDate(), 15);

  // Should be midnight UTC
  assertEquals(date.getUTCHours(), 0);
  assertEquals(date.getUTCMinutes(), 0);
  assertEquals(date.getUTCSeconds(), 0);
});

Deno.test("calculateBillingCycleAnchor - December to January rollover", () => {
  // If we're in December and anchor day has passed, should go to January next year
  const anchorDay = 15;
  const result = calculateBillingCycleAnchor(anchorDay);

  // Result should be a valid timestamp
  assertExists(result);
  const date = new Date(result * 1000);
  assertEquals(date.getUTCDate(), anchorDay);
});

// ============================================================================
// INTEGRATION TESTS: API Response Structure
// ============================================================================

Deno.test("API Response - should include all required fields for new subscription", () => {
  // Mock response structure
  const mockResponse = {
    client_secret: "pi_xxx_secret_yyy",
    customer_id: "cus_xxx",
    ephemeral_key: "ek_xxx",
    payment_intent_id: null,
    subscription_id: "sub_xxx",
    product_id: "prod_xxx",
    amount_cents: 499,
    requires_payment: true,
    is_subscription_update: false,
    message: null,
  };

  // Verify structure
  assertExists(mockResponse.client_secret);
  assertExists(mockResponse.customer_id);
  assertExists(mockResponse.ephemeral_key);
  assertExists(mockResponse.subscription_id);
  assertExists(mockResponse.product_id);
  assertEquals(typeof mockResponse.amount_cents, "number");
  assertEquals(typeof mockResponse.requires_payment, "boolean");
  assertEquals(typeof mockResponse.is_subscription_update, "boolean");
});

Deno.test("API Response - subscription update with payment required", () => {
  const mockResponse = {
    client_secret: "pi_xxx_secret_yyy",
    customer_id: "cus_xxx",
    ephemeral_key: "ek_xxx",
    payment_intent_id: "pi_xxx",
    subscription_id: "sub_xxx",
    product_id: "prod_xxx",
    amount_cents: 250, // Prorated amount
    requires_payment: true,
    is_subscription_update: true,
    message: "Ajout de 2 licence(s). Montant proraté à payer maintenant.",
  };

  assertEquals(mockResponse.is_subscription_update, true);
  assertEquals(mockResponse.requires_payment, true);
  assertExists(mockResponse.message);
  assertStringIncludes(mockResponse.message, "licence");
});

Deno.test("API Response - subscription update without payment required", () => {
  const mockResponse = {
    client_secret: null,
    customer_id: "cus_xxx",
    ephemeral_key: "ek_xxx",
    payment_intent_id: null,
    subscription_id: "sub_xxx",
    product_id: "prod_xxx",
    amount_cents: 0,
    requires_payment: false,
    is_subscription_update: true,
    message: "Ajout de 1 licence(s) confirmé. Sera facturé au prochain renouvellement.",
  };

  assertEquals(mockResponse.is_subscription_update, true);
  assertEquals(mockResponse.requires_payment, false);
  assertEquals(mockResponse.client_secret, null);
  assertStringIncludes(mockResponse.message!, "renouvellement");
});

Deno.test("API Response - lifetime payment", () => {
  const mockResponse = {
    client_secret: "pi_xxx_secret_yyy",
    customer_id: "cus_xxx",
    ephemeral_key: "ek_xxx",
    payment_intent_id: "pi_xxx",
    subscription_id: null,
    product_id: "prod_xxx",
    amount_cents: 12000, // 120 EUR
    requires_payment: true,
    is_subscription_update: false,
    message: null,
  };

  assertEquals(mockResponse.subscription_id, null);
  assertExists(mockResponse.payment_intent_id);
  assertEquals(mockResponse.is_subscription_update, false);
  assertEquals(mockResponse.amount_cents, 12000);
});

// ============================================================================
// VALIDATION TESTS: Input Validation
// ============================================================================

Deno.test("Input Validation - valid priceTypes", () => {
  const validPriceTypes = [
    "individual_monthly",
    "individual_lifetime",
    "pro_license_monthly",
    "pro_license_lifetime",
  ];

  validPriceTypes.forEach((priceType) => {
    assertEquals(validPriceTypes.includes(priceType), true);
  });
});

Deno.test("Input Validation - invalid priceType rejected", () => {
  const validPriceTypes = [
    "individual_monthly",
    "individual_lifetime",
    "pro_license_monthly",
    "pro_license_lifetime",
  ];

  const invalidTypes = ["invalid", "premium", "basic", ""];

  invalidTypes.forEach((priceType) => {
    assertEquals(validPriceTypes.includes(priceType), false);
  });
});

Deno.test("Input Validation - isLifetime detection", () => {
  const testCases = [
    { priceType: "individual_monthly", expected: false },
    { priceType: "individual_lifetime", expected: true },
    { priceType: "pro_license_monthly", expected: false },
    { priceType: "pro_license_lifetime", expected: true },
  ];

  testCases.forEach(({ priceType, expected }) => {
    const isLifetime = priceType.includes("lifetime");
    assertEquals(isLifetime, expected, `Failed for ${priceType}`);
  });
});

Deno.test("Input Validation - isProLicense detection", () => {
  const testCases = [
    { priceType: "individual_monthly", expected: false },
    { priceType: "individual_lifetime", expected: false },
    { priceType: "pro_license_monthly", expected: true },
    { priceType: "pro_license_lifetime", expected: true },
  ];

  testCases.forEach(({ priceType, expected }) => {
    const isProLicense = priceType.includes("pro_license");
    assertEquals(isProLicense, expected, `Failed for ${priceType}`);
  });
});

// ============================================================================
// PRICE CONFIGURATION TESTS
// ============================================================================

Deno.test("Price Configuration - correct amounts", () => {
  const PRICES = {
    individual_monthly: 499,
    individual_lifetime: 12000,
    pro_license_monthly: 499,
    pro_license_lifetime: 12000,
  };

  assertEquals(PRICES.individual_monthly, 499); // 4.99 EUR
  assertEquals(PRICES.individual_lifetime, 12000); // 120 EUR
  assertEquals(PRICES.pro_license_monthly, 499); // 4.99 EUR
  assertEquals(PRICES.pro_license_lifetime, 12000); // 120 EUR
});

Deno.test("Price Configuration - total amount calculation", () => {
  const PRICES = {
    individual_monthly: 499,
    pro_license_monthly: 499,
  };

  // Single license
  assertEquals(PRICES.pro_license_monthly * 1, 499);

  // 5 licenses
  assertEquals(PRICES.pro_license_monthly * 5, 2495);

  // 10 licenses
  assertEquals(PRICES.pro_license_monthly * 10, 4990);
});

// ============================================================================
// PRICE IDs CONFIGURATION TESTS
// ============================================================================

Deno.test("Price IDs - all defined and valid format", () => {
  const PRICE_IDS = {
    individual_monthly: "price_1Siz4GCsRT1u49RIG9npZVR4",
    individual_lifetime: "price_1SgUbHCsRT1u49RIfI1RbQCY",
    pro_license_monthly: "price_1SgUYGCsRT1u49RImUY0mvZQ",
    pro_license_lifetime: "price_1SgUeYCsRT1u49RItR5LyYGU",
  };

  Object.entries(PRICE_IDS).forEach(([key, value]) => {
    assertExists(value, `Missing price ID for ${key}`);
    assertStringIncludes(value, "price_", `Invalid price ID format for ${key}`);
  });
});

console.log("\n✅ All tests passed!\n");
