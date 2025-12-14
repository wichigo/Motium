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
      event = stripe.webhooks.constructEvent(body, signature, webhookSecret)
    } catch (err) {
      console.error("Webhook signature verification failed:", err.message)
      return new Response(`Webhook Error: ${err.message}`, { status: 400 })
    }

    console.log(`Received event: ${event.type}`)

    // Handle different event types
    switch (event.type) {
      case "payment_intent.succeeded": {
        const paymentIntent = event.data.object as Stripe.PaymentIntent
        await handlePaymentSuccess(supabase, paymentIntent)
        break
      }

      case "invoice.paid": {
        const invoice = event.data.object as Stripe.Invoice
        await handleInvoicePaid(supabase, invoice)
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
        await handleSubscriptionCanceled(supabase, subscription)
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
async function handlePaymentSuccess(
  supabase: any,
  paymentIntent: Stripe.PaymentIntent
) {
  const { supabase_user_id, price_type, quantity } = paymentIntent.metadata

  if (!supabase_user_id || !price_type) {
    console.log("Missing metadata in payment intent")
    return
  }

  console.log(`Processing payment success for user ${supabase_user_id}, type: ${price_type}`)

  if (price_type === "individual_lifetime") {
    // Activate lifetime subscription for individual user
    await supabase
      .from("users")
      .update({
        subscription_type: "LIFETIME",
        subscription_expires_at: null, // Never expires
        stripe_subscription_id: null,
        updated_at: new Date().toISOString(),
      })
      .eq("id", supabase_user_id)

    console.log(`Activated LIFETIME subscription for user ${supabase_user_id}`)

  } else if (price_type === "pro_license_lifetime") {
    // Create lifetime licenses for Pro account
    const qty = parseInt(quantity || "1")

    // Get pro_account_id from user
    const { data: userData } = await supabase
      .from("users")
      .select("pro_account_id")
      .eq("id", supabase_user_id)
      .single()

    if (userData?.pro_account_id) {
      // Create licenses
      const licenses = Array.from({ length: qty }, () => ({
        pro_account_id: userData.pro_account_id,
        is_lifetime: true,
        status: "active",
        created_at: new Date().toISOString(),
      }))

      await supabase.from("licenses").insert(licenses)
      console.log(`Created ${qty} lifetime licenses for pro account ${userData.pro_account_id}`)
    }
  }
}

/**
 * Handle paid invoice (subscription renewals)
 */
async function handleInvoicePaid(supabase: any, invoice: Stripe.Invoice) {
  const subscriptionId = invoice.subscription as string
  if (!subscriptionId) return

  // Extend subscription expiry
  const { data: user } = await supabase
    .from("users")
    .select("id")
    .eq("stripe_subscription_id", subscriptionId)
    .single()

  if (user) {
    const expiresAt = new Date()
    expiresAt.setMonth(expiresAt.getMonth() + 1)

    await supabase
      .from("users")
      .update({
        subscription_expires_at: expiresAt.toISOString(),
        updated_at: new Date().toISOString(),
      })
      .eq("id", user.id)

    console.log(`Extended subscription for user ${user.id} until ${expiresAt.toISOString()}`)
  }
}

/**
 * Handle subscription creation/update
 */
async function handleSubscriptionUpdate(
  supabase: any,
  subscription: Stripe.Subscription
) {
  const { supabase_user_id, price_type, quantity } = subscription.metadata

  if (!supabase_user_id) {
    console.log("Missing supabase_user_id in subscription metadata")
    return
  }

  if (subscription.status === "active" || subscription.status === "trialing") {
    const expiresAt = new Date(subscription.current_period_end * 1000)

    if (price_type === "individual_monthly") {
      await supabase
        .from("users")
        .update({
          subscription_type: "PREMIUM",
          subscription_expires_at: expiresAt.toISOString(),
          stripe_subscription_id: subscription.id,
          updated_at: new Date().toISOString(),
        })
        .eq("id", supabase_user_id)

      console.log(`Activated PREMIUM subscription for user ${supabase_user_id}`)

    } else if (price_type === "pro_license_monthly") {
      // Handle Pro license subscription
      const qty = parseInt(quantity || "1")

      const { data: userData } = await supabase
        .from("users")
        .select("pro_account_id")
        .eq("id", supabase_user_id)
        .single()

      if (userData?.pro_account_id) {
        // Create monthly licenses
        const licenses = Array.from({ length: qty }, () => ({
          pro_account_id: userData.pro_account_id,
          is_lifetime: false,
          status: "active",
          stripe_subscription_id: subscription.id,
          expires_at: expiresAt.toISOString(),
          created_at: new Date().toISOString(),
        }))

        await supabase.from("licenses").insert(licenses)
        console.log(`Created ${qty} monthly licenses for pro account ${userData.pro_account_id}`)
      }
    }
  }
}

/**
 * Handle subscription cancellation
 */
async function handleSubscriptionCanceled(
  supabase: any,
  subscription: Stripe.Subscription
) {
  const { supabase_user_id, price_type } = subscription.metadata

  if (!supabase_user_id) return

  if (price_type === "individual_monthly") {
    // Downgrade to FREE
    await supabase
      .from("users")
      .update({
        subscription_type: "FREE",
        stripe_subscription_id: null,
        updated_at: new Date().toISOString(),
      })
      .eq("id", supabase_user_id)

    console.log(`Downgraded user ${supabase_user_id} to FREE`)

  } else if (price_type === "pro_license_monthly") {
    // Cancel licenses linked to this subscription
    await supabase
      .from("licenses")
      .update({ status: "canceled" })
      .eq("stripe_subscription_id", subscription.id)

    console.log(`Canceled licenses for subscription ${subscription.id}`)
  }
}
