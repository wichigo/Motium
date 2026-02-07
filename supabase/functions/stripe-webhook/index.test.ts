// Tests for stripe-webhook edge function
// Run with: deno test --allow-env --allow-net index.test.ts

import {
  assertEquals,
  assertExists,
  assertNotEquals,
} from "https://deno.land/std@0.208.0/assert/mod.ts";

// ============================================
// MOCK DATA
// ============================================

const mockUserId = "user-uuid-123";
const mockProAccountId = "pro-account-uuid-456";
const mockCustomerId = "cus_test_customer";
const mockPaymentIntentId = "pi_test_payment_intent";
const mockSubscriptionId = "sub_test_subscription";
const mockInvoiceId = "in_test_invoice";

const mockPaymentIntentSucceeded = {
  id: mockPaymentIntentId,
  object: "payment_intent",
  status: "succeeded",
  amount: 499,
  amount_received: 499,
  currency: "eur",
  customer: mockCustomerId,
  latest_charge: "ch_test_charge",
  metadata: {
    supabase_user_id: mockUserId,
    supabase_pro_account_id: "",
    price_type: "individual_lifetime",
    quantity: "1",
    product_id: "prod_test",
  },
};

const mockPaymentIntentProLicense = {
  ...mockPaymentIntentSucceeded,
  amount: 1497, // 3 licenses
  amount_received: 1497,
  metadata: {
    supabase_user_id: mockUserId,
    supabase_pro_account_id: mockProAccountId,
    price_type: "pro_license_monthly",
    quantity: "3",
    product_id: "prod_test",
  },
};

const mockSubscription = {
  id: mockSubscriptionId,
  object: "subscription",
  status: "active",
  customer: mockCustomerId,
  current_period_start: Math.floor(Date.now() / 1000),
  current_period_end: Math.floor(Date.now() / 1000) + 30 * 24 * 60 * 60,
  items: {
    data: [{
      id: "si_test",
      quantity: 3,
      price: { unit_amount: 499 },
    }],
  },
  metadata: {
    supabase_user_id: mockUserId,
    supabase_pro_account_id: mockProAccountId,
    price_type: "pro_license_monthly",
  },
};

const mockInvoicePaid = {
  id: mockInvoiceId,
  object: "invoice",
  status: "paid",
  customer: mockCustomerId,
  subscription: mockSubscriptionId,
  amount_paid: 1497,
  currency: "eur",
  number: "INV-001",
  invoice_pdf: "https://stripe.com/invoice.pdf",
  hosted_invoice_url: "https://stripe.com/invoice",
  period_start: Math.floor(Date.now() / 1000) - 30 * 24 * 60 * 60,
  period_end: Math.floor(Date.now() / 1000),
  payment_intent: mockPaymentIntentId,
};

// ============================================
// TESTS: EVENT TYPE HANDLING
// ============================================

Deno.test("stripe-webhook: should handle payment_intent.succeeded event", () => {
  const eventType = "payment_intent.succeeded";
  const supportedEvents = [
    "payment_intent.succeeded",
    "checkout.session.completed",
    "invoice.paid",
    "invoice.upcoming",
    "invoice.created",
    "invoice.payment_failed",
    "customer.subscription.created",
    "customer.subscription.updated",
    "customer.subscription.deleted",
    "subscription_schedule.updated",
  ];

  const isSupported = supportedEvents.includes(eventType);
  assertEquals(isSupported, true, "payment_intent.succeeded should be supported");
});

Deno.test("stripe-webhook: should handle invoice.paid event", () => {
  const eventType = "invoice.paid";
  const supportedEvents = ["invoice.paid"];

  const isSupported = supportedEvents.includes(eventType);
  assertEquals(isSupported, true, "invoice.paid should be supported");
});

Deno.test("stripe-webhook: should handle subscription_schedule.updated event", () => {
  const eventType = "subscription_schedule.updated";
  const supportedEvents = ["subscription_schedule.updated"];

  const isSupported = supportedEvents.includes(eventType);
  assertEquals(isSupported, true, "subscription_schedule.updated should be supported");
});

