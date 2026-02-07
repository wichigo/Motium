// Supabase Edge Function: Stripe Webhook Handler
// Deploy: supabase functions deploy stripe-webhook --no-verify-jwt
//
// ============================================================================
// LICENSE STATUS LIFECYCLE (Pro Accounts)
// ============================================================================
// available → License in pool, not assigned to anyone
// active    → License assigned to a collaborator via company_links
// suspended → Payment failed, access blocked but not deleted
// canceled  → Marked for deletion at next renewal (via processProRenewal)
// pending   → License created but not yet activated
// paused    → Unassigned license paused (deprecated feature)
//
// NOTE: There is NO 'unlinked' status! Unlink is tracked via unlink_requested_at
// and unlink_effective_at timestamps while status remains 'active'.
//
// TRANSITIONS:
// - Purchase: Creates licenses with status='available'
// - Assignment (ARBRE 3): available → active + linked_account_id set
// - Payment failure: active/available → suspended
// - Payment success (renewal): suspended → active
// - Cancellation request: Subscription deleted → status='canceled'
// - Unlink request: status stays 'active', unlink_requested_at/effective_at set
// - Renewal processing (ARBRE 6):
//   - canceled licenses → DELETE (monthly) or return to pool (lifetime)
//   - active licenses with unlink_effective_at <= NOW() → same as above
// ============================================================================

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.0";

