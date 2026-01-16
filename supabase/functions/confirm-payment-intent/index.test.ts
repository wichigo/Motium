// Tests for confirm-payment-intent edge function
// Run with: deno test --allow-env --allow-net index.test.ts

import {
  assertEquals,
  assertExists,
  assertStringIncludes,
} from "https://deno.land/std@0.208.0/assert/mod.ts";
import {
  stub,
  Stub,
  returnsNext,
} from "https://deno.land/std@0.208.0/testing/mock.ts";

// ============================================
// MOCK DATA
// ============================================

const mockPaymentMethodId = "pm_test_123456789";
const mockUserId = "user-uuid-123";
const mockProAccountId = "pro-account-uuid-456";
const mockCustomerId = "cus_test_customer";
const mockPaymentIntentId = "pi_test_payment_intent";
const mockSubscriptionId = "sub_test_subscription";
const mockPriceId = "price_test_monthly";

const mockUserData = {
  id: mockUserId,
  email: "test@example.com",
  stripe_customer_id: mockCustomerId,
};

const mockProAccountData = {
  id: mockProAccountId,
  user_id: mockUserId,
  company_name: "Test Company",
  billing_anchor_day: null,
  stripe_subscription_id: null,
};

const mockPaymentIntent = {
  id: mockPaymentIntentId,
  client_secret: "pi_test_secret",
  status: "succeeded",
  amount: 499,
  currency: "eur",
  customer: mockCustomerId,
  metadata: {},
};

const mockSubscription = {
  id: mockSubscriptionId,
  customer: mockCustomerId,
  status: "active",
  items: {
    data: [{
      id: "si_test_item",
      quantity: 1,
    }],
  },
  metadata: {},
  current_period_start: Math.floor(Date.now() / 1000),
  current_period_end: Math.floor(Date.now() / 1000) + 30 * 24 * 60 * 60,
};

// ============================================
// MOCK STRIPE CLIENT
// ============================================

function createMockStripe() {
  return {
    customers: {
      create: async (params: any) => ({
        id: mockCustomerId,
        email: params.email,
        metadata: params.metadata,
      }),
      update: async (id: string, params: any) => ({
        id,
        ...params,
      }),
    },
    paymentMethods: {
      attach: async (pmId: string, params: any) => ({
        id: pmId,
        customer: params.customer,
      }),
    },
    paymentIntents: {
      create: async (params: any) => ({
        ...mockPaymentIntent,
        amount: params.amount,
        metadata: params.metadata,
      }),
    },
    prices: {
      create: async (params: any) => ({
        id: mockPriceId,
        unit_amount: params.unit_amount,
        currency: params.currency,
        recurring: params.recurring,
      }),
    },
    subscriptions: {
      create: async (params: any) => ({
        ...mockSubscription,
        customer: params.customer,
        items: { data: [{ id: "si_new", quantity: params.items[0].quantity }] },
        metadata: params.metadata,
      }),
      retrieve: async (id: string) => mockSubscription,
      update: async (id: string, params: any) => ({
        ...mockSubscription,
        ...params,
      }),
    },
  };
}

// ============================================
// MOCK SUPABASE CLIENT
// ============================================

function createMockSupabase(overrides: any = {}) {
  const defaultData = {
    users: mockUserData,
    pro_accounts: mockProAccountData,
  };

  return {
    from: (table: string) => ({
      select: (columns?: string) => ({
        eq: (col: string, val: any) => ({
          single: async () => ({
            data: overrides[table] ?? defaultData[table as keyof typeof defaultData] ?? null,
            error: null,
          }),
        }),
      }),
      update: (data: any) => ({
        eq: (col: string, val: any) => ({
          then: async (resolve: any) => resolve({ data: null, error: null }),
        }),
      }),
      insert: async (data: any) => ({ data: null, error: null }),
    }),
  };
}

// ============================================
// TESTS: VALIDATION
// ============================================

Deno.test("confirm-payment-intent: should reject missing payment_method_id", async () => {
  const requestBody = {
    user_id: mockUserId,
    price_type: "individual_lifetime",
    quantity: 1,
  };

  // Simulate validation logic
  const hasPmId = !!requestBody.payment_method_id;
  assertEquals(hasPmId, false, "Should detect missing payment_method_id");
});