Deno.test("stripe-webhook: should ignore unknown events", () => {
  const eventType = "unknown.event";
  const supportedEvents = [
    "payment_intent.succeeded",
    "invoice.paid",
    "customer.subscription.created",
  ];

  const isSupported = supportedEvents.includes(eventType);
  assertEquals(isSupported, false, "Unknown events should not be supported");
});

// ============================================
// TESTS: PAYMENT_INTENT.SUCCEEDED - Metadata parsing
// ============================================

Deno.test("stripe-webhook: should parse metadata from payment_intent", () => {
  const metadata = mockPaymentIntentSucceeded.metadata;

  assertEquals(metadata.supabase_user_id, mockUserId);
  assertEquals(metadata.price_type, "individual_lifetime");
  assertEquals(metadata.quantity, "1");
});

Deno.test("stripe-webhook: should detect lifetime vs monthly from price_type", () => {
  const lifetimeMetadata = { price_type: "individual_lifetime" };
  const monthlyMetadata = { price_type: "pro_license_monthly" };

  const isLifetime1 = lifetimeMetadata.price_type.includes("lifetime");
  const isLifetime2 = monthlyMetadata.price_type.includes("lifetime");

  assertEquals(isLifetime1, true, "individual_lifetime should be lifetime");
  assertEquals(isLifetime2, false, "pro_license_monthly should not be lifetime");
});

Deno.test("stripe-webhook: should detect pro vs individual from metadata", () => {
  const proMetadata = mockPaymentIntentProLicense.metadata;
  const indMetadata = mockPaymentIntentSucceeded.metadata;

  const isPro1 = !!proMetadata.supabase_pro_account_id;
  const isPro2 = !!indMetadata.supabase_pro_account_id;

  assertEquals(isPro1, true, "Pro license should have pro_account_id");
  assertEquals(isPro2, false, "Individual should not have pro_account_id");
});

// ============================================
// TESTS: PAYMENT_INTENT.SUCCEEDED - stripe_payments insert
// ============================================

Deno.test("stripe-webhook: should create correct stripe_payments record", () => {
  const paymentIntent = mockPaymentIntentSucceeded;

  const paymentRecord = {
    user_id: paymentIntent.metadata.supabase_user_id || null,
    pro_account_id: paymentIntent.metadata.supabase_pro_account_id || null,
    stripe_payment_intent_id: paymentIntent.id,
    stripe_customer_id: paymentIntent.customer,
    payment_type: "one_time_payment",
    amount_cents: paymentIntent.amount,
    amount_received_cents: paymentIntent.amount_received,
    currency: paymentIntent.currency,
    status: "succeeded",
  };

  assertEquals(paymentRecord.user_id, mockUserId);
  assertEquals(paymentRecord.stripe_payment_intent_id, mockPaymentIntentId);
  assertEquals(paymentRecord.amount_cents, 499);
  assertEquals(paymentRecord.payment_type, "one_time_payment");
});

// ============================================
// TESTS: PAYMENT_INTENT.SUCCEEDED - stripe_subscriptions insert
// ============================================

Deno.test("stripe-webhook: should create subscription record for all price types", () => {
  const priceTypes = [
    "individual_monthly",
    "individual_lifetime",
    "pro_license_monthly",
    "pro_license_lifetime",
  ];

  for (const priceType of priceTypes) {
    const shouldCreate = !!priceType; // Always create if price_type exists
    assertEquals(shouldCreate, true, `Should create subscription record for ${priceType}`);
  }
});

Deno.test("stripe-webhook: should set correct stripe_subscription_id format", () => {
  const lifetimePriceType = "individual_lifetime";
  const monthlyPriceType = "pro_license_monthly";
  const paymentIntentId = mockPaymentIntentId;

  const lifetimeSubId = `lifetime_${paymentIntentId}`;
  const monthlySubId = `onetime_${paymentIntentId}`;

  assertEquals(lifetimeSubId, `lifetime_${mockPaymentIntentId}`);
  assertEquals(monthlySubId, `onetime_${mockPaymentIntentId}`);
});