serve(async (req) => {
  try {
    // Get Supabase client with service role (needed early for vault access)
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    // Get Stripe secrets - try env first, then vault fallback
    let stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY");
    let webhookSecret = Deno.env.get("STRIPE_WEBHOOK_SECRET");

    // Fallback to vault for secrets (useful for self-hosted Supabase)
    if (!stripeSecretKey || !webhookSecret) {
      const { data: vaultSecrets } = await supabase
        .from("vault.decrypted_secrets")
        .select("name, decrypted_secret")
        .in("name", ["STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET"]);

      if (vaultSecrets) {
        for (const secret of vaultSecrets) {
          if (secret.name === "STRIPE_SECRET_KEY" && !stripeSecretKey) {
            stripeSecretKey = secret.decrypted_secret;
          }
          if (secret.name === "STRIPE_WEBHOOK_SECRET" && !webhookSecret) {
            webhookSecret = secret.decrypted_secret;
          }
        }
      }
    }

    if (!stripeSecretKey || !webhookSecret) {
      throw new Error("Missing Stripe configuration (checked env and vault)");
    }

    const stripe = new Stripe(stripeSecretKey, {
      apiVersion: "2023-10-16",
      httpClient: Stripe.createFetchHttpClient(),
    });

    // Verify webhook signature
    const signature = req.headers.get("stripe-signature");
    if (!signature) {
      return new Response("Missing stripe-signature header", { status: 400 });
    }

    const body = await req.text();
    let event: Stripe.Event;

    try {
      event = await stripe.webhooks.constructEventAsync(
        body,
        signature,
        webhookSecret,
      );
    } catch (err) {
      console.error("Webhook signature verification failed:", err.message);
      return new Response(`Webhook Error: ${err.message}`, { status: 400 });
    }

    console.log(`Received event: ${event.type}`);

    // Handle different event types
    switch (event.type) {
      case "payment_intent.succeeded": {
        const paymentIntent = event.data.object as Stripe.PaymentIntent;
        await handlePaymentIntentSucceeded(supabase, stripe, paymentIntent);
        break;
      }

      case "checkout.session.completed": {
        const session = event.data.object as Stripe.Checkout.Session;
        await handleCheckoutSessionCompleted(supabase, session);
        break;
      }

      case "invoice.paid": {
        const invoice = event.data.object as Stripe.Invoice;
        await handleInvoicePaid(supabase, stripe, invoice);
        break;
      }

      case "invoice.upcoming": {
        const invoice = event.data.object as Stripe.Invoice;
        try {
          await handleInvoiceUpcoming(supabase, stripe, invoice);
        } catch (error) {
          // Non-blocking: never fail webhook delivery for upcoming invoice sync
          // to avoid impacting Stripe's invoicing lifecycle.
          console.error(
            `⚠️ invoice.upcoming handler error (non-blocking) for ${invoice.id}:`,
            error,
          );
        }
        break;
      }

      case "invoice.created": {
        const invoice = event.data.object as Stripe.Invoice;
        // Important: failing invoice.created can delay Stripe invoice finalization.
        // We acknowledge the event and let Stripe finalize/charge normally.
        console.log(
          `ℹ️ invoice.created ${invoice.id}: acknowledged (no-op to avoid finalization delays)`,
        );
        break;
      }

      case "invoice.payment_failed": {
        const invoice = event.data.object as Stripe.Invoice;
        await handleInvoicePaymentFailed(supabase, invoice);
        break;
      }

      case "customer.subscription.created":
      case "customer.subscription.updated": {
        const subscription = event.data.object as Stripe.Subscription;
        await handleSubscriptionUpdate(supabase, subscription);
        break;
      }

      case "customer.subscription.deleted": {
        const subscription = event.data.object as Stripe.Subscription;
        await handleSubscriptionDeleted(supabase, subscription);
        break;
      }

      case "subscription_schedule.updated": {
        const schedule = event.data.object as Stripe.SubscriptionSchedule;
        await handleSubscriptionScheduleUpdated(supabase, stripe, schedule);
        break;
      }

      default:
        console.log(`Unhandled event type: ${event.type}`);
    }

    return new Response(JSON.stringify({ received: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  } catch (error) {
    console.error("Webhook error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }
});

/**
 * Handle successful one-time payment (lifetime subscriptions)
 *
 * ARBRE 1 INDIVIDUEL:
 * - individual_lifetime: User becomes LIFETIME (via trigger sync_user_subscription_cache)
 *
 * ARBRE 2 PRO:
 * - pro_license_lifetime: Creates lifetime license(s) in the pool with status='available'
 * - pro_license_monthly: Creates monthly license(s) in the pool (via PaymentIntent for Checkout)
 *
 * BILLING ANCHOR NOTE:
 * ====================
 * - For Pro monthly subscriptions, billing anchor can be configured:
 *   1) On subscription creation (billing_cycle_anchor in create-payment/confirm-payment flows)
 *   2) After creation via the dedicated update-billing-anchor function
 *      (implemented with Stripe Subscription Schedules)
 * - Mid-cycle license additions are still handled with proration logic where applicable.
 * ====================
 */
async function handlePaymentIntentSucceeded(
  supabase: any,
  stripe: Stripe,
  paymentIntent: Stripe.PaymentIntent,
) {
  // FIX BUG: Skip PaymentIntents that belong to subscriptions (invoices)
  // These are handled by handleInvoicePaid() with correct payment_type="subscription_payment"
  // Without this check, subscription payments were incorrectly recorded as "one_time_payment"
  if (paymentIntent.invoice) {
    console.log(
      `ℹ️ PaymentIntent ${paymentIntent.id} is linked to invoice ${paymentIntent.invoice} - skipping (handled by handleInvoicePaid)`,
    );
    return;
  }

  const {
    supabase_user_id,
    supabase_pro_account_id,
    price_type,
    product_id,
    quantity,
  } = paymentIntent.metadata;

  console.log(
    `Processing payment success: ${paymentIntent.id}, type: ${price_type}`,
  );

  // ARBRE 1 INDIVIDUEL - Validation rules
  if (supabase_user_id && price_type?.includes("individual")) {
    const { data: user } = await supabase
      .from("users")
      .select("subscription_type")
      .eq("id", supabase_user_id)
      .single();

    const currentType = user?.subscription_type;

    // Rule 1: LICENSED users cannot purchase Individual subscription
    if (currentType === "LICENSED") {
      console.error(
        `❌ BLOCKED: User ${supabase_user_id} is LICENSED and cannot purchase Individual`,
      );
      console.error(
        `   Payment received but subscription NOT activated. Manual refund required.`,
      );
      await recordBlockedPayment(
        supabase,
        supabase_user_id,
        paymentIntent,
        "BLOCKED: User is LICENSED",
      );
      return;
    }

    // Rule 2: LIFETIME users cannot purchase anything (they already have the best plan)
    if (currentType === "LIFETIME") {
      console.error(
        `❌ BLOCKED: User ${supabase_user_id} already has LIFETIME and cannot purchase again`,
      );
      console.error(
        `   Payment received but subscription NOT activated. Manual refund required.`,
      );
      await recordBlockedPayment(
        supabase,
        supabase_user_id,
        paymentIntent,
        "BLOCKED: User already has LIFETIME",
      );
      return;
    }

    // Rule 3: PREMIUM users cannot purchase another PREMIUM (would cause double billing)
    if (currentType === "PREMIUM" && price_type === "individual_monthly") {
      console.error(
        `❌ BLOCKED: User ${supabase_user_id} already has active PREMIUM subscription`,
      );
      console.error(
        `   Duplicate subscription attempt. Payment received but NOT activated.`,
      );
      await recordBlockedPayment(
        supabase,
        supabase_user_id,
        paymentIntent,
        "BLOCKED: User already has active PREMIUM",
      );
      return;
    }

    // Rule 4: PREMIUM users CAN upgrade to LIFETIME (valid upgrade path)
    if (currentType === "PREMIUM" && price_type === "individual_lifetime") {
      console.log(
        `⬆️ User ${supabase_user_id} upgrading from PREMIUM to LIFETIME`,
      );

      // AUTO-CANCEL: Immediately cancel the existing monthly subscription
      // when upgrading to LIFETIME to prevent double billing
      const { data: existingMonthly } = await supabase
        .from("stripe_subscriptions")
        .select("id, stripe_subscription_id")
        .eq("user_id", supabase_user_id)
        .eq("subscription_type", "individual_monthly")
        .in("status", ["active", "trialing", "past_due"])
        .maybeSingle();

      if (existingMonthly?.stripe_subscription_id) {
        const now = new Date().toISOString();

        try {
          // 1. Cancel on Stripe with cancel_at_period_end = true
          // This stops future billing immediately
          await stripe.subscriptions.update(
            existingMonthly.stripe_subscription_id,
            {
              cancel_at_period_end: true,
            },
          );

          // 2. Update our stripe_subscriptions table
          // Mark as canceled immediately since user now has LIFETIME
          await supabase
            .from("stripe_subscriptions")
            .update({
              cancel_at_period_end: true,
              canceled_at: now,
              ended_at: now,
              status: "canceled",
              updated_at: now,
            })
            .eq("id", existingMonthly.id);

          console.log(
            `✅ Auto-canceled monthly subscription ${existingMonthly.stripe_subscription_id} due to lifetime upgrade`,
          );
        } catch (err) {
          // Non-blocking: Don't prevent lifetime purchase if cancellation fails
          // The user will have their old subscription to manage manually
          console.error(
            `⚠️ Failed to auto-cancel monthly subscription (non-blocking):`,
            err,
          );
        }
      }

      // Continue with the payment - LIFETIME will override PREMIUM via trigger
    }

    // Rule 5: TRIAL users cannot "buy" another trial (nonsensical operation)
    // This shouldn't happen via normal flow but catch it for completeness
    if (currentType === "TRIAL" && price_type === "individual_trial") {
      console.error(`❌ BLOCKED: User ${supabase_user_id} is already in TRIAL`);
      await recordBlockedPayment(
        supabase,
        supabase_user_id,
        paymentIntent,
        "BLOCKED: Invalid TRIAL purchase attempt",
      );
      return;
    }

    // Valid transitions logged for audit:
    // TRIAL → PREMIUM ✅
    // TRIAL → LIFETIME ✅
    // EXPIRED → PREMIUM ✅
    // EXPIRED → LIFETIME ✅
    // PREMIUM → LIFETIME ✅ (upgrade)
    console.log(
      `✅ User ${supabase_user_id} validated for Individual ${price_type} (current: ${currentType})`,
    );
  }

  // Insert into stripe_payments table with all Stripe references (idempotent)
  // FIX: PostgREST upsert doesn't work with partial unique indexes (WHERE ... IS NOT NULL)
  // Use manual check-then-insert pattern instead
  const { data: existingPayment } = await supabase
    .from("stripe_payments")
    .select("id")
    .eq("stripe_payment_intent_id", paymentIntent.id)
    .maybeSingle();

  if (!existingPayment) {
    // Determine payment_type based on price_type
    // - lifetime purchases = one_time_payment (no recurring billing)
    // - monthly subscriptions = subscription_payment (initial payment for recurring)
    const isMonthlySubscription = price_type?.includes("monthly") || false;
    const paymentType = isMonthlySubscription
      ? "subscription_payment"
      : "one_time_payment";

    await supabase.from("stripe_payments").insert({
      user_id: supabase_user_id || null,
      pro_account_id: supabase_pro_account_id || null,
      stripe_payment_intent_id: paymentIntent.id,
      stripe_charge_id: paymentIntent.latest_charge as string || null,
      stripe_customer_id: paymentIntent.customer as string,
      payment_type: paymentType,
      amount_cents: paymentIntent.amount,
      amount_received_cents: paymentIntent.amount_received,
      currency: paymentIntent.currency,
      status: "succeeded",
      receipt_url: paymentIntent.latest_charge
        ? `https://dashboard.stripe.com/payments/${paymentIntent.latest_charge}`
        : null,
      metadata: paymentIntent.metadata,
      paid_at: new Date().toISOString(),
    });
  } else {
    console.log(
      `Payment record already exists for PI ${paymentIntent.id}, skipping insert`,
    );
  }

  // Create a "subscription" record ONLY for lifetime purchases
  // Monthly subscriptions are handled by handleSubscriptionUpdate when
  // customer.subscription.created/updated event arrives with the real sub_xxx ID
  // ARBRE 2 PRO BOUCLE 3: Returns the created record to get stripe_subscription_ref
  let stripeSubscriptionRef: string | null = null;
  const isLifetime = price_type?.includes("lifetime") || false;
  const isMonthly = price_type?.includes("monthly") || false;

  // FIX BUG: Only create stripe_subscriptions record for LIFETIME purchases here
  // Monthly subscriptions will be created by handleSubscriptionUpdate with the real Stripe sub_xxx ID
  if (price_type && isLifetime) {
    const qty = parseInt(quantity || "1");
    const fakeStripeSubId = `lifetime_${paymentIntent.id}`;

    // FIX: Include stripe_price_id - for lifetime/one-time payments, we use a synthetic ID
    // based on price_type since there's no real Stripe Price object
    const syntheticPriceId = `price_${price_type}_${product_id || "unknown"}`;

    const subscriptionData = {
      user_id: supabase_user_id || null,
      pro_account_id: supabase_pro_account_id || null,
      stripe_subscription_id: fakeStripeSubId,
      stripe_customer_id: paymentIntent.customer as string,
      stripe_product_id: product_id,
      stripe_price_id: syntheticPriceId, // FIX: Now populated
      subscription_type: price_type,
      status: "active",
      quantity: qty,
      currency: paymentIntent.currency,
      unit_amount_cents: paymentIntent.amount / qty,
      current_period_start: new Date().toISOString(),
      current_period_end: isLifetime
        ? null
        : new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
      metadata: paymentIntent.metadata,
    };

    const { data: insertedSub } = await supabase
      .from("stripe_subscriptions")
      .insert(subscriptionData)
      .select("id")
      .single();

    stripeSubscriptionRef = insertedSub?.id || null;
    console.log(
      `Created subscription record: ${price_type} (internal id: ${
        stripeSubscriptionRef || "null"
      })`,
    );
    // NOTE: users.subscription_type is synchronized automatically by SQL trigger
    // sync_user_subscription_cache() in stripe_integration.sql - NO direct update needed

    // FIX: Link stripe_payments to stripe_subscriptions via stripe_subscription_ref
    if (stripeSubscriptionRef) {
      await supabase.from("stripe_payments")
        .update({ stripe_subscription_ref: stripeSubscriptionRef })
        .eq("stripe_payment_intent_id", paymentIntent.id);
      console.log(
        `Linked payment ${paymentIntent.id} to subscription ref ${stripeSubscriptionRef}`,
      );
    }
  }

  // ARBRE 2 PRO - Create licenses for Pro account purchases
  if (
    supabase_pro_account_id &&
    (price_type === "pro_license_monthly" ||
      price_type === "pro_license_lifetime")
  ) {
    const qty = parseInt(quantity || "1");

    // Idempotency check: skip if licenses already exist for this subscription ref
    // FIX: We now use stripe_subscription_ref instead of stripe_payment_intent_id
    // because the unique index on stripe_payment_intent_id prevents multi-license inserts
    const { data: existingLicenses } = await supabase
      .from("licenses")
      .select("id")
      .eq("stripe_subscription_ref", stripeSubscriptionRef);

    if (existingLicenses && existingLicenses.length > 0) {
      console.log(
        `Licenses already exist for subscription ref ${stripeSubscriptionRef}, skipping creation (found ${existingLicenses.length})`,
      );
    } else {
      // Validate Pro account exists and is valid
      const { data: proAccount, error: proError } = await supabase
        .from("pro_accounts")
        .select("id, user_id, status, trial_ends_at, billing_anchor_day")
        .eq("id", supabase_pro_account_id)
        .single();

      if (proError || !proAccount) {
        console.error(
          `❌ BLOCKED: Pro account ${supabase_pro_account_id} not found`,
        );
        await recordBlockedPayment(
          supabase,
          null,
          paymentIntent,
          "BLOCKED: Pro account not found",
        );
        return;
      }

      // SECURITY: Validate that the payer is the owner of this pro_account
      // (if supabase_user_id is provided in metadata, which it should be for Pro purchases)
      if (supabase_user_id && proAccount.user_id !== supabase_user_id) {
        console.error(
          `❌ BLOCKED: User ${supabase_user_id} is not the owner of pro_account ${supabase_pro_account_id}`,
        );
        console.error(`   Expected owner: ${proAccount.user_id}`);
        await recordBlockedPayment(
          supabase,
          supabase_user_id,
          paymentIntent,
          "BLOCKED: User is not pro_account owner",
        );
        return;
      }

      // ARBRE 2 PRO BOUCLE 4: Handle pro_account status transitions
      // Allowed statuses for purchase: trial, active, expired
      // Blocked status: suspended (payment failure - must resolve first)
      if (proAccount.status === "suspended") {
        console.error(
          `❌ BLOCKED: Pro account ${supabase_pro_account_id} is suspended due to payment failure`,
        );
        console.error(
          `   The pro owner must resolve the failed payment before purchasing additional licenses`,
        );
        await recordBlockedPayment(
          supabase,
          supabase_user_id,
          paymentIntent,
          "BLOCKED: Pro account is suspended - resolve payment failure first",
        );
        return;
      }

      // Check if pro account is expired (trial ended without purchase)
      // Note: We still allow purchase even if expired - this is the reactivation path
      if (proAccount.status === "expired") {
        console.log(
          `ℹ️ Pro account ${supabase_pro_account_id} was expired, reactivating with this purchase`,
        );
      }

      // Log current status for audit trail
      console.log(
        `📋 Pro account ${supabase_pro_account_id} status: ${proAccount.status} (allowed for purchase)`,
      );

      // Validate quantity limit (anti-abuse)
      const MAX_LICENSES_PER_PURCHASE = 100;
      if (qty > MAX_LICENSES_PER_PURCHASE) {
        console.error(
          `❌ BLOCKED: Quantity ${qty} exceeds max ${MAX_LICENSES_PER_PURCHASE}`,
        );
        await recordBlockedPayment(
          supabase,
          supabase_user_id,
          paymentIntent,
          `BLOCKED: Quantity ${qty} exceeds limit`,
        );
        return;
      }

      // Log current license count for audit
      const { count: currentLicenseCount } = await supabase
        .from("licenses")
        .select("*", { count: "exact", head: true })
        .eq("pro_account_id", supabase_pro_account_id)
        .not("status", "eq", "canceled");

      console.log(
        `📊 Pro account ${supabase_pro_account_id} currently has ${
          currentLicenseCount || 0
        } licenses, adding ${qty} more`,
      );

      const isLifetime = price_type === "pro_license_lifetime";
      const now = new Date();

      // For monthly licenses, set end_date to 30 days from now
      // (will be updated to actual subscription period end when subscription is created)
      const endDate = isLifetime
        ? null
        : new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000).toISOString();

      // IMPORTANT: New licenses start as 'available' (not 'active')
      // They become 'active' only when assigned to a collaborator via ARBRE 3
      // FIX: Don't set stripe_payment_intent_id on licenses - there's a UNIQUE index on it
      // which prevents inserting multiple licenses for the same payment.
      // Instead, use stripe_subscription_ref as the reference to link licenses to payments.
      // The idempotency is ensured by the check at line 311-317 (existingLicenses query).
      const licenses = Array.from({ length: qty }, () => ({
        pro_account_id: supabase_pro_account_id,
        is_lifetime: isLifetime,
        status: "available", // FIX: Use 'available' not 'active' until assigned
        stripe_subscription_ref: stripeSubscriptionRef, // FK to stripe_subscriptions.id (for idempotency, query via this)
        price_monthly_ht: isLifetime ? 0 : 5.00, // 6.00 TTC / 1.20 = 5.00 HT
        vat_rate: 0.20,
        start_date: now.toISOString(),
        end_date: endDate, // null for lifetime, 30 days for monthly
        billing_starts_at: now.toISOString(),
        created_at: now.toISOString(),
        updated_at: now.toISOString(),
      }));

      await supabase.from("licenses").insert(licenses);
      console.log(
        `✅ Created ${qty} ${
          isLifetime ? "lifetime" : "monthly"
        } licenses for pro account ${supabase_pro_account_id} (status: available)`,
      );

      // ARBRE 2 PRO: Update pro_account status and billing_anchor_day
      // This is the first license purchase that activates the Pro account
      const updateData: Record<string, any> = {
        updated_at: now.toISOString(),
      };

      // Set status to 'active' if was trial or expired
      if (proAccount.status === "trial" || proAccount.status === "expired") {
        updateData.status = "active";
        console.log(
          `✅ Pro account ${supabase_pro_account_id} activating (was: ${proAccount.status})`,
        );
      }

      // ARBRE 2 PRO BOUCLE 3: Set billing_anchor_day on first purchase if not already set
      // This determines the day of month when all licenses are billed together
      // Example: First purchase on 15th → all future renewals on 15th
      if (!proAccount.billing_anchor_day) {
        const anchorDay = Math.min(now.getUTCDate(), 28); // Cap at 28 to avoid month-end issues
        updateData.billing_anchor_day = anchorDay;
        console.log(
          `📅 Setting billing_anchor_day to ${anchorDay} for pro account ${supabase_pro_account_id}`,
        );
      }

      // Apply updates to pro_account
      if (Object.keys(updateData).length > 1) { // More than just updated_at
        await supabase.from("pro_accounts")
          .update(updateData)
          .eq("id", supabase_pro_account_id);
      }
    }
  }
}

/**
 * Handle checkout session completion
 *
 * Per ARBRE 1 INDIVIDUEL spec:
 * - checkout.session.completed with subscription → user becomes PREMIUM or LIFETIME
 * - The trigger sync_user_subscription_cache() handles the actual update
 *   when stripe_subscriptions is updated, so we just need to ensure the
 *   subscription record exists properly.
 *
 * For one-time payments (lifetime), this is handled by payment_intent.succeeded.
 * For subscriptions, this may be called before invoice.paid, so we log it.
 */
async function handleCheckoutSessionCompleted(
  supabase: any,
  session: Stripe.Checkout.Session,
) {
  console.log(`✅ Checkout session completed: ${session.id}`);
  console.log(`   Mode: ${session.mode}`);
  console.log(`   Subscription: ${session.subscription || "N/A (one-time)"}`);
  console.log(`   Payment Intent: ${session.payment_intent || "N/A"}`);
  console.log(`   Customer: ${session.customer}`);

  const metadata = session.metadata || {};
  const { supabase_user_id, price_type } = metadata;

  // Log metadata for debugging
  console.log(`   Metadata: ${JSON.stringify(metadata)}`);

  // ARBRE 1 INDIVIDUEL - Validation: User must not be LICENSED
  // A LICENSED user (has Pro license) cannot purchase an Individual subscription
  // They must first unlink from their Pro account
  if (supabase_user_id && price_type?.includes("individual")) {
    const { data: user } = await supabase
      .from("users")
      .select("subscription_type")
      .eq("id", supabase_user_id)
      .single();

    const currentType = user?.subscription_type;

    if (currentType === "LICENSED") {
      console.error(
        `❌ BLOCKED: User ${supabase_user_id} is LICENSED and cannot purchase Individual subscription`,
      );
      console.error(`   They must first unlink from their Pro account`);
      return;
    }

    if (currentType === "LIFETIME") {
      console.error(
        `❌ BLOCKED: User ${supabase_user_id} already has LIFETIME - no purchase needed`,
      );
      return;
    }

    // PREMIUM trying to buy another PREMIUM = duplicate
    if (currentType === "PREMIUM" && price_type === "individual_monthly") {
      console.error(
        `❌ BLOCKED: User ${supabase_user_id} already has active PREMIUM subscription`,
      );
      return;
    }

    console.log(
      `✅ User ${supabase_user_id} validated for Individual subscription (current: ${currentType})`,
    );
  }

  // For subscription mode, the subscription is created and will be handled
  // by customer.subscription.created/updated events.
  // For payment mode (one-time/lifetime), payment_intent.succeeded handles it.
}

/**
 * Handle paid invoice (subscription renewals)
 */
async function handleInvoicePaid(
  supabase: any,
  stripe: Stripe,
  invoice: Stripe.Invoice,
) {
  const subscriptionId = invoice.subscription as string;
  if (!subscriptionId) {
    console.log(
      `ℹ️ Invoice ${invoice.id} paid but no subscription ID - likely one-time payment`,
    );
    return;
  }

  console.log(`📧 Invoice paid for subscription: ${subscriptionId}`);
  console.log(`   Invoice ID: ${invoice.id}`);
  console.log(`   Amount: ${invoice.amount_paid} ${invoice.currency}`);
  console.log(`   Billing reason: ${invoice.billing_reason}`);

  // Get the subscription from Stripe
  const subscription = await stripe.subscriptions.retrieve(subscriptionId);
  const metadata = subscription.metadata;

  console.log(`   Subscription status: ${subscription.status}`);
  console.log(`   Subscription metadata: ${JSON.stringify(metadata)}`);
  console.log(`   Cancel at period end: ${subscription.cancel_at_period_end}`);

  const {
    supabase_user_id,
    supabase_pro_account_id,
    price_type,
    quantity,
  } = metadata;

  console.log(
    `   Extracted: user_id=${supabase_user_id || "null"}, price_type=${
      price_type || "null"
    }`,
  );

  const periodEnd = new Date(subscription.current_period_end * 1000)
    .toISOString();
  console.log(`   New period end: ${periodEnd}`);

  // Extract subscription item and price info for reference
  const subscriptionItem = subscription.items.data[0];
  const stripePriceId = subscriptionItem?.price?.id || null;
  const stripeSubscriptionItemId = subscriptionItem?.id || null;
  const metadataQuantity = parseInt(quantity || "1");
  const stripeQuantity = (typeof subscriptionItem?.quantity === "number" &&
      subscriptionItem.quantity > 0)
    ? subscriptionItem.quantity
    : (metadataQuantity > 0 ? metadataQuantity : 1);

  // FIX: Get internal stripe_subscription_ref (UUID) from stripe_subscriptions table
  const { data: internalSub } = await supabase
    .from("stripe_subscriptions")
    .select("id")
    .eq("stripe_subscription_id", subscriptionId)
    .maybeSingle();
  const stripeSubscriptionRef = internalSub?.id || null;

  // Insert payment record with all Stripe references (idempotent)
  // FIX: PostgREST upsert doesn't work with partial unique indexes (WHERE ... IS NOT NULL)
  // Use manual check-then-insert pattern instead
  const { data: existingInvoicePayment } = await supabase
    .from("stripe_payments")
    .select("id")
    .eq("stripe_invoice_id", invoice.id)
    .maybeSingle();

  if (!existingInvoicePayment) {
    await supabase.from("stripe_payments").insert({
      user_id: supabase_user_id || null,
      pro_account_id: supabase_pro_account_id || null,
      stripe_subscription_ref: stripeSubscriptionRef, // FIX: Now populated with internal FK
      stripe_invoice_id: invoice.id,
      stripe_payment_intent_id: invoice.payment_intent as string,
      stripe_charge_id: invoice.charge as string || null,
      stripe_subscription_id: subscriptionId,
      stripe_customer_id: invoice.customer as string,
      payment_type: "subscription_payment",
      amount_cents: invoice.amount_paid,
      amount_received_cents: invoice.amount_paid,
      currency: invoice.currency,
      status: "succeeded",
      invoice_number: invoice.number,
      invoice_pdf_url: invoice.invoice_pdf,
      hosted_invoice_url: invoice.hosted_invoice_url,
      period_start: new Date(invoice.period_start * 1000).toISOString(),
      period_end: new Date(invoice.period_end * 1000).toISOString(),
      metadata: metadata,
      paid_at: new Date().toISOString(),
    });
    console.log(
      `Created payment record for invoice ${invoice.id} (subscription_ref: ${
        stripeSubscriptionRef || "null"
      })`,
    );
  } else {
    console.log(
      `Payment record already exists for invoice ${invoice.id}, skipping insert`,
    );
  }

  // Update subscription period in stripe_subscriptions with all Stripe references
  await supabase.from("stripe_subscriptions")
    .update({
      current_period_start: new Date(subscription.current_period_start * 1000)
        .toISOString(),
      current_period_end: periodEnd,
      stripe_price_id: stripePriceId,
      status: subscription.status,
      updated_at: new Date().toISOString(),
    })
    .eq("stripe_subscription_id", subscriptionId);

  // Renew Pro licenses if this is a pro_license_monthly subscription
  // AUDIT FIX: Use atomic RPC for all operations (bugfix_016)
  if (supabase_pro_account_id && price_type === "pro_license_monthly") {
    console.log(
      `🔄 === PRO LICENSE RENEWAL START for account ${supabase_pro_account_id} ===`,
    );
    console.log(`   Subscription: ${subscriptionId}`);
    console.log(`   New period end: ${periodEnd}`);

    // AUDIT FIX: Call atomic RPC process_invoice_paid_pro for all operations
    await processInvoicePaidPro(
      supabase,
      supabase_pro_account_id,
      subscriptionId,
      periodEnd,
      stripeSubscriptionItemId,
      stripePriceId,
    );

    console.log(
      `🔄 === PRO LICENSE RENEWAL COMPLETE for account ${supabase_pro_account_id} ===`,
    );
  }

  // NOTE: Individual subscription renewal is handled automatically by the SQL trigger
  // sync_user_subscription_cache() in stripe_integration.sql when stripe_subscriptions is updated above.
  // NO direct update to users.subscription_type needed here to avoid race conditions.
  if (supabase_user_id && price_type?.includes("individual")) {
    console.log(
      `ℹ️ Individual subscription ${subscriptionId} renewed - users.subscription_type synced via trigger`,
    );
  }
}

/**
 * Handle upcoming invoice before final payment.
 *
 * Goal:
 * - Ensure Stripe subscription quantity reflects billable monthly licenses
 *   BEFORE Stripe charges the renewal invoice.
 *
 * This prevents overbilling when licenses were canceled/unlinked before renewal.
 */
async function handleInvoiceUpcoming(
  supabase: any,
  stripe: Stripe,
  invoice: Stripe.Invoice,
) {
  const subscriptionId = invoice.subscription as string;
  if (!subscriptionId) {
    return;
  }

  // Retrieve latest subscription state from Stripe.
  const subscription = await stripe.subscriptions.retrieve(subscriptionId);
  const metadata = subscription.metadata || {};
  const priceType = metadata.price_type;

  // Only relevant for Pro monthly license subscriptions.
  if (priceType !== "pro_license_monthly") {
    return;
  }

  const subscriptionItem = subscription.items?.data?.[0];
  if (!subscriptionItem?.id) {
    console.warn(
      `⚠️ invoice.upcoming ${invoice.id}: missing subscription item for ${subscriptionId}`,
    );
    return;
  }

  // Resolve pro account id (metadata first, DB fallback for legacy rows).
  let proAccountId = metadata.supabase_pro_account_id || null;
  if (!proAccountId) {
    const { data: subRow } = await supabase
      .from("stripe_subscriptions")
      .select("pro_account_id")
      .eq("stripe_subscription_id", subscriptionId)
      .maybeSingle();
    proAccountId = subRow?.pro_account_id || null;
  }

  if (!proAccountId) {
    console.warn(
      `⚠️ invoice.upcoming ${invoice.id}: no pro_account_id for subscription ${subscriptionId}`,
    );
    return;
  }

  // Renewal boundary: licenses unlinked/canceled effective at or before this date
  // must not be billed for the upcoming period.
  const renewalStartIso = invoice.period_start
    ? new Date(invoice.period_start * 1000).toISOString()
    : new Date().toISOString();

  // Load monthly licenses and compute billable count in JS for robust filtering.
  const { data: monthlyLicenses, error: licensesError } = await supabase
    .from("licenses")
    .select("id, status, unlink_effective_at")
    .eq("pro_account_id", proAccountId)
    .eq("is_lifetime", false);

  if (licensesError) {
    console.error(
      `❌ invoice.upcoming ${invoice.id}: failed to load licenses for ${proAccountId}`,
      licensesError,
    );
    return;
  }

  // Source of truth: total remaining monthly licenses for the Pro account.
  // We keep legacy exclusions for rows that are explicitly not billable.
  const targetQuantity = (monthlyLicenses || []).filter((l: any) => {
    if (!l) return false;
    // Backward compatibility with historical "canceled" rows.
    if (l.status === "canceled") return false;
    // If unlink becomes effective at/before renewal start, do not bill it.
    if (l.unlink_effective_at && l.unlink_effective_at <= renewalStartIso) {
      return false;
    }
    return true;
  }).length;

  const currentQuantity = subscriptionItem.quantity || 0;
  if (targetQuantity === currentQuantity) {
    console.log(
      `ℹ️ invoice.upcoming ${invoice.id}: quantity already aligned (${currentQuantity})`,
    );
    return;
  }

  // Stripe subscription quantities should stay >= 1.
  // If no billable licenses remain, keep quantity unchanged and rely on existing
  // cancellation lifecycle (status='canceled' + renewal cleanup) to terminate flow.
  if (targetQuantity <= 0) {
    console.warn(
      `⚠️ invoice.upcoming ${invoice.id}: computed quantity=0 for ${subscriptionId}, skipping Stripe update`,
    );
    return;
  }

  // Update Stripe before invoice finalization so renewal charges the correct quantity.
  await stripe.subscriptions.update(subscriptionId, {
    items: [{
      id: subscriptionItem.id,
      quantity: targetQuantity,
    }],
    proration_behavior: "none",
    metadata: {
      ...metadata,
      quantity: targetQuantity.toString(),
      quantity_synced_at: new Date().toISOString(),
    },
  });

  // Keep DB cache aligned with Stripe quantity.
  const unitPrice = subscriptionItem?.price?.unit_amount || null;
  const totalAmountCents = unitPrice !== null
    ? unitPrice * targetQuantity
    : null;

  await supabase.from("stripe_subscriptions")
    .update({
      quantity: targetQuantity,
      unit_amount_cents: totalAmountCents,
      updated_at: new Date().toISOString(),
    })
    .eq("stripe_subscription_id", subscriptionId);

  console.log(
    `✅ invoice.upcoming ${invoice.id}: synced subscription ${subscriptionId} quantity ${currentQuantity} -> ${targetQuantity}`,
  );
}

/**
 * Handle failed invoice payment
 * Per specs:
 * - Individual: EXPIRED immediately on first failure
 * - Pro: Suspend monthly licenses only, lifetime unaffected
 */
async function handleInvoicePaymentFailed(
  supabase: any,
  invoice: Stripe.Invoice,
) {
  console.log(`❌ Invoice payment failed: ${invoice.id}`);

  // Get subscription metadata to determine type
  const subscriptionId = invoice.subscription as string;
  let metadata: Record<string, string> = {};
  let stripeSubscriptionRef: string | null = null; // FIX: Track internal subscription ref

  if (subscriptionId) {
    const { data: subData } = await supabase
      .from("stripe_subscriptions")
      .select("id, metadata, user_id, pro_account_id, subscription_type") // FIX: Also select 'id' for ref
      .eq("stripe_subscription_id", subscriptionId)
      .single();

    if (subData) {
      metadata = subData.metadata || {};
      stripeSubscriptionRef = subData.id || null; // FIX: Get internal ref
    }
  }

  const { supabase_user_id, supabase_pro_account_id, price_type } = metadata;

  // Record the failed payment (idempotent)
  // FIX: PostgREST upsert doesn't work with partial unique indexes
  const { data: existingFailedPayment } = await supabase
    .from("stripe_payments")
    .select("id")
    .eq("stripe_invoice_id", invoice.id)
    .maybeSingle();

  if (!existingFailedPayment) {
    await supabase.from("stripe_payments").insert({
      user_id: supabase_user_id || null,
      pro_account_id: supabase_pro_account_id || null,
      stripe_subscription_ref: stripeSubscriptionRef, // FIX: Now populated with internal FK
      stripe_invoice_id: invoice.id,
      stripe_customer_id: invoice.customer as string,
      payment_type: "subscription_payment",
      amount_cents: invoice.amount_due,
      currency: invoice.currency,
      status: "failed",
      failure_message: "Payment failed",
      invoice_number: invoice.number,
      period_start: new Date(invoice.period_start * 1000).toISOString(),
      period_end: new Date(invoice.period_end * 1000).toISOString(),
    });
    console.log(
      `Created failed payment record for invoice ${invoice.id} (subscription_ref: ${
        stripeSubscriptionRef || "null"
      })`,
    );
  } else {
    console.log(
      `Failed payment record already exists for invoice ${invoice.id}, skipping insert`,
    );
  }

  // Update subscription status
  if (subscriptionId) {
    await supabase.from("stripe_subscriptions")
      .update({
        status: "past_due",
        updated_at: new Date().toISOString(),
      })
      .eq("stripe_subscription_id", subscriptionId);
  }

  // Handle Individual subscription failure → EXPIRED immediately
  // DESIGN DECISION: Individual users lose access immediately on payment failure.
  // Unlike Pro accounts (which get a grace period with 'suspended' status),
  // Individual users are immediately set to EXPIRED because:
  // 1. No collective business impact (only the user is affected)
  // 2. Simpler recovery path (just subscribe again)
  // 3. Prevents extended unpaid usage
  //
  // Note: This direct update INTENTIONALLY overrides the trigger's "keep current type"
  // behavior for past_due status. stripe_subscriptions stays at past_due for Stripe sync,
  // but users.subscription_type becomes EXPIRED for app access control.
  if (supabase_user_id && price_type?.includes("individual")) {
    console.log(
      `⚠️ Individual payment failed - setting user ${supabase_user_id} to EXPIRED`,
    );
    console.log(
      `   stripe_subscriptions.status = past_due, users.subscription_type = EXPIRED`,
    );
    await supabase.from("users")
      .update({
        subscription_type: "EXPIRED",
        updated_at: new Date().toISOString(),
      })
      .eq("id", supabase_user_id);
  }

  // Handle Pro license payment failure → Suspend monthly licenses only
  // FIX: Use atomic RPC to ensure consistency
  if (supabase_pro_account_id && price_type === "pro_license_monthly") {
    console.log(
      `⚠️ Pro payment failed - processing via atomic RPC for account ${supabase_pro_account_id}`,
    );

    try {
      // Call atomic RPC for Pro payment failure
      const { data, error } = await supabase.rpc(
        "process_invoice_payment_failed_pro",
        {
          p_stripe_subscription_id: subscriptionId,
          p_pro_account_id: supabase_pro_account_id,
        },
      );

      if (error) {
        console.error(
          `❌ Error calling process_invoice_payment_failed_pro RPC:`,
          error,
        );
        console.log(`⚠️ Falling back to legacy processing...`);
        await handleInvoicePaymentFailedProLegacy(
          supabase,
          supabase_pro_account_id,
        );
      } else if (data?.success) {
        console.log(`✅ Pro payment failure processed via RPC:`);
        console.log(`   - Suspended licenses: ${data.suspended_count || 0}`);
        console.log(`   - Affected users: ${data.affected_users || 0}`);
      } else {
        console.error(
          `❌ process_invoice_payment_failed_pro returned failure:`,
          data?.error,
        );
        await handleInvoicePaymentFailedProLegacy(
          supabase,
          supabase_pro_account_id,
        );
      }
    } catch (err) {
      console.error(
        `❌ Exception calling process_invoice_payment_failed_pro:`,
        err,
      );
      await handleInvoicePaymentFailedProLegacy(
        supabase,
        supabase_pro_account_id,
      );
    }
  }
}

/**
 * Legacy fallback for Pro payment failure handling
 * Used when the RPC function is not yet deployed
 */
async function handleInvoicePaymentFailedProLegacy(
  supabase: any,
  proAccountId: string,
) {
  console.log(`⚠️ Using legacy processing for Pro payment failure...`);

  // Suspend only monthly licenses (is_lifetime = false)
  await supabase.from("licenses")
    .update({
      status: "suspended",
      updated_at: new Date().toISOString(),
    })
    .eq("pro_account_id", proAccountId)
    .eq("is_lifetime", false)
    .in("status", ["active", "available"]);

  // Update pro_account status to suspended
  await supabase.from("pro_accounts")
    .update({
      status: "suspended",
      updated_at: new Date().toISOString(),
    })
    .eq("id", proAccountId);

  // Update affected users - but check for other active licenses first
  const { data: affectedLicenses } = await supabase
    .from("licenses")
    .select("linked_account_id")
    .eq("pro_account_id", proAccountId)
    .eq("status", "suspended")
    .eq("is_lifetime", false)
    .not("linked_account_id", "is", null);

  if (affectedLicenses && affectedLicenses.length > 0) {
    const userIds = [
      ...new Set(
        affectedLicenses.map((l: any) => l.linked_account_id).filter(Boolean),
      ),
    ];
    console.log(
      `📋 Legacy: Checking ${userIds.length} affected users for other active licenses...`,
    );

    for (const userId of userIds) {
      const { data: otherActiveLicenses } = await supabase
        .from("licenses")
        .select("id, pro_account_id, is_lifetime")
        .eq("linked_account_id", userId)
        .eq("status", "active")
        .limit(1);

      if (otherActiveLicenses && otherActiveLicenses.length > 0) {
        console.log(
          `ℹ️ User ${userId} has another active license - keeping access`,
        );
      } else {
        await supabase.from("users").update({
          subscription_expires_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
        }).eq("id", userId);
        console.log(
          `⚠️ User ${userId} has no other active licenses - access expired`,
        );
      }
    }
  }
}

/**
 * Handle subscription creation/update
 *
 * ARBRE 1 INDIVIDUEL:
 * - individual_monthly: Creates recurring subscription, user becomes PREMIUM
 * - Syncs cancel_at_period_end for voluntary cancellation
 *
 * ARBRE 2 PRO:
 * - pro_license_monthly: Creates licenses in the pool
 */
async function handleSubscriptionUpdate(
  supabase: any,
  subscription: Stripe.Subscription,
) {
  const metadata = subscription.metadata;
  const {
    supabase_user_id,
    supabase_pro_account_id,
    price_type,
    product_id,
    quantity,
  } = metadata;

  console.log(
    `Subscription update: ${subscription.id}, status: ${subscription.status}`,
  );

  // ARBRE 1 INDIVIDUEL - Validation: User must not be LICENSED for new Individual subscriptions
  if (
    supabase_user_id && price_type?.includes("individual") &&
    subscription.status === "active"
  ) {
    const { data: user } = await supabase
      .from("users")
      .select("subscription_type")
      .eq("id", supabase_user_id)
      .single();

    // Only block if this is a NEW subscription creation (not an update to existing)
    const { data: existingUserSub } = await supabase
      .from("stripe_subscriptions")
      .select("id")
      .eq("stripe_subscription_id", subscription.id)
      .single();

    const currentType = user?.subscription_type;

    if (!existingUserSub) {
      // NEW subscription - apply all validation rules

      // Rule 1: LICENSED cannot create Individual subscription
      if (currentType === "LICENSED") {
        console.error(
          `❌ BLOCKED: User ${supabase_user_id} is LICENSED and cannot create Individual subscription`,
        );
        console.error(
          `   Subscription ${subscription.id} will NOT be synced. Manual intervention required.`,
        );
        return;
      }

      // Rule 2: LIFETIME cannot create any subscription
      if (currentType === "LIFETIME") {
        console.error(
          `❌ BLOCKED: User ${supabase_user_id} already has LIFETIME`,
        );
        console.error(`   Subscription ${subscription.id} will NOT be synced.`);
        return;
      }

      // Rule 3: PREMIUM cannot create another PREMIUM (double billing)
      if (currentType === "PREMIUM" && price_type === "individual_monthly") {
        console.error(
          `❌ BLOCKED: User ${supabase_user_id} already has active PREMIUM`,
        );
        console.error(
          `   Duplicate subscription ${subscription.id} will NOT be synced.`,
        );
        return;
      }

      console.log(
        `✅ User ${supabase_user_id} validated for new ${price_type} subscription (current: ${currentType})`,
      );
    }
  }

  // Check if subscription exists
  // FIX BUG: Use .maybeSingle() instead of .single() to avoid 406 error
  // when the subscription doesn't exist yet. .single() throws 406 "Not Acceptable"
  // when 0 rows are returned, while .maybeSingle() returns null gracefully.
  const { data: existing } = await supabase
    .from("stripe_subscriptions")
    .select("id")
    .eq("stripe_subscription_id", subscription.id)
    .maybeSingle();

  // Extract subscription item and price info
  const subscriptionItem = subscription.items.data[0];
  const stripePriceId = subscriptionItem?.price?.id || null;
  const stripeSubscriptionItemId = subscriptionItem?.id || null;
  const metadataQuantity = parseInt(quantity || "1", 10);
  const stripeQuantity = (typeof subscriptionItem?.quantity === "number" &&
      subscriptionItem.quantity > 0)
    ? subscriptionItem.quantity
    : (metadataQuantity > 0 ? metadataQuantity : 1);
  const resolvedSubscriptionType = price_type || "individual_monthly";
  const stripeUnitAmountCents = subscriptionItem?.price?.unit_amount || null;
  const storedAmountCents =
    (resolvedSubscriptionType === "pro_license_monthly" &&
        stripeUnitAmountCents !== null)
      ? stripeUnitAmountCents * stripeQuantity
      : stripeUnitAmountCents;

  // FIX BUG-003: In newer Stripe API versions (2025+), current_period_start/end
  // are in subscription.items.data[0], not at the subscription level.
  // Fall back to item-level values if subscription-level values are undefined.
  const periodStart = subscription.current_period_start ||
    (subscriptionItem as any)?.current_period_start ||
    null;
  const periodEnd = subscription.current_period_end ||
    (subscriptionItem as any)?.current_period_end ||
    null;

  const subscriptionData = {
    user_id: supabase_user_id || null,
    pro_account_id: supabase_pro_account_id || null,
    stripe_subscription_id: subscription.id,
    stripe_customer_id: subscription.customer as string,
    stripe_product_id: product_id || subscriptionItem?.price?.product || null,
    stripe_price_id: stripePriceId,
    subscription_type: resolvedSubscriptionType,
    status: subscription.status,
    quantity: stripeQuantity,
    currency: subscription.currency,
    unit_amount_cents: storedAmountCents,
    current_period_start: periodStart
      ? new Date(periodStart * 1000).toISOString()
      : null,
    current_period_end: periodEnd
      ? new Date(periodEnd * 1000).toISOString()
      : null,
    cancel_at_period_end: subscription.cancel_at_period_end,
    // FIX BUG: When cancel_at_period_end=true, Stripe doesn't set canceled_at until subscription actually ends
    // We use the current timestamp as the "cancellation request date" in this case
    canceled_at: subscription.canceled_at
      ? new Date(subscription.canceled_at * 1000).toISOString()
      : subscription.cancel_at_period_end
      ? new Date().toISOString() // Date of cancellation request
      : null,
    // FIX BUG: Use cancel_at (scheduled end date) when cancel_at_period_end=true
    // subscription.cancel_at contains the timestamp when the subscription will be canceled
    ended_at: subscription.ended_at
      ? new Date(subscription.ended_at * 1000).toISOString()
      : (subscription as any).cancel_at
      ? new Date((subscription as any).cancel_at * 1000).toISOString()
      : null,
    metadata: metadata,
    updated_at: new Date().toISOString(),
  };

  if (existing) {
    // Update existing
    await supabase.from("stripe_subscriptions")
      .update(subscriptionData)
      .eq("id", existing.id);
  } else {
    // Insert new
    await supabase.from("stripe_subscriptions").insert({
      ...subscriptionData,
      created_at: new Date().toISOString(),
    });
  }

  // NOTE: users.subscription_type is synchronized automatically by SQL trigger
  // sync_user_subscription_cache() in stripe_integration.sql - NO direct update needed.
  // The trigger fires on INSERT/UPDATE of stripe_subscriptions above.
  if (supabase_user_id && price_type?.includes("individual")) {
    console.log(
      `ℹ️ Subscription ${subscription.id} updated - users.subscription_type synced via trigger`,
    );

    // ARBRE 1 INDIVIDUEL: Handle voluntary cancellation (cancel_at_period_end)
    // When user cancels, they keep PREMIUM access until subscription_expires_at
    if (subscription.cancel_at_period_end && periodEnd) {
      console.log(
        `📅 User ${supabase_user_id} scheduled cancellation at period end: ${
          new Date(periodEnd * 1000).toISOString()
        }`,
      );
      // The trigger already handles subscription_expires_at via current_period_end
      // We just log this for visibility
    }
  }

  // ARBRE 2 PRO: Create licenses if Pro monthly and subscription is active
  if (supabase_pro_account_id && price_type === "pro_license_monthly") {
    // ARBRE 2 PRO BOUCLE 4: Validate pro_account is not suspended before creating licenses
    const { data: proAccountCheck } = await supabase
      .from("pro_accounts")
      .select("status")
      .eq("id", supabase_pro_account_id)
      .single();

    if (proAccountCheck?.status === "suspended") {
      console.error(
        `❌ BLOCKED: Pro account ${supabase_pro_account_id} is suspended - cannot create licenses`,
      );
      console.error(
        `   Subscription ${subscription.id} created but licenses NOT created until payment issue resolved`,
      );
      return;
    }

    if (subscription.status === "active") {
      // BUGFIX: Get quantity from Stripe subscription items, not just metadata
      // When adding licenses to an existing subscription, metadata.quantity
      // only reflects the NEW licenses being added, but items.data[0].quantity
      // reflects the TOTAL licenses on the subscription
      console.log(
        `📊 Subscription quantity check: Stripe items.quantity=${stripeQuantity}, metadata.quantity=${metadataQuantity}`,
      );

      const subPeriodEnd = subscription.current_period_end ||
        (subscriptionItem as any)?.current_period_end ||
        null;
      const periodEndStr = subPeriodEnd
        ? new Date(subPeriodEnd * 1000).toISOString()
        : new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(); // Fallback 30 days

      // BUGFIX: Count existing licenses for this pro_account (not just this subscription)
      // This handles the case where licenses were created by payment_intent.succeeded
      // before this subscription.updated event, with stripe_payment_intent_id set but
      // stripe_subscription_id not yet set
      //
      // We count ALL active/available licenses (not canceled) for the pro_account
      // because that's the true license pool that should match Stripe quantity
      const { count: existingCount } = await supabase
        .from("licenses")
        .select("id", { count: "exact", head: true })
        .eq("pro_account_id", supabase_pro_account_id)
        .eq("is_lifetime", false) // Only count monthly licenses
        .not("status", "eq", "canceled");

      const currentLicenseCount = existingCount || 0;
      const targetLicenseCount = stripeQuantity;
      const licensesToCreate = targetLicenseCount - currentLicenseCount;

      console.log(
        `📊 License count: existing=${currentLicenseCount}, target=${targetLicenseCount}, toCreate=${licensesToCreate}`,
      );

      // ARBRE 2 PRO BOUCLE 3: Get stripe_subscription_ref (internal UUID) for FK link
      // This ensures licenses are properly linked to stripe_subscriptions via FK
      const { data: stripeSubRecord } = await supabase
        .from("stripe_subscriptions")
        .select("id")
        .eq("stripe_subscription_id", subscription.id)
        .single();

      const stripeSubscriptionRef = stripeSubRecord?.id || null;
      const now = new Date().toISOString();

      // BUGFIX: Update existing licenses that don't have stripe_subscription_id yet
      // This handles licenses created by payment_intent.succeeded before this webhook
      const { data: licensesWithoutSubId } = await supabase
        .from("licenses")
        .select("id")
        .eq("pro_account_id", supabase_pro_account_id)
        .eq("is_lifetime", false)
        .is("stripe_subscription_id", null)
        .not("status", "eq", "canceled");

      if (licensesWithoutSubId && licensesWithoutSubId.length > 0) {
        const licenseIdsToUpdate = licensesWithoutSubId.map((l: any) => l.id);
        await supabase.from("licenses")
          .update({
            stripe_subscription_id: subscription.id,
            stripe_subscription_ref: stripeSubscriptionRef,
            stripe_subscription_item_id: stripeSubscriptionItemId,
            stripe_price_id: stripePriceId,
            end_date: periodEndStr,
            updated_at: now,
          })
          .in("id", licenseIdsToUpdate);

        console.log(
          `🔗 Linked ${licensesWithoutSubId.length} existing licenses to subscription ${subscription.id}`,
        );
      }

      // Only create licenses if we need more than we have
      if (licensesToCreate > 0) {
        const licenses = Array.from({ length: licensesToCreate }, () => ({
          pro_account_id: supabase_pro_account_id,
          is_lifetime: false,
          status: "available", // FIX: Use 'available' not 'active' until assigned
          stripe_subscription_id: subscription.id, // Stripe's external ID
          stripe_subscription_ref: stripeSubscriptionRef, // Internal FK to stripe_subscriptions.id
          stripe_subscription_item_id: stripeSubscriptionItemId,
          stripe_price_id: stripePriceId,
          price_monthly_ht: 5.00, // 6.00 TTC / 1.20 = 5.00 HT
          vat_rate: 0.20,
          start_date: now,
          end_date: periodEndStr, // Set end_date to subscription period end
          billing_starts_at: now,
          created_at: now,
          updated_at: now,
        }));
        await supabase.from("licenses").insert(licenses);
        console.log(
          `✅ Created ${licensesToCreate} monthly licenses for pro account ${supabase_pro_account_id} (status: available, stripe_subscription_ref: ${
            stripeSubscriptionRef || "null"
          }), end_date: ${periodEndStr}`,
        );
        console.log(
          `   Total licenses now: ${currentLicenseCount + licensesToCreate}`,
        );

        // Also update pro_account status and link subscription
        const { data: proAccount } = await supabase
          .from("pro_accounts")
          .select("status, stripe_subscription_id, billing_anchor_day")
          .eq("id", supabase_pro_account_id)
          .single();

        if (proAccount) {
          const proUpdateData: Record<string, any> = {
            updated_at: now,
          };

          // Set status to 'active' if was trial or expired
          if (
            proAccount.status === "trial" || proAccount.status === "expired"
          ) {
            proUpdateData.status = "active";
            console.log(
              `✅ Pro account ${supabase_pro_account_id} activating (was: ${proAccount.status})`,
            );
          }

          // ARBRE 2 PRO BOUCLE 3: Set stripe_subscription_id on pro_account
          // This links the pro_account to its main Stripe subscription
          if (
            !proAccount.stripe_subscription_id ||
            proAccount.stripe_subscription_id !== subscription.id
          ) {
            proUpdateData.stripe_subscription_id = subscription.id;
            console.log(
              `🔗 Linking stripe_subscription_id ${subscription.id} to pro account ${supabase_pro_account_id}`,
            );
          }

          // Set billing_anchor_day if not already set
          if (!proAccount.billing_anchor_day) {
            const anchorDay = Math.min(new Date().getUTCDate(), 28);
            proUpdateData.billing_anchor_day = anchorDay;
            console.log(
              `📅 Setting billing_anchor_day to ${anchorDay} for pro account ${supabase_pro_account_id}`,
            );
          }

          await supabase.from("pro_accounts")
            .update(proUpdateData)
            .eq("id", supabase_pro_account_id);
        }
      } else if (licensesToCreate === 0) {
        console.log(
          `ℹ️ License count already matches subscription quantity (${targetLicenseCount}), no new licenses needed`,
        );
      } else {
        // licensesToCreate < 0 means we have more licenses than subscription quantity
        // This could happen if licenses were manually added, or if subscription was downgraded
        // We log this but don't delete licenses automatically (that requires explicit unlink)
        console.warn(
          `⚠️ License count (${currentLicenseCount}) exceeds subscription quantity (${targetLicenseCount})`,
        );
        console.warn(
          `   This may indicate manual licenses or a downgrade - no automatic deletion`,
        );
      }
    }
  }
}

/**
 * Handle subscription schedule updates.
 *
 * Purpose:
 * - Keep local stripe_subscriptions cache aligned when Stripe adjusts phases.
 * - Source of truth remains Stripe Subscription fields for period start/end.
 *
 * Important:
 * - This does NOT renew licenses and does NOT modify canceled licenses.
 * - Renewal/cancellation side effects stay in invoice/subscription handlers.
 */
async function handleSubscriptionScheduleUpdated(
  supabase: any,
  stripe: Stripe,
  schedule: Stripe.SubscriptionSchedule,
) {
  const subscriptionId = typeof schedule.subscription === "string"
    ? schedule.subscription
    : (schedule.subscription as any)?.id || null;

  if (!subscriptionId) {
    console.log(
      `ℹ️ subscription_schedule.updated ${schedule.id}: no linked subscription, skipping`,
    );
    return;
  }

  const subscription = await stripe.subscriptions.retrieve(subscriptionId, {
    expand: ["items.data.price"],
  });
  const subscriptionItem = subscription.items?.data?.[0];
  const metadata = subscription.metadata || {};
  const metadataQuantity = parseInt(metadata.quantity || "1", 10);
  const stripeQuantity = (typeof subscriptionItem?.quantity === "number" &&
      subscriptionItem.quantity > 0)
    ? subscriptionItem.quantity
    : (metadataQuantity > 0 ? metadataQuantity : 1);

  const periodStart = subscription.current_period_start ||
    (subscriptionItem as any)?.current_period_start ||
    null;
  const periodEnd = subscription.current_period_end ||
    (subscriptionItem as any)?.current_period_end ||
    null;
  const nowIso = new Date().toISOString();

  const { data: rows, error } = await supabase
    .from("stripe_subscriptions")
    .update({
      stripe_price_id: subscriptionItem?.price?.id || null,
      status: subscription.status,
      quantity: stripeQuantity,
      current_period_start: periodStart
        ? new Date(periodStart * 1000).toISOString()
        : null,
      current_period_end: periodEnd
        ? new Date(periodEnd * 1000).toISOString()
        : null,
      cancel_at_period_end: subscription.cancel_at_period_end,
      canceled_at: subscription.canceled_at
        ? new Date(subscription.canceled_at * 1000).toISOString()
        : subscription.cancel_at_period_end
        ? nowIso
        : null,
      ended_at: subscription.ended_at
        ? new Date(subscription.ended_at * 1000).toISOString()
        : (subscription as any).cancel_at
        ? new Date((subscription as any).cancel_at * 1000).toISOString()
        : null,
      metadata,
      updated_at: nowIso,
    })
    .eq("stripe_subscription_id", subscriptionId)
    .select("id");

  if (error) {
    throw error;
  }

  if (!rows || rows.length === 0) {
    console.log(
      `ℹ️ subscription_schedule.updated ${schedule.id}: no local stripe_subscriptions row for ${subscriptionId}`,
    );
    return;
  }

  console.log(
    `✅ subscription_schedule.updated ${schedule.id}: synced subscription ${subscriptionId} period_end=${
      periodEnd ? new Date(periodEnd * 1000).toISOString() : "null"
    }`,
  );
}

/**
 * Handle subscription cancellation/deletion
 *
 * ARBRE 4 - RÉSILIATION LICENCE:
 * ==============================
 * This is called when Stripe fires customer.subscription.deleted event.
 * This happens when:
 * 1. User explicitly cancels and period ends (cancel_at_period_end was true)
 * 2. Subscription is immediately canceled (rare, admin action)
 * 3. Too many payment failures (Stripe gives up)
 *
 * FOR INDIVIDUAL:
 * - The trigger sync_user_subscription_cache() handles the transition to EXPIRED
 *
 * FOR PRO (ARBRE 4 FIX):
 * - Uses RPC process_subscription_deleted() for atomic processing
 * - Licenses are marked as 'canceled'
 * - Affected users are set to EXPIRED (with multi-license check)
 * - This is the FINAL cleanup - there is no "next renewal" for deleted subscriptions
 */
async function handleSubscriptionDeleted(
  supabase: any,
  subscription: Stripe.Subscription,
) {
  const metadata = subscription.metadata;
  const isPro = metadata.supabase_pro_account_id &&
    metadata.price_type?.includes("pro_license");
  const isIndividual = metadata.supabase_user_id &&
    metadata.price_type?.includes("individual");

  console.log(`🔴 Subscription deleted: ${subscription.id}`);
  console.log(
    `   Type: ${isPro ? "Pro" : isIndividual ? "Individual" : "Unknown"}`,
  );

  // FIX: For Pro subscriptions, process licenses/users BEFORE updating stripe_subscriptions
  // This ensures that if the RPC fails, the subscription stays in a consistent state
  // and Stripe will retry the webhook.
  if (isPro) {
    // ARBRE 4 FIX: Process Pro subscription deletion via atomic RPC FIRST
    // This handles both license status AND user subscription_type updates
    await processSubscriptionDeleted(
      supabase,
      subscription.id,
      metadata.supabase_pro_account_id,
    );
    await triggerExpiredLicenseProcessing(
      metadata.supabase_pro_account_id || null,
    );
  }

  // Update stripe_subscriptions (triggers sync_user_subscription_cache for Individual)
  await supabase.from("stripe_subscriptions")
    .update({
      status: "canceled",
      canceled_at: new Date().toISOString(),
      ended_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    })
    .eq("stripe_subscription_id", subscription.id);

  // NOTE: users.subscription_type is synchronized automatically by SQL trigger
  // sync_user_subscription_cache() in stripe_integration.sql - NO direct update needed.
  // The trigger fires on UPDATE of stripe_subscriptions above (status = 'canceled').
  if (isIndividual) {
    console.log(
      `ℹ️ Subscription ${subscription.id} canceled - users.subscription_type synced via trigger`,
    );
  }
}

/**
 * Process Pro subscription deletion: cancel licenses AND update users
 *
 * ARBRE 4 - RÉSILIATION LICENCE FIX:
 * ==================================
 * Uses the SQL RPC function process_subscription_deleted() for atomicity.
 * The RPC handles:
 * - Phase 1: Mark all licenses for this subscription as 'canceled'
 * - Phase 2: Update affected users to EXPIRED (with multi-license check)
 * - Phase 3: Update pro_account status if all licenses are canceled
 *
 * This is different from processProRenewal() which handles:
 * - Licenses with unlink_effective_at <= NOW()
 * - Lifetime licenses returned to pool
 *
 * Here, the subscription is DELETED - there is no next period, no renewal.
 * Users must lose access NOW (unless they have other active licenses).
 */
async function processSubscriptionDeleted(
  supabase: any,
  stripeSubscriptionId: string,
  proAccountId: string,
) {
  console.log(`🔴 Processing Pro subscription deletion via RPC...`);
  console.log(`   Subscription: ${stripeSubscriptionId}`);
  console.log(`   Pro Account: ${proAccountId}`);

  try {
    // Call the atomic SQL function
    const { data, error } = await supabase.rpc("process_subscription_deleted", {
      p_stripe_subscription_id: stripeSubscriptionId,
      p_pro_account_id: proAccountId,
    });

    if (error) {
      console.error(
        `❌ Error calling process_subscription_deleted RPC:`,
        error,
      );
      // Fallback to legacy processing
      console.log(`⚠️ Falling back to legacy processing...`);
      await processSubscriptionDeletedLegacy(
        supabase,
        stripeSubscriptionId,
        proAccountId,
      );
      return;
    }

    if (data?.success) {
      console.log(`✅ Subscription deletion processed via RPC:`);
      console.log(`   - Canceled licenses: ${data.canceled_count || 0}`);
      console.log(`   - Affected users: ${data.affected_users || 0}`);
    } else {
      console.error(
        `❌ process_subscription_deleted returned failure:`,
        data?.error,
      );
      await processSubscriptionDeletedLegacy(
        supabase,
        stripeSubscriptionId,
        proAccountId,
      );
    }
  } catch (err) {
    console.error(`❌ Exception calling process_subscription_deleted:`, err);
    await processSubscriptionDeletedLegacy(
      supabase,
      stripeSubscriptionId,
      proAccountId,
    );
  }
}

/**
 * Legacy fallback for processSubscriptionDeleted
 * Used when the RPC function is not yet deployed
 *
 * ARBRE 4 AUDIT FIX: Improved safety with idempotent checks
 * - Check if licenses already canceled before processing
 * - Use WHERE clause to prevent double-updates
 * - Log warnings if already processed
 */
async function processSubscriptionDeletedLegacy(
  supabase: any,
  stripeSubscriptionId: string,
  proAccountId: string,
) {
  console.log(
    `🔴 [LEGACY] Processing subscription deletion for ${stripeSubscriptionId}`,
  );
  console.warn(
    `⚠️ [LEGACY] Using non-atomic fallback - RPC should be deployed for production`,
  );

  // Step 1: Get all licenses for this subscription that haven't been processed yet
  // AUDIT FIX: More specific filter to prevent double-processing
  const { data: licenses, error: fetchError } = await supabase
    .from("licenses")
    .select("id, linked_account_id, status")
    .eq("stripe_subscription_id", stripeSubscriptionId)
    .in("status", ["active", "suspended", "pending"]); // Only unprocessed statuses

  if (fetchError) {
    console.error(`❌ [LEGACY] Failed to fetch licenses:`, fetchError);
    return;
  }

  if (!licenses || licenses.length === 0) {
    console.log(
      `ℹ️ [LEGACY] No licenses to process (may be already processed)`,
    );
    return;
  }

  console.log(`📋 [LEGACY] Found ${licenses.length} licenses to cancel`);

  // Step 2: Collect affected users BEFORE changing license status
  const affectedUserIds: string[] = [];
  const licenseIds: string[] = [];
  for (const license of licenses) {
    licenseIds.push(license.id);
    if (license.linked_account_id) {
      affectedUserIds.push(license.linked_account_id);
    }
  }

  // Step 3: Mark licenses as canceled - using specific IDs for idempotence
  // AUDIT FIX: Use license IDs instead of subscription_id to prevent race conditions
  const { error: updateError } = await supabase.from("licenses")
    .update({ status: "canceled", updated_at: new Date().toISOString() })
    .in("id", licenseIds)
    .in("status", ["active", "suspended", "pending"]); // Extra safety

  if (updateError) {
    console.error(`❌ [LEGACY] Failed to update licenses:`, updateError);
    // Continue to user updates even if license update partially failed
  } else {
    console.log(`✅ [LEGACY] Marked ${licenses.length} licenses as canceled`);
  }

  // Step 4: Update affected users - with idempotent WHERE clause
  if (affectedUserIds.length > 0) {
    const uniqueUserIds = [...new Set(affectedUserIds)];
    console.log(
      `📋 [LEGACY] Checking ${uniqueUserIds.length} affected users...`,
    );

    for (const userId of uniqueUserIds) {
      // Check if user has any OTHER active license from ANY pro_account
      // AUDIT FIX: Query AFTER license status change to get accurate count
      const { data: otherActiveLicenses, error: checkError } = await supabase
        .from("licenses")
        .select("id")
        .eq("linked_account_id", userId)
        .eq("status", "active")
        .limit(1);

      if (checkError) {
        console.error(
          `❌ [LEGACY] Failed to check other licenses for user ${userId}:`,
          checkError,
        );
        continue; // Skip this user, don't set to EXPIRED without confirmation
      }

      if (otherActiveLicenses && otherActiveLicenses.length > 0) {
        console.log(
          `ℹ️ [LEGACY] User ${userId} has another active license - keeping LICENSED`,
        );
      } else {
        // AUDIT FIX: WHERE subscription_type = 'LICENSED' prevents double-updates
        const { error: userUpdateError, count } = await supabase.from("users")
          .update({
            subscription_type: "EXPIRED",
            subscription_expires_at: null,
            updated_at: new Date().toISOString(),
          })
          .eq("id", userId)
          .eq("subscription_type", "LICENSED");

        if (userUpdateError) {
          console.error(
            `❌ [LEGACY] Failed to update user ${userId}:`,
            userUpdateError,
          );
        } else {
          console.log(
            `⚠️ [LEGACY] User ${userId} set to EXPIRED (no other active licenses)`,
          );
        }
      }
    }
  }
}

/**
 * Record a blocked payment for audit trail
 * When a payment is received but cannot be activated (LICENSED/LIFETIME conflict),
 * we record it for manual refund processing.
 *
 * ⚠️ IMPORTANT: This creates an audit record for ops team to process refunds.
 * The payment succeeded in Stripe but the subscription was NOT activated.
 */
async function recordBlockedPayment(
  supabase: any,
  userId: string,
  paymentIntent: Stripe.PaymentIntent,
  reason: string,
) {
  // CRITICAL: Log with high visibility for ops alerting
  console.error(`🚨 ========== BLOCKED PAYMENT - REFUND REQUIRED ==========`);
  console.error(`🚨 Payment Intent: ${paymentIntent.id}`);
  console.error(`🚨 User ID: ${userId}`);
  console.error(
    `🚨 Amount: ${
      paymentIntent.amount / 100
    } ${paymentIntent.currency.toUpperCase()}`,
  );
  console.error(`🚨 Reason: ${reason}`);
  console.error(`🚨 Customer: ${paymentIntent.customer}`);
  console.error(`🚨 ========================================================`);

  const blockedAt = new Date().toISOString();

  // FIX: PostgREST upsert doesn't work with partial unique indexes
  // For blocked payments, we want to INSERT if new or UPDATE if exists
  const { data: existingBlockedPayment } = await supabase
    .from("stripe_payments")
    .select("id")
    .eq("stripe_payment_intent_id", paymentIntent.id)
    .maybeSingle();

  const blockedPaymentData = {
    user_id: userId,
    stripe_payment_intent_id: paymentIntent.id,
    stripe_customer_id: paymentIntent.customer as string,
    payment_type: "one_time_payment",
    amount_cents: paymentIntent.amount,
    amount_received_cents: paymentIntent.amount_received,
    currency: paymentIntent.currency,
    status: "succeeded", // Payment DID succeed in Stripe
    failure_message: reason, // But we blocked subscription activation
    metadata: {
      ...paymentIntent.metadata,
      blocked_reason: reason,
      blocked_at: blockedAt,
      requires_manual_refund: true,
      refund_status: "pending",
    },
    paid_at: blockedAt,
  };

  if (existingBlockedPayment) {
    await supabase.from("stripe_payments")
      .update(blockedPaymentData)
      .eq("id", existingBlockedPayment.id);
  } else {
    await supabase.from("stripe_payments").insert({
      ...blockedPaymentData,
      created_at: blockedAt,
    });
  }

  console.log(`✅ Blocked payment recorded in stripe_payments table`);
  console.log(
    `📋 To process refund, run: stripe refunds create --payment-intent ${paymentIntent.id}`,
  );
}

/**
 * Process Pro account renewal: handle canceled/unlinked licenses
 *
 * ARBRE 6 - RENOUVELLEMENT PRO:
 * ============================
 * Uses the SQL RPC function process_pro_renewal() for atomicity.
 * The RPC handles:
 * - Phase 1: Licenses with expired unlink_effective_at → process
 * - Phase 2: Licenses with status='canceled' → process
 * - Phase 3: Update affected users (with multi-license check)
 *
 * Processing per license type:
 * - Monthly canceled/unlinked licenses → DELETE
 * - Lifetime canceled/unlinked licenses → return to pool (available)
 * - Affected collaborators → EXPIRED (if no other active licenses)
 *
 * Benefits of RPC approach:
 * - Atomic: all-or-nothing transaction
 * - Idempotent: safe to call multiple times
 * - Handles company_links deactivation
 * - Proper unlink_effective_at checking (not status='unlinked' which doesn't exist)
 */
async function processProRenewal(supabase: any, proAccountId: string) {
  console.log(
    `🔄 Processing Pro renewal for account ${proAccountId} via RPC...`,
  );

  try {
    // Call the atomic SQL function
    const { data, error } = await supabase.rpc("process_pro_renewal", {
      p_pro_account_id: proAccountId,
    });

    if (error) {
      console.error(`❌ Error calling process_pro_renewal RPC:`, error);
      // Fallback to legacy processing if RPC fails (for backward compatibility)
      console.log(`⚠️ Falling back to legacy processing...`);
      await processProRenewalLegacy(supabase, proAccountId);
      return;
    }

    if (data?.success) {
      console.log(`✅ Pro renewal processed via RPC:`);
      console.log(`   - Deleted: ${data.deleted_count || 0} monthly licenses`);
      console.log(
        `   - Returned to pool: ${data.returned_count || 0} lifetime licenses`,
      );
      console.log(`   - Affected users: ${data.affected_users || 0}`);
    } else {
      console.error(`❌ process_pro_renewal returned failure:`, data?.error);
      // Fallback to legacy
      await processProRenewalLegacy(supabase, proAccountId);
    }
  } catch (err) {
    console.error(`❌ Exception calling process_pro_renewal:`, err);
    // Fallback to legacy
    await processProRenewalLegacy(supabase, proAccountId);
  }
}

/**
 * Legacy fallback for processProRenewal
 * Used when the RPC function is not yet deployed
 *
 * ARBRE 6 AUDIT FIX: Improved safety with idempotent checks
 * - Check if licenses already processed before processing
 * - Use WHERE clause to prevent double-updates
 * - Better error handling
 */
async function processProRenewalLegacy(supabase: any, proAccountId: string) {
  console.log(`🔄 [LEGACY] Processing Pro renewal for account ${proAccountId}`);
  console.warn(
    `⚠️ [LEGACY] Using non-atomic fallback - RPC should be deployed for production`,
  );

  // ARBRE 6 FIX: Query licenses by unlink_effective_at instead of non-existent 'unlinked' status
  // Also include 'canceled' status
  // We run two separate queries for reliability (complex .or() can be problematic)
  const now = new Date().toISOString();

  // Query 1: Get canceled licenses (that haven't been processed to 'available')
  const { data: canceledLicenses, error: canceledError } = await supabase
    .from("licenses")
    .select("id, linked_account_id, is_lifetime, status, unlink_effective_at")
    .eq("pro_account_id", proAccountId)
    .eq("status", "canceled");

  if (canceledError) {
    console.error(
      `❌ [LEGACY] Failed to fetch canceled licenses:`,
      canceledError,
    );
    // Don't return - try the other query
  }

  // Query 2: Get licenses with expired unlink requests (regardless of status, except 'available')
  // AUDIT FIX: Also exclude 'canceled' to prevent double-processing with Query 1
  const { data: unlinkExpiredLicenses, error: unlinkError } = await supabase
    .from("licenses")
    .select("id, linked_account_id, is_lifetime, status, unlink_effective_at")
    .eq("pro_account_id", proAccountId)
    .not("status", "in", '("available","canceled")')
    .not("unlink_effective_at", "is", null)
    .lte("unlink_effective_at", now);

  if (unlinkError) {
    console.error(
      `❌ [LEGACY] Failed to fetch unlink-expired licenses:`,
      unlinkError,
    );
  }

  // Merge and deduplicate
  const allLicenses = [
    ...(canceledLicenses || []),
    ...(unlinkExpiredLicenses || []),
  ];
  const seenIds = new Set<string>();
  const licensesToProcess = allLicenses.filter((license) => {
    if (seenIds.has(license.id)) return false;
    seenIds.add(license.id);
    return true;
  });

  if (!licensesToProcess || licensesToProcess.length === 0) {
    console.log(
      `ℹ️ [LEGACY] No canceled/unlinked licenses to process (may be already processed)`,
    );
    return;
  }

  console.log(
    `📋 [LEGACY] Found ${licensesToProcess.length} licenses to process`,
  );

  // Collect user IDs that will lose access
  const affectedUserIds: string[] = [];
  const processedLicenseIds: string[] = [];

  for (const license of licensesToProcess) {
    // Collect affected user BEFORE modifying license
    if (license.linked_account_id) {
      affectedUserIds.push(license.linked_account_id);

      // ARBRE 6 FIX: Deactivate company_link (idempotent with WHERE status='ACTIVE')
      const { error: linkError } = await supabase.from("company_links")
        .update({
          status: "INACTIVE",
          unlinked_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", license.linked_account_id)
        .eq("linked_pro_account_id", proAccountId)
        .eq("status", "ACTIVE"); // Idempotent: only affects ACTIVE links

      if (linkError) {
        console.error(
          `❌ [LEGACY] Failed to deactivate company_link for user ${license.linked_account_id}:`,
          linkError,
        );
        // Continue anyway - company_link deactivation is secondary
      }
    }

    if (license.is_lifetime) {
      // Lifetime license → return to pool (available), clear assignment
      // AUDIT FIX: Add WHERE status != 'available' for idempotence
      const { error: updateError } = await supabase.from("licenses")
        .update({
          status: "available",
          linked_account_id: null,
          linked_at: null,
          unlink_requested_at: null,
          unlink_effective_at: null,
          updated_at: new Date().toISOString(),
        })
        .eq("id", license.id)
        .not("status", "eq", "available"); // Idempotent

      if (updateError) {
        console.error(
          `❌ [LEGACY] Failed to return lifetime license ${license.id} to pool:`,
          updateError,
        );
      } else {
        console.log(
          `✅ [LEGACY] Lifetime license ${license.id} returned to pool`,
        );
        processedLicenseIds.push(license.id);
      }
    } else {
      // Monthly license → DELETE
      const { error: deleteError } = await supabase.from("licenses")
        .delete()
        .eq("id", license.id);

      if (deleteError) {
        console.error(
          `❌ [LEGACY] Failed to delete monthly license ${license.id}:`,
          deleteError,
        );
      } else {
        console.log(`🗑️ [LEGACY] Monthly license ${license.id} deleted`);
        processedLicenseIds.push(license.id);
      }
    }
  }

  // Update affected users to EXPIRED - BUT ONLY IF THEY DON'T HAVE OTHER ACTIVE LICENSES
  if (affectedUserIds.length > 0) {
    const uniqueUserIds = [...new Set(affectedUserIds)];
    console.log(
      `📋 [LEGACY] Checking license status for ${uniqueUserIds.length} affected users...`,
    );

    for (const userId of uniqueUserIds) {
      // Check if user has any OTHER active license from ANY pro_account
      // AUDIT FIX: Query AFTER license processing to get accurate count
      const { data: otherActiveLicenses, error: checkError } = await supabase
        .from("licenses")
        .select("id, pro_account_id")
        .eq("linked_account_id", userId)
        .eq("status", "active")
        .limit(1);

      if (checkError) {
        console.error(
          `❌ [LEGACY] Failed to check other licenses for user ${userId}:`,
          checkError,
        );
        continue; // Skip this user, don't set to EXPIRED without confirmation
      }

      if (otherActiveLicenses && otherActiveLicenses.length > 0) {
        console.log(
          `ℹ️ [LEGACY] User ${userId} has another active license (${
            otherActiveLicenses[0].id
          }) - keeping LICENSED status`,
        );
      } else {
        // AUDIT FIX: WHERE subscription_type = 'LICENSED' prevents double-updates
        const { error: userUpdateError } = await supabase.from("users")
          .update({
            subscription_type: "EXPIRED",
            subscription_expires_at: null,
            updated_at: new Date().toISOString(),
          })
          .eq("id", userId)
          .eq("subscription_type", "LICENSED"); // Idempotent: only affects LICENSED users

        if (userUpdateError) {
          console.error(
            `❌ [LEGACY] Failed to update user ${userId}:`,
            userUpdateError,
          );
        } else {
          console.log(
            `⚠️ [LEGACY] User ${userId} has no more active licenses - set to EXPIRED`,
          );
        }
      }
    }
  }

  console.log(
    `✅ [LEGACY] Pro renewal complete: processed ${processedLicenseIds.length} licenses`,
  );
}

/**
 * Process invoice paid for Pro accounts via atomic RPC
 *
 * AUDIT FIX (bugfix_016): All operations are now atomic in a single RPC call:
 * 1. Process canceled/unlinked licenses (delete monthly, return lifetime to pool)
 * 2. Reactivate suspended licenses
 * 3. Update license end_dates and Stripe references
 * 4. Update user subscription_expires_at
 * 5. Update affected users to EXPIRED if no active licenses remain
 * 6. Reactivate pro_account if suspended
 *
 * Benefits:
 * - Atomic: all-or-nothing transaction
 * - No partial state if crash occurs mid-operation
 * - Uses FOR UPDATE locks to prevent race conditions
 */
async function processInvoicePaidPro(
  supabase: any,
  proAccountId: string,
  stripeSubscriptionId: string,
  periodEnd: string,
  stripeSubscriptionItemId: string | null,
  stripePriceId: string | null,
) {
  console.log(`🔄 Processing invoice paid via atomic RPC...`);
  await triggerExpiredLicenseProcessing(proAccountId);
  try {
    const { data, error } = await supabase.rpc("process_invoice_paid_pro", {
      p_pro_account_id: proAccountId,
      p_stripe_subscription_id: stripeSubscriptionId,
      p_period_end: periodEnd,
      p_stripe_subscription_item_id: stripeSubscriptionItemId,
      p_stripe_price_id: stripePriceId,
    });

    if (error) {
      console.error(`❌ Error calling process_invoice_paid_pro RPC:`, error);
      console.log(`⚠️ Falling back to legacy processing...`);
      await processInvoicePaidProLegacy(
        supabase,
        proAccountId,
        stripeSubscriptionId,
        periodEnd,
        stripeSubscriptionItemId,
        stripePriceId,
      );
      return;
    }

    if (data?.success) {
      console.log(`✅ Invoice paid processed via RPC:`);
      console.log(`   - Deleted licenses: ${data.deleted_licenses || 0}`);
      console.log(`   - Returned to pool: ${data.returned_licenses || 0}`);
      console.log(`   - Reactivated: ${data.reactivated_licenses || 0}`);
      console.log(`   - Renewed: ${data.renewed_licenses || 0}`);
      console.log(`   - Updated users: ${data.updated_users || 0}`);
    } else {
      console.error(
        `❌ process_invoice_paid_pro returned failure:`,
        data?.error,
      );
      await processInvoicePaidProLegacy(
        supabase,
        proAccountId,
        stripeSubscriptionId,
        periodEnd,
        stripeSubscriptionItemId,
        stripePriceId,
      );
    }
  } catch (err) {
    console.error(`❌ Exception calling process_invoice_paid_pro:`, err);
    await processInvoicePaidProLegacy(
      supabase,
      proAccountId,
      stripeSubscriptionId,
      periodEnd,
      stripeSubscriptionItemId,
      stripePriceId,
    );
  }
}

/**
 * Legacy fallback for processInvoicePaidPro
 * Used when the RPC function is not yet deployed
 */
async function processInvoicePaidProLegacy(
  supabase: any,
  proAccountId: string,
  stripeSubscriptionId: string,
  periodEnd: string,
  stripeSubscriptionItemId: string | null,
  stripePriceId: string | null,
) {
  console.log(`🔄 [LEGACY] Processing invoice paid for ${proAccountId}`);
  console.warn(
    `⚠️ [LEGACY] Using non-atomic fallback - RPC should be deployed for production`,
  );

  // STEP 1: Process canceled/unlinked licenses
  await processProRenewal(supabase, proAccountId);

  // STEP 2: Reactivate suspended licenses
  const { data: suspendedLicenses } = await supabase.from("licenses")
    .update({ status: "active", updated_at: new Date().toISOString() })
    .eq("pro_account_id", proAccountId)
    .eq("is_lifetime", false)
    .eq("status", "suspended")
    .select("id");

  if (suspendedLicenses && suspendedLicenses.length > 0) {
    console.log(
      `   ✅ [LEGACY] Reactivated ${suspendedLicenses.length} suspended licenses`,
    );
  }

  // STEP 3: Update license end_dates
  const { data: updatedLicenses, error } = await supabase.from("licenses")
    .update({
      end_date: periodEnd,
      stripe_subscription_id: stripeSubscriptionId,
      stripe_subscription_item_id: stripeSubscriptionItemId,
      stripe_price_id: stripePriceId,
      billing_starts_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    })
    .eq("pro_account_id", proAccountId)
    .eq("is_lifetime", false)
    .in("status", ["active", "available"])
    .select("id");

  if (error) {
    console.error(`❌ [LEGACY] Error renewing licenses:`, error);
  } else {
    console.log(
      `   ✅ [LEGACY] Renewed ${updatedLicenses?.length || 0} licenses`,
    );

    // STEP 3b: Update user expiration dates
    const { data: assignedLicenses } = await supabase.from("licenses")
      .select("linked_account_id")
      .eq("pro_account_id", proAccountId)
      .eq("is_lifetime", false)
      .eq("status", "active")
      .not("linked_account_id", "is", null);

    if (assignedLicenses && assignedLicenses.length > 0) {
      const userIds = assignedLicenses.map((l: any) => l.linked_account_id)
        .filter(Boolean);
      for (const userId of userIds) {
        await supabase.from("users")
          .update({
            subscription_expires_at: periodEnd,
            updated_at: new Date().toISOString(),
          })
          .eq("id", userId);
      }
      console.log(
        `   ✅ [LEGACY] Updated expiration for ${userIds.length} users`,
      );
    }
  }

  // STEP 4: Reactivate pro_account if suspended
  const { data: reactivatedAccount } = await supabase.from("pro_accounts")
    .update({ status: "active", updated_at: new Date().toISOString() })
    .eq("id", proAccountId)
    .eq("status", "suspended")
    .select("id");

  if (reactivatedAccount && reactivatedAccount.length > 0) {
    console.log(`   ✅ [LEGACY] Pro account reactivated from suspended`);
  }

  console.log(`✅ [LEGACY] Invoice paid processing complete`);
}
/**
 * Trigger server-side processing for expired unlink/canceled licenses.
 * Non-blocking safety helper used by webhook Pro flows.
 */
async function triggerExpiredLicenseProcessing(proAccountId: string | null) {
  if (!proAccountId) return;

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !supabaseServiceKey) {
    console.warn(
      `?? Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY, skipping expired-license processing for ${proAccountId}`,
    );
    return;
  }

  try {
    const response = await fetch(
      `${supabaseUrl}/functions/v1/process-expired-license-unlinks`,
      {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${supabaseServiceKey}`,
          "Content-Type": "application/json",
          "apikey": supabaseServiceKey,
        },
        body: JSON.stringify({ pro_account_id: proAccountId }),
      },
    );

    const raw = await response.text();
    let payload: any = null;
    try {
      payload = JSON.parse(raw);
    } catch {
      payload = null;
    }

    if (!response.ok || payload?.success === false) {
      console.error(
        `? process-expired-license-unlinks failed for ${proAccountId}: HTTP ${response.status} - ${
          payload?.error || raw
        }`,
      );
      return;
    }

    console.log(
      `? process-expired-license-unlinks for ${proAccountId}: ` +
        `processed=${payload?.processed_count || 0}, deleted=${
          payload?.deleted_count || 0
        }, returned=${payload?.returned_to_pool_count || 0}`,
    );
  } catch (error) {
    console.error(
      `? Exception triggering process-expired-license-unlinks for ${proAccountId}:`,
      error,
    );
  }
}
