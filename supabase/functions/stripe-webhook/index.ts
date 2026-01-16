// Supabase Edge Function: Stripe Webhook Handler
// Deploy: supabase functions deploy stripe-webhook --no-verify-jwt

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.0"

serve(async (req) => {
  try {
    const stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY")
    const webhookSecret = Deno.env.get("STRIPE_WEBHOOK_SECRET")

    if (!stripeSecretKey || !webhookSecret) {
      throw new Error("Missing Stripe configuration")
    }

    const stripe = new Stripe(stripeSecretKey, {
      apiVersion: "2023-10-16",
      httpClient: Stripe.createFetchHttpClient(),
    })

    // Get Supabase client with service role
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // Verify webhook signature
    const signature = req.headers.get("stripe-signature")
    if (!signature) {
      return new Response("Missing stripe-signature header", { status: 400 })
    }

    const body = await req.text()
    let event: Stripe.Event

    try {
      event = await stripe.webhooks.constructEventAsync(body, signature, webhookSecret)
    } catch (err) {
      console.error("Webhook signature verification failed:", err.message)
      return new Response(`Webhook Error: ${err.message}`, { status: 400 })
    }

    console.log(`Received event: ${event.type}`)

    // Handle different event types
    switch (event.type) {
      case "payment_intent.succeeded": {
        const paymentIntent = event.data.object as Stripe.PaymentIntent
        await handlePaymentIntentSucceeded(supabase, paymentIntent)
        break
      }

      case "checkout.session.completed": {
        const session = event.data.object as Stripe.Checkout.Session
        await handleCheckoutSessionCompleted(supabase, session)
        break
      }

      case "invoice.paid": {
        const invoice = event.data.object as Stripe.Invoice
        await handleInvoicePaid(supabase, stripe, invoice)
        break
      }

      case "invoice.payment_failed": {
        const invoice = event.data.object as Stripe.Invoice
        await handleInvoicePaymentFailed(supabase, invoice)
        break
      }

      case "customer.subscription.created":
      case "customer.subscription.updated": {
        const subscription = event.data.object as Stripe.Subscription
        await handleSubscriptionUpdate(supabase, subscription)
        break
      }

      case "customer.subscription.deleted": {
        const subscription = event.data.object as Stripe.Subscription
        await handleSubscriptionDeleted(supabase, subscription)
        break
      }

      default:
        console.log(`Unhandled event type: ${event.type}`)
    }

    return new Response(JSON.stringify({ received: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })

  } catch (error) {
    console.error("Webhook error:", error)
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    )
  }
})

/**
 * Handle successful one-time payment (lifetime subscriptions)
 */