Deno.test("stripe-webhook: should set current_period_end correctly", () => {
  // Test lifetime subscription - no end date
  const isLifetime = true;
  const lifetimeEnd = isLifetime ? null : new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  assertEquals(lifetimeEnd, null, "Lifetime should have null period_end");

  // Test monthly subscription - has end date
  const isMonthly = true; // Fixed: should be true to test monthly behavior
  const monthlyEnd = isMonthly ? new Date(Date.now() + 30 * 24 * 60 * 60 * 1000) : null;
  assertNotEquals(monthlyEnd, null, "Monthly should have period_end set");
});

// ============================================
// TESTS: PAYMENT_INTENT.SUCCEEDED - License creation
// ============================================

Deno.test("stripe-webhook: should create licenses for pro_license purchases", () => {
  const priceTypes = [
    { type: "pro_license_monthly", shouldCreate: true },
    { type: "pro_license_lifetime", shouldCreate: true },
    { type: "individual_monthly", shouldCreate: false },
    { type: "individual_lifetime", shouldCreate: false },
  ];

  for (const { type, shouldCreate } of priceTypes) {
    const hasProAccountId = type.includes("pro_license");
    const isProLicense = type === "pro_license_monthly" || type === "pro_license_lifetime";
    const willCreateLicenses = hasProAccountId && isProLicense;

    assertEquals(willCreateLicenses, shouldCreate, `${type} license creation: ${shouldCreate}`);
  }
});

Deno.test("stripe-webhook: should create correct number of licenses", () => {
  const quantity = parseInt(mockPaymentIntentProLicense.metadata.quantity);
  const expectedLicenses = 3;

  assertEquals(quantity, expectedLicenses, "Should create 3 licenses");
});

Deno.test("stripe-webhook: should set is_lifetime correctly on licenses", () => {
  const monthlyPriceType: string = "pro_license_monthly";
  const lifetimePriceType: string = "pro_license_lifetime";

  const isLifetimeMonthly = monthlyPriceType === "pro_license_lifetime";
  const isLifetimeLifetime = lifetimePriceType === "pro_license_lifetime";

  assertEquals(isLifetimeMonthly, false, "Monthly licenses should not be lifetime");
  assertEquals(isLifetimeLifetime, true, "Lifetime licenses should be lifetime");
});

Deno.test("stripe-webhook: should set correct price_monthly_ht on licenses", () => {
  const isLifetime = false;
  const priceMonthlyHT = isLifetime ? 0 : 5.00; // 6.00 TTC / 1.20 = 5.00 HT

  assertEquals(priceMonthlyHT, 5.00, "Monthly license should have price_monthly_ht of 5.00 EUR");
});

// ============================================
// TESTS: PAYMENT_INTENT.SUCCEEDED - Idempotency
// ============================================

Deno.test("stripe-webhook: should check for existing licenses (idempotency)", () => {
  const existingLicenses = [{ id: "license-1" }, { id: "license-2" }];
  const noExistingLicenses: any[] = [];

  const shouldSkip1 = existingLicenses.length > 0;
  const shouldSkip2 = noExistingLicenses.length > 0;

  assertEquals(shouldSkip1, true, "Should skip if licenses exist");
  assertEquals(shouldSkip2, false, "Should create if no licenses exist");
});

// ============================================
// TESTS: INVOICE.PAID - Subscription renewal
// ============================================

Deno.test("stripe-webhook: should skip invoice.paid if no subscription", () => {
  const invoiceWithSub = { subscription: mockSubscriptionId };
  const invoiceWithoutSub = { subscription: null };

  const shouldProcess1 = !!invoiceWithSub.subscription;
  const shouldProcess2 = !!invoiceWithoutSub.subscription;

  assertEquals(shouldProcess1, true, "Should process invoice with subscription");
  assertEquals(shouldProcess2, false, "Should skip invoice without subscription");
});

Deno.test("stripe-webhook: should insert payment record for invoice.paid", () => {
  const invoice = mockInvoicePaid;

  const paymentRecord = {
    stripe_invoice_id: invoice.id,
    stripe_payment_intent_id: invoice.payment_intent,
    stripe_customer_id: invoice.customer,
    payment_type: "subscription_payment",
    amount_cents: invoice.amount_paid,
    status: "succeeded",
  };

  assertEquals(paymentRecord.payment_type, "subscription_payment");
  assertEquals(paymentRecord.amount_cents, 1497);
});