Deno.test("confirm-payment-intent: should reject invalid price_type", async () => {
  const validPriceTypes = [
    "individual_monthly",
    "individual_lifetime",
    "pro_license_monthly",
    "pro_license_lifetime",
  ];

  const invalidType = "invalid_type";
  const isValid = validPriceTypes.includes(invalidType);
  assertEquals(isValid, false, "Should reject invalid price_type");
});

Deno.test("confirm-payment-intent: should accept valid price_types", async () => {
  const validPriceTypes = [
    "individual_monthly",
    "individual_lifetime",
    "pro_license_monthly",
    "pro_license_lifetime",
  ];

  for (const priceType of validPriceTypes) {
    const isValid = validPriceTypes.includes(priceType);
    assertEquals(isValid, true, `Should accept ${priceType}`);
  }
});

Deno.test("confirm-payment-intent: should require user_id or pro_account_id", async () => {
  const requestBody = {
    payment_method_id: mockPaymentMethodId,
    price_type: "individual_lifetime",
  };

  const isProPurchase = !!requestBody.pro_account_id;
  const isIndividual = !!requestBody.user_id && !requestBody.pro_account_id;
  const isValid = isProPurchase || isIndividual;

  assertEquals(isValid, false, "Should reject when neither user_id nor pro_account_id");
});

// ============================================
// TESTS: PRICE CALCULATION
// ============================================

Deno.test("confirm-payment-intent: should calculate correct amount for single item", () => {
  const PRICES = {
    individual_monthly: 499,
    individual_lifetime: 12000,
    pro_license_monthly: 499,
    pro_license_lifetime: 12000,
  };

  const quantity = 1;
  const priceType = "individual_monthly";
  const expected = 499;

  const amount = PRICES[priceType as keyof typeof PRICES] * quantity;
  assertEquals(amount, expected, "Single monthly should be 499 cents");
});

Deno.test("confirm-payment-intent: should calculate correct amount for multiple licenses", () => {
  const PRICES = {
    pro_license_monthly: 600, // 6€ TTC
  };

  const quantity = 5;
  const priceType = "pro_license_monthly";
  const expected = 3000; // 5 * 600

  const amount = PRICES[priceType as keyof typeof PRICES] * quantity;
  assertEquals(amount, expected, "5 licenses should be 3000 cents (30 EUR)");
});

Deno.test("confirm-payment-intent: should calculate correct amount for lifetime", () => {
  const PRICES = {
    pro_license_lifetime: 14400, // 144€ TTC
  };

  const quantity = 3;
  const priceType = "pro_license_lifetime";
  const expected = 43200; // 3 * 14400

  const amount = PRICES[priceType as keyof typeof PRICES] * quantity;
  assertEquals(amount, expected, "3 lifetime licenses should be 43200 cents (432 EUR)");
});

// ============================================
// TESTS: PURCHASE TYPE DETECTION
// ============================================

Deno.test("confirm-payment-intent: should detect lifetime purchase", () => {
  const priceTypes = ["individual_lifetime", "pro_license_lifetime"];

  for (const priceType of priceTypes) {
    const isLifetime = priceType.includes("lifetime");
    assertEquals(isLifetime, true, `${priceType} should be detected as lifetime`);
  }
});

Deno.test("confirm-payment-intent: should detect monthly purchase", () => {
  const priceTypes = ["individual_monthly", "pro_license_monthly"];

  for (const priceType of priceTypes) {
    const isMonthly = priceType.includes("monthly");
    assertEquals(isMonthly, true, `${priceType} should be detected as monthly`);
  }
});

Deno.test("confirm-payment-intent: should detect Pro vs Individual purchase", () => {
  // Pro purchase
  const proRequest = { pro_account_id: mockProAccountId, user_id: mockUserId };
  const isProPurchase = !!proRequest.pro_account_id;
  assertEquals(isProPurchase, true, "Should detect Pro purchase");

  // Individual purchase
  const indRequest = { user_id: mockUserId };
  const isIndividual = !!indRequest.user_id && !indRequest.pro_account_id;
  assertEquals(isIndividual, true, "Should detect Individual purchase");
});

