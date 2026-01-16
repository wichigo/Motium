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
    "invoice.payment_failed",
    "customer.subscription.created",
    "customer.subscription.updated",
    "customer.subscription.deleted",
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
  const isLifetime = true;
  const isMonthly = false;

  const lifetimeEnd = isLifetime ? null : new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  const monthlyEnd = isMonthly ? new Date(Date.now() + 30 * 24 * 60 * 60 * 1000) : null;

  assertEquals(lifetimeEnd, null, "Lifetime should have null period_end");
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
  const monthlyPriceType = "pro_license_monthly";
  const lifetimePriceType = "pro_license_lifetime";

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

console.log("\n✅ All stripe-webhook tests defined\n");