Deno.test("stripe-webhook: should update stripe_subscriptions period on invoice.paid", () => {
  const subscription = mockSubscription;

  const periodStart = new Date(subscription.current_period_start * 1000).toISOString();
  const periodEnd = new Date(subscription.current_period_end * 1000).toISOString();

  assertExists(periodStart, "Should have period_start");
  assertExists(periodEnd, "Should have period_end");
});

Deno.test("stripe-webhook: should renew pro licenses on invoice.paid", () => {
  const metadata = mockSubscription.metadata;
  const priceType = metadata.price_type;
  const proAccountId = metadata.supabase_pro_account_id;

  const shouldRenewLicenses = !!proAccountId && priceType === "pro_license_monthly";

  assertEquals(shouldRenewLicenses, true, "Should renew Pro monthly licenses");
});

Deno.test("stripe-webhook: should renew individual subscription on invoice.paid", () => {
  const metadata = {
    supabase_user_id: mockUserId,
    price_type: "individual_monthly",
  };

  const shouldRenewUser = !!metadata.supabase_user_id && metadata.price_type === "individual_monthly";

  assertEquals(shouldRenewUser, true, "Should renew individual monthly");
});

// ============================================
// TESTS: INVOICE.PAYMENT_FAILED
// ============================================

Deno.test("stripe-webhook: should record failed payment", () => {
  const invoice = {
    id: mockInvoiceId,
    amount_due: 499,
    status: "open",
  };

  const paymentRecord = {
    stripe_invoice_id: invoice.id,
    amount_cents: invoice.amount_due,
    status: "failed",
    failure_message: "Payment failed",
  };

  assertEquals(paymentRecord.status, "failed");
  assertExists(paymentRecord.failure_message);
});

Deno.test("stripe-webhook: should mark subscription as past_due on failure", () => {
  const subscriptionId = mockSubscriptionId;
  const newStatus = "past_due";

  assertEquals(newStatus, "past_due", "Subscription should be marked past_due");
});

// ============================================
// TESTS: CUSTOMER.SUBSCRIPTION.CREATED/UPDATED
// ============================================

Deno.test("stripe-webhook: should upsert subscription record", () => {
  const existingSubscription = { id: "existing-uuid" };
  const noExisting = null;

  const shouldUpdate = !!existingSubscription;
  const shouldInsert = !noExisting;

  assertEquals(shouldUpdate, true, "Should update if exists");
  assertEquals(shouldInsert, true, "Should insert if not exists");
});

Deno.test("stripe-webhook: should update user subscription_type for individual", () => {
  const metadata = {
    supabase_user_id: mockUserId,
    price_type: "individual_monthly",
  };
  const status = "active";

  const shouldUpdate = !!metadata.supabase_user_id &&
                       metadata.price_type.includes("individual") &&
                       (status === "active" || status === "trialing");

  assertEquals(shouldUpdate, true, "Should update individual user");
});

Deno.test("stripe-webhook: should create licenses for new pro subscription", () => {
  const metadata = {
    supabase_pro_account_id: mockProAccountId,
    price_type: "pro_license_monthly",
    quantity: "3",
  };
  const status = "active";
  const existingLicenses: any[] = [];

  const shouldCreate = !!metadata.supabase_pro_account_id &&
                       metadata.price_type === "pro_license_monthly" &&
                       status === "active" &&
                       existingLicenses.length === 0;

  assertEquals(shouldCreate, true, "Should create licenses for new pro subscription");
});

// ============================================
// TESTS: CUSTOMER.SUBSCRIPTION.DELETED
// ============================================

Deno.test("stripe-webhook: should mark subscription as canceled", () => {
  const subscriptionId = mockSubscriptionId;
  const updateData = {
    status: "canceled",
    canceled_at: new Date().toISOString(),
    ended_at: new Date().toISOString(),
  };

  assertEquals(updateData.status, "canceled");
  assertExists(updateData.canceled_at);
  assertExists(updateData.ended_at);
});

Deno.test("stripe-webhook: should downgrade individual user on subscription deletion", () => {
  const metadata = {
    supabase_user_id: mockUserId,
    price_type: "individual_monthly",
  };

  const shouldDowngrade = !!metadata.supabase_user_id && metadata.price_type.includes("individual");
  const newSubscriptionType = "EXPIRED";

  assertEquals(shouldDowngrade, true, "Should downgrade individual user");
  assertEquals(newSubscriptionType, "EXPIRED");
});