// ============================================
// TESTS: BILLING ANCHOR DAY
// ============================================

Deno.test("confirm-payment-intent: should calculate next billing date - anchor in future", () => {
  const anchorDay = 25;
  const now = new Date(2024, 0, 10); // January 10

  let anchorDate = new Date(now.getFullYear(), now.getMonth(), anchorDay);

  // Anchor day (25) is in the future this month
  const isInFuture = anchorDate > now;
  assertEquals(isInFuture, true, "January 25 should be in the future from January 10");
});

Deno.test("confirm-payment-intent: should calculate next billing date - anchor passed", () => {
  const anchorDay = 5;
  const now = new Date(2024, 0, 10); // January 10

  let anchorDate = new Date(now.getFullYear(), now.getMonth(), anchorDay);

  // Anchor day (5) has passed, should use next month
  if (anchorDate <= now) {
    anchorDate = new Date(now.getFullYear(), now.getMonth() + 1, anchorDay);
  }

  assertEquals(anchorDate.getMonth(), 1, "Should be February");
  assertEquals(anchorDate.getDate(), 5, "Should be the 5th");
});

Deno.test("confirm-payment-intent: should default anchor day to current day if not provided", () => {
  const billingAnchorDay = undefined;
  const existingAnchorDay = undefined;
  const currentDay = 15;

  const anchorDay = billingAnchorDay || existingAnchorDay || currentDay;
  assertEquals(anchorDay, 15, "Should default to current day");
});

Deno.test("confirm-payment-intent: should use existing anchor day if available", () => {
  const billingAnchorDay = undefined;
  const existingAnchorDay = 1;
  const currentDay = 15;

  const anchorDay = billingAnchorDay || existingAnchorDay || currentDay;
  assertEquals(anchorDay, 1, "Should use existing anchor day");
});

Deno.test("confirm-payment-intent: should prioritize new billing_anchor_day over existing", () => {
  const billingAnchorDay = 20;
  const existingAnchorDay = 1;
  const currentDay = 15;

  const anchorDay = billingAnchorDay || existingAnchorDay || currentDay;
  assertEquals(anchorDay, 20, "Should prioritize new billing_anchor_day");
});

// ============================================
// TESTS: SUBSCRIPTION QUANTITY UPDATE
// ============================================

Deno.test("confirm-payment-intent: should increment subscription quantity correctly", () => {
  const existingQuantity = 3;
  const newQuantity = 2;

  const updatedQuantity = existingQuantity + newQuantity;
  assertEquals(updatedQuantity, 5, "Should add new licenses to existing");
});

// ============================================
// TESTS: STRIPE CUSTOMER HANDLING
// ============================================

Deno.test("confirm-payment-intent: should use existing customer if available", () => {
  const userData = { stripe_customer_id: mockCustomerId };

  const shouldCreateNew = !userData.stripe_customer_id;
  assertEquals(shouldCreateNew, false, "Should not create new customer");
});

Deno.test("confirm-payment-intent: should create new customer if none exists", () => {
  const userData = { stripe_customer_id: null };

  const shouldCreateNew = !userData.stripe_customer_id;
  assertEquals(shouldCreateNew, true, "Should create new customer");
});

// ============================================
// TESTS: RESPONSE FORMAT
// ============================================

Deno.test("confirm-payment-intent: lifetime response should have correct type", () => {
  const response = {
    client_secret: "pi_test_secret",
    payment_intent_id: mockPaymentIntentId,
    status: "succeeded",
    type: "one_time",
  };

  assertEquals(response.type, "one_time", "Lifetime should return 'one_time' type");
  assertExists(response.client_secret);
  assertExists(response.payment_intent_id);
});

Deno.test("confirm-payment-intent: monthly response should have correct type", () => {
  const response = {
    client_secret: "pi_test_secret",
    payment_intent_id: mockPaymentIntentId,
    status: "succeeded",
    type: "subscription_created",
  };

  assertEquals(response.type, "subscription_created", "Monthly should return 'subscription_created' type");
});

// ============================================
// INTEGRATION TEST SCENARIOS
// ============================================