async function handlePaymentIntentSucceeded(
  supabase: any,
  paymentIntent: Stripe.PaymentIntent
) {
  const {
    supabase_user_id,
    supabase_pro_account_id,
    price_type,
    product_id,
    quantity
  } = paymentIntent.metadata

  console.log(`Processing payment success: ${paymentIntent.id}, type: ${price_type}`)

  // Insert into stripe_payments table with all Stripe references
  await supabase.from("stripe_payments").insert({
    user_id: supabase_user_id || null,
    pro_account_id: supabase_pro_account_id || null,
    stripe_payment_intent_id: paymentIntent.id,
    stripe_charge_id: paymentIntent.latest_charge as string || null,
    stripe_customer_id: paymentIntent.customer as string,
    payment_type: "one_time_payment",
    amount_cents: paymentIntent.amount,
    amount_received_cents: paymentIntent.amount_received,
    currency: paymentIntent.currency,
    status: "succeeded",
    receipt_url: paymentIntent.latest_charge
      ? `https://dashboard.stripe.com/payments/${paymentIntent.latest_charge}`
      : null,
    metadata: paymentIntent.metadata,
    paid_at: new Date().toISOString(),
  })

  // Create a "subscription" record for all purchase types (lifetime and monthly)
  if (price_type) {
    const isLifetime = price_type.includes("lifetime")
    const qty = parseInt(quantity || "1")

    const subscriptionData = {
      user_id: supabase_user_id || null,
      pro_account_id: supabase_pro_account_id || null,
      stripe_subscription_id: `${isLifetime ? 'lifetime' : 'onetime'}_${paymentIntent.id}`,
      stripe_customer_id: paymentIntent.customer as string,
      stripe_product_id: product_id,
      subscription_type: price_type,
      status: "active",
      quantity: qty,
      currency: paymentIntent.currency,
      unit_amount_cents: paymentIntent.amount / qty,
      current_period_start: new Date().toISOString(),
      current_period_end: isLifetime ? null : new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
      metadata: paymentIntent.metadata,
    }

    await supabase.from("stripe_subscriptions").insert(subscriptionData)
    console.log(`Created subscription record: ${price_type}`)
    // NOTE: users.subscription_type is synchronized automatically by SQL trigger
    // sync_user_subscription_cache() in stripe_integration.sql - NO direct update needed
  }

  // Create licenses if Pro (monthly or lifetime) - OUTSIDE the lifetime block
  if (supabase_pro_account_id && (price_type === "pro_license_monthly" || price_type === "pro_license_lifetime")) {
    const qty = parseInt(quantity || "1")

    // Idempotency check: skip if licenses already exist for this payment intent
    const { data: existingLicenses } = await supabase
      .from("licenses")
      .select("id")
      .eq("stripe_payment_intent_id", paymentIntent.id)

    if (existingLicenses && existingLicenses.length > 0) {
      console.log(`Licenses already exist for PI ${paymentIntent.id}, skipping creation`)
    } else {
      const isLifetime = price_type === "pro_license_lifetime"
      const now = new Date()
      // For monthly licenses, set end_date to 30 days from now
      // (will be updated to actual subscription period end when subscription is created)
      const endDate = isLifetime ? null : new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000).toISOString()

      const licenses = Array.from({ length: qty }, () => ({
        pro_account_id: supabase_pro_account_id,
        is_lifetime: isLifetime,
        status: "active",
        stripe_payment_intent_id: paymentIntent.id, // For idempotency
        price_monthly_ht: isLifetime ? 0 : 5.00, // 6.00 TTC / 1.20 = 5.00 HT
        vat_rate: 0.20,
        start_date: now.toISOString(),
        end_date: endDate, // null for lifetime, 30 days for monthly
        billing_starts_at: now.toISOString(),
        created_at: now.toISOString(),
        updated_at: now.toISOString(),
      }))
      await supabase.from("licenses").insert(licenses)
      console.log(`Created ${qty} ${isLifetime ? 'lifetime' : 'monthly'} licenses for pro account ${supabase_pro_account_id}, end_date: ${endDate}`)
    }
  }
}

/**
 * Handle checkout session completion
 */
async function handleCheckoutSessionCompleted(
  supabase: any,
  session: Stripe.Checkout.Session
) {
  console.log(`Checkout session completed: ${session.id}`)
  // Most logic is handled by invoice.paid or payment_intent.succeeded
}

/**
 * Handle paid invoice (subscription renewals)
 */