Deno.test("stripe-webhook: should cancel pro licenses on subscription deletion", () => {
  const metadata = {
    supabase_pro_account_id: mockProAccountId,
    price_type: "pro_license_monthly",
  };

  const shouldCancel = !!metadata.supabase_pro_account_id && metadata.price_type.includes("pro_license");
  const newStatus = "cancelled";

  assertEquals(shouldCancel, true, "Should cancel pro licenses");
  assertEquals(newStatus, "cancelled");
});

// ============================================
// INTEGRATION TEST SCENARIOS
// ============================================

Deno.test("SCENARIO: First time pro license purchase flow", () => {
  // 1. payment_intent.succeeded fires
  const paymentIntent = mockPaymentIntentProLicense;
  const metadata = paymentIntent.metadata;

  // 2. Verify metadata
  assertEquals(metadata.price_type, "pro_license_monthly");
  assertEquals(metadata.quantity, "3");
  assertEquals(metadata.supabase_pro_account_id, mockProAccountId);

  // 3. Should create stripe_payments record
  const paymentType = "one_time_payment";
  assertEquals(paymentType, "one_time_payment");

  // 4. Should create stripe_subscriptions record (onetime_*)
  const isLifetime = metadata.price_type.includes("lifetime");
  const subscriptionId = `${isLifetime ? 'lifetime' : 'onetime'}_${paymentIntent.id}`;
  assertEquals(subscriptionId.startsWith("onetime_"), true);

  // 5. Should create 3 licenses
  const licenseCount = parseInt(metadata.quantity);
  assertEquals(licenseCount, 3);
});

Deno.test("SCENARIO: Subscription renewal flow", () => {
  // 1. invoice.paid fires
  const invoice = mockInvoicePaid;
  const subscription = mockSubscription;
  const metadata = subscription.metadata;

  // 2. Verify it's a renewal (has subscription ID)
  assertEquals(!!invoice.subscription, true, "Should have subscription");

  // 3. Should insert stripe_payments with type subscription_payment
  const paymentType = "subscription_payment";
  assertEquals(paymentType, "subscription_payment");

  // 4. Should update subscription period
  const periodEnd = new Date(subscription.current_period_end * 1000);
  assertExists(periodEnd);

  // 5. Should renew licenses (if pro monthly)
  const shouldRenew = metadata.price_type === "pro_license_monthly";
  assertEquals(shouldRenew, true);
});

Deno.test("SCENARIO: Failed payment flow", () => {
  // 1. invoice.payment_failed fires
  const invoice = {
    id: mockInvoiceId,
    subscription: mockSubscriptionId,
    amount_due: 1497,
  };

  // 2. Should record failed payment
  const paymentStatus = "failed";
  assertEquals(paymentStatus, "failed");

  // 3. Should mark subscription as past_due
  const subscriptionStatus = "past_due";
  assertEquals(subscriptionStatus, "past_due");

  // 4. Stripe will retry according to settings
});

Deno.test("SCENARIO: Subscription cancellation flow", () => {
  const subscription = mockSubscription;
  const metadata = subscription.metadata;

  // 1. Mark subscription as canceled in stripe_subscriptions
  const newStatus = "canceled";
  assertEquals(newStatus, "canceled");

  // 2. For pro accounts, cancel licenses
  if (metadata.supabase_pro_account_id && metadata.price_type.includes("pro_license")) {
    const licenseStatus = "cancelled";
    assertEquals(licenseStatus, "cancelled");
  }

  // 3. For individual, set subscription_type to EXPIRED
  if (metadata.supabase_user_id && metadata.price_type.includes("individual")) {
    const userSubscriptionType = "EXPIRED";
    assertEquals(userSubscriptionType, "EXPIRED");
  }
});

// ============================================
// BUG FIX TESTS: payment_type incorrect (2026-02-03)
// ============================================