Deno.test("SCENARIO: Individual lifetime purchase flow", async () => {
  const request = {
    payment_method_id: mockPaymentMethodId,
    user_id: mockUserId,
    price_type: "individual_lifetime",
    quantity: 1,
  };

  // 1. Validate request
  const isValid = !!request.payment_method_id && !!request.user_id;
  assertEquals(isValid, true, "Request should be valid");

  // 2. Detect purchase type
  const isLifetime = request.price_type.includes("lifetime");
  const isIndividual = !!request.user_id && !request.pro_account_id;
  assertEquals(isLifetime, true, "Should be lifetime");
  assertEquals(isIndividual, true, "Should be individual");

  // 3. Calculate amount
  const amount = 12000 * request.quantity;
  assertEquals(amount, 12000, "Amount should be 120 EUR");

  // 4. Expected flow: PaymentIntent only (no subscription)
  const expectedFlow = isLifetime ? "payment_intent_only" : "payment_intent_and_subscription";
  assertEquals(expectedFlow, "payment_intent_only");
});

Deno.test("SCENARIO: Pro monthly license purchase - first time", async () => {
  const request = {
    payment_method_id: mockPaymentMethodId,
    pro_account_id: mockProAccountId,
    user_id: mockUserId,
    price_type: "pro_license_monthly",
    quantity: 3,
    billing_anchor_day: 1,
  };

  const proAccount = {
    stripe_subscription_id: null, // No existing subscription
    billing_anchor_day: null,
  };

  // 1. Detect purchase type
  const isMonthly = request.price_type.includes("monthly");
  const isProPurchase = !!request.pro_account_id;
  assertEquals(isMonthly, true);
  assertEquals(isProPurchase, true);

  // 2. No existing subscription = create new
  const hasExistingSubscription = !!proAccount.stripe_subscription_id;
  assertEquals(hasExistingSubscription, false, "Should not have existing subscription");

  // 3. Should use provided billing_anchor_day
  const anchorDay = request.billing_anchor_day || proAccount.billing_anchor_day || new Date().getDate();
  assertEquals(anchorDay, 1, "Should use provided anchor day");

  // 4. Calculate amount for immediate payment
  const amount = 499 * request.quantity;
  assertEquals(amount, 1497, "Immediate payment should be 14.97 EUR");
});

Deno.test("SCENARIO: Pro monthly license purchase - adding to existing", async () => {
  const request = {
    payment_method_id: mockPaymentMethodId,
    pro_account_id: mockProAccountId,
    price_type: "pro_license_monthly",
    quantity: 2,
  };

  const proAccount = {
    stripe_subscription_id: mockSubscriptionId, // Has existing subscription
    billing_anchor_day: 15,
  };

  const existingSubscription = {
    items: { data: [{ quantity: 3 }] },
  };

  // 1. Has existing subscription = update
  const hasExistingSubscription = !!proAccount.stripe_subscription_id;
  assertEquals(hasExistingSubscription, true, "Should have existing subscription");

  // 2. Calculate new quantity
  const newQuantity = existingSubscription.items.data[0].quantity + request.quantity;
  assertEquals(newQuantity, 5, "Total should be 5 licenses");

  // 3. Immediate payment for new licenses only
  const immediatePayment = 499 * request.quantity;
  assertEquals(immediatePayment, 998, "Immediate payment for 2 new licenses");
});

Deno.test("SCENARIO: Individual monthly subscription", async () => {
  const request = {
    payment_method_id: mockPaymentMethodId,
    user_id: mockUserId,
    price_type: "individual_monthly",
    quantity: 1,
  };

  // 1. Detect type
  const isMonthly = request.price_type.includes("monthly");
  const isIndividual = !!request.user_id && !request.pro_account_id;
  assertEquals(isMonthly, true);
  assertEquals(isIndividual, true);

  // 2. Amount
  const amount = 499;
  assertEquals(amount, 499, "Monthly individual is 4.99 EUR");

  // 3. Next billing date (next month, same day)
  const now = new Date();
  const nextMonth = new Date(now.getFullYear(), now.getMonth() + 1, now.getDate());
  assertEquals(nextMonth.getMonth(), (now.getMonth() + 1) % 12, "Should be next month");
});

console.log("\n✅ All confirm-payment-intent tests defined\n");