async function handleInvoicePaid(
  supabase: any,
  stripe: Stripe,
  invoice: Stripe.Invoice
) {
  const subscriptionId = invoice.subscription as string
  if (!subscriptionId) {
    console.log(`ℹ️ Invoice ${invoice.id} paid but no subscription ID - likely one-time payment`)
    return
  }

  console.log(`📧 Invoice paid for subscription: ${subscriptionId}`)
  console.log(`   Invoice ID: ${invoice.id}`)
  console.log(`   Amount: ${invoice.amount_paid} ${invoice.currency}`)
  console.log(`   Billing reason: ${invoice.billing_reason}`)

  // Get the subscription from Stripe
  const subscription = await stripe.subscriptions.retrieve(subscriptionId)
  const metadata = subscription.metadata

  console.log(`   Subscription status: ${subscription.status}`)
  console.log(`   Subscription metadata: ${JSON.stringify(metadata)}`)
  console.log(`   Cancel at period end: ${subscription.cancel_at_period_end}`)

  const {
    supabase_user_id,
    supabase_pro_account_id,
    price_type,
  } = metadata

  console.log(`   Extracted: user_id=${supabase_user_id || 'null'}, price_type=${price_type || 'null'}`)

  const periodEnd = new Date(subscription.current_period_end * 1000).toISOString()
  console.log(`   New period end: ${periodEnd}`)

  // Extract subscription item and price info for reference
  const subscriptionItem = subscription.items.data[0]
  const stripePriceId = subscriptionItem?.price?.id || null
  const stripeSubscriptionItemId = subscriptionItem?.id || null

  // Insert payment record with all Stripe references
  await supabase.from("stripe_payments").insert({
    user_id: supabase_user_id || null,
    pro_account_id: supabase_pro_account_id || null,
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
  })

  // Update subscription period in stripe_subscriptions with all Stripe references
  await supabase.from("stripe_subscriptions")
    .update({
      current_period_start: new Date(subscription.current_period_start * 1000).toISOString(),
      current_period_end: periodEnd,
      stripe_price_id: stripePriceId,
      status: subscription.status,
      updated_at: new Date().toISOString(),
    })
    .eq("stripe_subscription_id", subscriptionId)

  // Renew Pro licenses if this is a pro_license_monthly subscription
  if (supabase_pro_account_id && price_type === "pro_license_monthly") {
    // Update all monthly licenses for this pro account with new end_date and Stripe references
    const { error } = await supabase
      .from("licenses")
      .update({
        status: "active",
        end_date: periodEnd, // Renew end_date to new subscription period end
        stripe_subscription_id: subscriptionId,
        stripe_subscription_item_id: stripeSubscriptionItemId,
        stripe_price_id: stripePriceId,
        billing_starts_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      })
      .eq("pro_account_id", supabase_pro_account_id)
      .eq("is_lifetime", false)

    if (error) {
      console.error("Error renewing licenses:", error)
    } else {
      console.log(`✅ Renewed monthly licenses for pro account ${supabase_pro_account_id}, new end_date: ${periodEnd}`)

      // Also update subscription_expires_at for all users with assigned licenses from this pro account
      // This keeps the user's cached expiration date in sync with the license end_date
      const { data: assignedLicenses } = await supabase
        .from("licenses")
        .select("linked_account_id")
        .eq("pro_account_id", supabase_pro_account_id)
        .eq("is_lifetime", false)
        .not("linked_account_id", "is", null)

      if (assignedLicenses && assignedLicenses.length > 0) {
        const userIds = assignedLicenses.map(l => l.linked_account_id).filter(Boolean)
        for (const userId of userIds) {
          await supabase.from("users").update({
            subscription_expires_at: periodEnd,
            updated_at: new Date().toISOString(),
          }).eq("id", userId)
        }
        console.log(`✅ Updated subscription_expires_at for ${userIds.length} users with licenses`)
      }
    }
  }

  // NOTE: Individual subscription renewal is handled automatically by the SQL trigger
  // sync_user_subscription_cache() in stripe_integration.sql when stripe_subscriptions is updated above.
  // NO direct update to users.subscription_type needed here to avoid race conditions.
  if (supabase_user_id && price_type?.includes("individual")) {
    console.log(`ℹ️ Individual subscription ${subscriptionId} renewed - users.subscription_type synced via trigger`)
  }
}

/**
 * Handle failed invoice payment
 */
async function handleInvoicePaymentFailed(
  supabase: any,
  invoice: Stripe.Invoice
) {
  console.log(`Invoice payment failed: ${invoice.id}`)

  // Record the failed payment
  await supabase.from("stripe_payments").insert({
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
  })

  // Update subscription status
  if (invoice.subscription) {
    await supabase.from("stripe_subscriptions")
      .update({
        status: "past_due",
        updated_at: new Date().toISOString(),
      })
      .eq("stripe_subscription_id", invoice.subscription as string)
  }
}

/**
 * Handle subscription creation/update
 */