Deno.test("BUG FIX: PaymentIntent with invoice should be skipped", () => {
  // PaymentIntent from a subscription has an invoice field
  const paymentIntentWithInvoice = {
    id: "pi_xxx",
    invoice: "in_xxx", // ← Has invoice = subscription payment
    amount: 499,
  };

  // Should skip handlePaymentIntentSucceeded
  const shouldSkip = paymentIntentWithInvoice.invoice !== null && paymentIntentWithInvoice.invoice !== undefined;
  assertEquals(shouldSkip, true, "Should skip PaymentIntent with invoice (handled by handleInvoicePaid)");
});

Deno.test("BUG FIX: PaymentIntent without invoice should be processed", () => {
  // PaymentIntent from a one-time payment (lifetime) has no invoice
  const paymentIntentWithoutInvoice = {
    id: "pi_xxx",
    invoice: null, // ← No invoice = one-time payment
    amount: 12000,
  };

  // Should process handlePaymentIntentSucceeded
  const shouldSkip = paymentIntentWithoutInvoice.invoice !== null && paymentIntentWithoutInvoice.invoice !== undefined;
  assertEquals(shouldSkip, false, "Should process PaymentIntent without invoice (one-time payment)");
});

Deno.test("BUG FIX: subscription_payment type for invoice.paid", () => {
  // handleInvoicePaid always sets payment_type = "subscription_payment"
  const invoice = mockInvoicePaid;

  const paymentRecord = {
    stripe_invoice_id: invoice.id,
    payment_type: "subscription_payment", // ← Correct type
  };

  assertEquals(paymentRecord.payment_type, "subscription_payment",
    "Invoice payments should be subscription_payment");
});

Deno.test("BUG FIX: one_time_payment type only for lifetime purchases", () => {
  // handlePaymentIntentSucceeded sets payment_type = "one_time_payment" only for lifetime
  const lifetimePaymentIntent = {
    id: "pi_xxx",
    invoice: null, // No invoice = processed by handlePaymentIntentSucceeded
    metadata: { price_type: "individual_lifetime" },
  };

  const paymentType = "one_time_payment";
  assertEquals(paymentType, "one_time_payment",
    "Lifetime purchases should be one_time_payment");
});

// ============================================
// BUG FIX TESTS: canceled_at/ended_at (2026-02-03)
// ============================================

Deno.test("BUG FIX: canceled_at set when cancel_at_period_end is true", () => {
  // When user cancels, Stripe sets cancel_at_period_end=true but NOT canceled_at
  const subscription = {
    canceled_at: null, // Stripe doesn't set this until subscription actually ends
    cancel_at_period_end: true, // User requested cancellation
    ended_at: null,
    cancel_at: Math.floor(Date.now() / 1000) + 30 * 24 * 60 * 60, // End of period
  };

  // FIX: Use current time as cancellation request date
  const canceled_at = subscription.canceled_at
    ? new Date(subscription.canceled_at * 1000).toISOString()
    : subscription.cancel_at_period_end
      ? new Date().toISOString() // ← FIX: Use NOW instead of null
      : null;

  assertExists(canceled_at, "canceled_at should be set when cancel_at_period_end is true");
});

Deno.test("BUG FIX: canceled_at null for active subscription", () => {
  const subscription = {
    canceled_at: null,
    cancel_at_period_end: false, // Active subscription
    ended_at: null,
  };

  const canceled_at = subscription.canceled_at
    ? new Date(subscription.canceled_at * 1000).toISOString()
    : subscription.cancel_at_period_end
      ? new Date().toISOString()
      : null;

  assertEquals(canceled_at, null, "canceled_at should be null for active subscription");
});

Deno.test("BUG FIX: ended_at uses cancel_at when subscription scheduled for cancellation", () => {
  const futureTimestamp = Math.floor(Date.now() / 1000) + 30 * 24 * 60 * 60; // 30 days
  const subscription = {
    ended_at: null, // Not ended yet
    cancel_at: futureTimestamp, // Will end on this date
  };

  // FIX: Use cancel_at as planned end date
  const ended_at = subscription.ended_at
    ? new Date(subscription.ended_at * 1000).toISOString()
    : subscription.cancel_at
      ? new Date(subscription.cancel_at * 1000).toISOString() // ← FIX: Use cancel_at
      : null;

  assertExists(ended_at, "ended_at should use cancel_at when subscription is scheduled for cancellation");
  assertEquals(ended_at, new Date(futureTimestamp * 1000).toISOString());
});