async function handleSubscriptionUpdate(
  supabase: any,
  subscription: Stripe.Subscription
) {
  const metadata = subscription.metadata
  const {
    supabase_user_id,
    supabase_pro_account_id,
    price_type,
    product_id,
    quantity
  } = metadata

  console.log(`Subscription update: ${subscription.id}, status: ${subscription.status}`)

  // Check if subscription exists
  const { data: existing } = await supabase
    .from("stripe_subscriptions")
    .select("id")
    .eq("stripe_subscription_id", subscription.id)
    .single()

  // Extract subscription item and price info
  const subscriptionItem = subscription.items.data[0]
  const stripePriceId = subscriptionItem?.price?.id || null
  const stripeSubscriptionItemId = subscriptionItem?.id || null

  const subscriptionData = {
    user_id: supabase_user_id || null,
    pro_account_id: supabase_pro_account_id || null,
    stripe_subscription_id: subscription.id,
    stripe_customer_id: subscription.customer as string,
    stripe_product_id: product_id || subscriptionItem?.price?.product || null,
    stripe_price_id: stripePriceId,
    subscription_type: price_type || "individual_monthly",
    status: subscription.status,
    quantity: parseInt(quantity || "1"),
    currency: subscription.currency,
    unit_amount_cents: subscriptionItem?.price?.unit_amount || null,
    current_period_start: new Date(subscription.current_period_start * 1000).toISOString(),
    current_period_end: new Date(subscription.current_period_end * 1000).toISOString(),
    cancel_at_period_end: subscription.cancel_at_period_end,
    canceled_at: subscription.canceled_at
      ? new Date(subscription.canceled_at * 1000).toISOString()
      : null,
    ended_at: subscription.ended_at
      ? new Date(subscription.ended_at * 1000).toISOString()
      : null,
    metadata: metadata,
    updated_at: new Date().toISOString(),
  }

  if (existing) {
    // Update existing
    await supabase.from("stripe_subscriptions")
      .update(subscriptionData)
      .eq("id", existing.id)
  } else {
    // Insert new
    await supabase.from("stripe_subscriptions").insert({
      ...subscriptionData,
      created_at: new Date().toISOString(),
    })
  }

  // NOTE: users.subscription_type is synchronized automatically by SQL trigger
  // sync_user_subscription_cache() in stripe_integration.sql - NO direct update needed.
  // The trigger fires on INSERT/UPDATE of stripe_subscriptions above.
  if (supabase_user_id && price_type?.includes("individual")) {
    console.log(`ℹ️ Subscription ${subscription.id} updated - users.subscription_type synced via trigger`)
  }

  // Create licenses if Pro monthly and active
  if (supabase_pro_account_id && price_type === "pro_license_monthly") {
    if (subscription.status === "active") {
      const qty = parseInt(quantity || "1")
      const periodEnd = new Date(subscription.current_period_end * 1000).toISOString()

      // Check existing licenses for this subscription
      const { data: existingLicenses } = await supabase
        .from("licenses")
        .select("id")
        .eq("stripe_subscription_id", subscription.id)

      if (!existingLicenses || existingLicenses.length === 0) {
        const licenses = Array.from({ length: qty }, () => ({
          pro_account_id: supabase_pro_account_id,
          is_lifetime: false,
          status: "active",
          stripe_subscription_id: subscription.id,
          stripe_subscription_item_id: stripeSubscriptionItemId,
          stripe_price_id: stripePriceId,
          start_date: new Date().toISOString(),
          end_date: periodEnd, // Set end_date to subscription period end
          billing_starts_at: new Date().toISOString(),
          created_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
        }))
        await supabase.from("licenses").insert(licenses)
        console.log(`Created ${qty} monthly licenses for pro account ${supabase_pro_account_id}, end_date: ${periodEnd}, price_id: ${stripePriceId}`)
      }
    }
  }
}

/**
 * Handle subscription cancellation
 */
async function handleSubscriptionDeleted(
  supabase: any,
  subscription: Stripe.Subscription
) {
  const metadata = subscription.metadata

  console.log(`Subscription deleted: ${subscription.id}`)

  // Update stripe_subscriptions
  await supabase.from("stripe_subscriptions")
    .update({
      status: "canceled",
      canceled_at: new Date().toISOString(),
      ended_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    })
    .eq("stripe_subscription_id", subscription.id)

  // NOTE: users.subscription_type is synchronized automatically by SQL trigger
  // sync_user_subscription_cache() in stripe_integration.sql - NO direct update needed.
  // The trigger fires on UPDATE of stripe_subscriptions above (status = 'canceled').
  if (metadata.supabase_user_id && metadata.price_type?.includes("individual")) {
    console.log(`ℹ️ Subscription ${subscription.id} canceled - users.subscription_type synced via trigger`)
  }

  // Cancel licenses if Pro
  if (metadata.supabase_pro_account_id && metadata.price_type?.includes("pro_license")) {
    await supabase.from("licenses")
      .update({ status: "cancelled" })
      .eq("stripe_subscription_id", subscription.id)
  }
}