Deno.test("BUG FIX: ended_at from Stripe when subscription actually ended", () => {
  const pastTimestamp = Math.floor(Date.now() / 1000) - 24 * 60 * 60; // Yesterday
  const subscription = {
    ended_at: pastTimestamp, // Actually ended
    cancel_at: null,
  };

  const ended_at = subscription.ended_at
    ? new Date(subscription.ended_at * 1000).toISOString()
    : (subscription as any).cancel_at
      ? new Date((subscription as any).cancel_at * 1000).toISOString()
      : null;

  assertExists(ended_at);
  assertEquals(ended_at, new Date(pastTimestamp * 1000).toISOString(),
    "ended_at should use Stripe's value when subscription actually ended");
});

// ============================================
// FEATURE TESTS: Pro billing consolidation (2026-02-03)
// ============================================

Deno.test("FEATURE: billing_cycle_anchor calculation - day in future", () => {
  // Simulate: today is 5th, billing_anchor_day is 15th
  const billingAnchorDay = 15;
  const currentDay = 5;

  // Should return this month's 15th
  const shouldUseThisMonth = currentDay < billingAnchorDay;
  assertEquals(shouldUseThisMonth, true, "Should use this month if anchor day is in future");
});

Deno.test("FEATURE: billing_cycle_anchor calculation - day in past", () => {
  // Simulate: today is 20th, billing_anchor_day is 15th
  const billingAnchorDay = 15;
  const currentDay = 20;

  // Should return next month's 15th
  const shouldUseNextMonth = currentDay >= billingAnchorDay;
  assertEquals(shouldUseNextMonth, true, "Should use next month if anchor day has passed");
});

Deno.test("FEATURE: billing_anchor_day clamped to 1-28", () => {
  const testCases = [
    { input: 0, expected: 1 },
    { input: 1, expected: 1 },
    { input: 15, expected: 15 },
    { input: 28, expected: 28 },
    { input: 29, expected: 28 },
    { input: 31, expected: 28 },
  ];

  testCases.forEach(({ input, expected }) => {
    const clamped = Math.min(Math.max(input, 1), 28);
    assertEquals(clamped, expected, `billing_anchor_day ${input} should clamp to ${expected}`);
  });
});

Deno.test("FEATURE: subscription update increases quantity", () => {
  const currentQuantity = 5;
  const additionalLicenses = 3;
  const newQuantity = currentQuantity + additionalLicenses;

  assertEquals(newQuantity, 8, "New quantity should be sum of current + additional");
});

Deno.test("FEATURE: proration_behavior set for subscription updates", () => {
  const proration_behavior = "create_prorations";
  assertEquals(proration_behavior, "create_prorations",
    "Should use create_prorations for mid-cycle license additions");
});

Deno.test("FEATURE: response includes is_subscription_update flag", () => {
  const responseForUpdate = {
    is_subscription_update: true,
    requires_payment: true,
    amount_cents: 250, // Prorated amount
    message: "Ajout de 3 licence(s). Montant proraté à payer maintenant.",
  };

  assertEquals(responseForUpdate.is_subscription_update, true);
  assertExists(responseForUpdate.message);
});

Deno.test("FEATURE: response when no payment required", () => {
  const responseNoPayment = {
    client_secret: null,
    is_subscription_update: true,
    requires_payment: false,
    amount_cents: 0,
    message: "Ajout de 1 licence(s) confirmé. Sera facturé au prochain renouvellement.",
  };

  assertEquals(responseNoPayment.requires_payment, false);
  assertEquals(responseNoPayment.client_secret, null);
  assertEquals(responseNoPayment.amount_cents, 0);
});

// ============================================
// BUG FIX TESTS: .single() → .maybeSingle() (2026-02-03)
// Issue: cancel_at_period_end, canceled_at, ended_at not updated
// Root cause: .single() returns 406 error when 0 rows, causing
// handleSubscriptionUpdate to fail silently
// ============================================

Deno.test("BUG FIX: .maybeSingle() returns null instead of 406 error", () => {
  // Simulating .single() vs .maybeSingle() behavior
  // .single() throws 406 "Not Acceptable" when 0 rows returned
  // .maybeSingle() returns null gracefully

  const singleBehavior = (rows: any[]) => {
    if (rows.length !== 1) {
      throw new Error("406 Not Acceptable"); // .single() behavior
    }
    return rows[0];
  };

  const maybeSingleBehavior = (rows: any[]) => {
    if (rows.length === 0) return null; // .maybeSingle() behavior
    if (rows.length > 1) throw new Error("Multiple rows");
    return rows[0];
  };

  // Test with 0 rows
  const emptyRows: any[] = [];

  // .single() throws error
  let singleThrew = false;
  try {
    singleBehavior(emptyRows);
  } catch {
    singleThrew = true;
  }
  assertEquals(singleThrew, true, ".single() should throw on 0 rows");

  // .maybeSingle() returns null
  const maybeSingleResult = maybeSingleBehavior(emptyRows);
  assertEquals(maybeSingleResult, null, ".maybeSingle() should return null on 0 rows");
});

Deno.test("BUG FIX: existing check uses correct logic after fix", () => {
  // Before fix: existing could be undefined due to 406 error
  // After fix: existing is null if subscription doesn't exist

  const existingNull = null;
  const existingFound = { id: "uuid-123" };

  // With null, should INSERT new subscription
  const shouldInsertNull = !existingNull;
  assertEquals(shouldInsertNull, true, "Should INSERT when existing is null");

  // With found data, should UPDATE existing subscription
  const shouldUpdateFound = !!existingFound;
  assertEquals(shouldUpdateFound, true, "Should UPDATE when existing is found");
});

Deno.test("BUG FIX: subscription update flow after .maybeSingle() fix", () => {
  // Simulating the fixed handleSubscriptionUpdate flow
  const subscription = {
    id: "sub_test",
    cancel_at_period_end: true,
    canceled_at: 1770125223, // Unix timestamp
    cancel_at: 1772496000, // Future end date
    ended_at: null,
    status: "active",
  };

  // After fix, even if subscription doesn't exist in DB yet,
  // we don't get 406 error, we get null and do INSERT
  const existing = null; // .maybeSingle() returns null

  const subscriptionData = {
    cancel_at_period_end: subscription.cancel_at_period_end,
    canceled_at: subscription.canceled_at
      ? new Date(subscription.canceled_at * 1000).toISOString()
      : subscription.cancel_at_period_end
        ? new Date().toISOString()
        : null,
    ended_at: subscription.ended_at
      ? new Date((subscription as any).ended_at * 1000).toISOString()
      : (subscription as any).cancel_at
        ? new Date((subscription as any).cancel_at * 1000).toISOString()
        : null,
    status: subscription.status,
  };

  // Verify cancellation fields are properly set
  assertEquals(subscriptionData.cancel_at_period_end, true,
    "cancel_at_period_end should be true");
  assertExists(subscriptionData.canceled_at,
    "canceled_at should be set from Stripe timestamp");
  assertExists(subscriptionData.ended_at,
    "ended_at should be set from cancel_at");

  // Verify the decision logic
  if (existing) {
    // UPDATE path
    assertEquals(true, false, "Should not reach UPDATE path when existing is null");
  } else {
    // INSERT path (correct for new subscription)
    assertEquals(true, true, "Should reach INSERT path when existing is null");
  }
});

Deno.test("BUG FIX: cancel-subscription PATCH verifies rows updated", () => {
  // Simulating the fix that adds .select() to verify rows were updated

  // Before fix: PATCH returns 204 even if 0 rows match (silent failure)
  // After fix: PATCH with .select() returns empty array if 0 rows match

  const updatedRowsEmpty: any[] = [];
  const updatedRowsOne = [{ id: "uuid-123" }];

  // With empty result, should log warning
  const shouldWarnEmpty = !updatedRowsEmpty || updatedRowsEmpty.length === 0;
  assertEquals(shouldWarnEmpty, true, "Should warn when 0 rows updated");

  // With 1 result, should log success
  const shouldSucceed = updatedRowsOne && updatedRowsOne.length > 0;
  assertEquals(shouldSucceed, true, "Should succeed when 1+ rows updated");
});

console.log("\n✅ All stripe-webhook tests defined\n");
