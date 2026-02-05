// Supabase Edge Function: Create Payment Intent for Stripe
// Deploy: supabase functions deploy create-payment-intent

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.0"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

// Stripe Product IDs (Test Mode)
const PRODUCTS = {
  individual_monthly: "prod_TdmBT4sDscYZer",
  individual_lifetime: "prod_Tdm94ZsJEGevzK",
  pro_license_monthly: "prod_Tdm6mAbHVHJLxz",
  pro_license_lifetime: "prod_TdmC9Jq3tCk94E",
}

// Stripe Price IDs (created in Stripe Dashboard)
// FIX BUG: Use predefined Price IDs instead of inline price_data
// This ensures stripe_price_id is properly populated in stripe_subscriptions
const PRICE_IDS = {
  individual_monthly: "price_1Siz4GCsRT1u49RIG9npZVR4",
  individual_lifetime: "price_1SgUbHCsRT1u49RIfI1RbQCY",
  pro_license_monthly: "price_1SgUYGCsRT1u49RImUY0mvZQ",
  pro_license_lifetime: "price_1SgUeYCsRT1u49RItR5LyYGU",
}

// Prices in cents (kept for reference and lifetime payments)
const PRICES = {
  individual_monthly: 499,      // 4.99 EUR/month
  individual_lifetime: 12000,   // 120.00 EUR one-time
  pro_license_monthly: 499,     // 4.99 EUR/month per license
  pro_license_lifetime: 12000,  // 120.00 EUR per license one-time
}

type PriceType = keyof typeof PRICES

/**
 * Calculate the Unix timestamp for billing_cycle_anchor based on billing_anchor_day
 * Returns the next occurrence of that day (this month if not passed, next month otherwise)
 *
 * Example: If today is Jan 20 and billing_anchor_day is 15:
 *   → Returns Feb 15 (since Jan 15 already passed)
 * Example: If today is Jan 10 and billing_anchor_day is 15:
 *   → Returns Jan 15 (still in the future)
 */
function calculateBillingCycleAnchor(billingAnchorDay: number): number {
  const now = new Date()
  const currentDay = now.getUTCDate()
  const currentMonth = now.getUTCMonth()
  const currentYear = now.getUTCFullYear()

  // Clamp anchor day to valid range (1-28 to avoid month-end issues)
  const anchorDay = Math.min(Math.max(billingAnchorDay, 1), 28)

  let targetMonth = currentMonth
  let targetYear = currentYear

  // If anchor day has already passed this month, use next month
  if (currentDay >= anchorDay) {
    targetMonth += 1
    if (targetMonth > 11) {
      targetMonth = 0
      targetYear += 1
    }
  }

  // Create date at midnight UTC on the anchor day
  const anchorDate = new Date(Date.UTC(targetYear, targetMonth, anchorDay, 0, 0, 0))

  // Return Unix timestamp (seconds)
  return Math.floor(anchorDate.getTime() / 1000)
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: corsHeaders })
  }

  try {
    // Get Stripe secret key from environment
    const stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY")
    if (!stripeSecretKey) {
      throw new Error("STRIPE_SECRET_KEY not configured")
    }

    const stripe = new Stripe(stripeSecretKey, {
      apiVersion: "2023-10-16",
    })

    // Get Supabase client with service role
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // Parse request body
    const { userId, proAccountId, email, priceType, quantity = 1 } = await req.json()

    // Validate inputs
    if (!email || !priceType) {
      return new Response(
        JSON.stringify({ error: "Missing required fields: email, priceType" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    if (!userId && !proAccountId) {
      return new Response(
        JSON.stringify({ error: "Either userId or proAccountId is required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Validate priceType
    const validPriceTypes: PriceType[] = [
      "individual_monthly",
      "individual_lifetime",
      "pro_license_monthly",
      "pro_license_lifetime"
    ]
    if (!validPriceTypes.includes(priceType as PriceType)) {
      return new Response(
        JSON.stringify({ error: `Invalid priceType. Must be one of: ${validPriceTypes.join(", ")}` }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const priceConfig = PRICES[priceType as PriceType]
    const productId = PRODUCTS[priceType as PriceType]
    const totalAmount = priceConfig * quantity
    const isLifetime = priceType.includes("lifetime")
    const isProLicense = priceType.includes("pro_license")

    // Create or retrieve Stripe customer
    // Note: stripe_customer_id is always stored in users table, even for Pro accounts
    let customerId: string
    let ownerUserId: string

    // Pro account data (used for billing_anchor_day and existing subscription)
    let proAccountData: { user_id: string; billing_email: string | null; billing_anchor_day: number | null; stripe_subscription_id: string | null } | null = null

    if (isProLicense && proAccountId) {
      // For Pro licenses, get the owner's user_id and billing info from pro_accounts
      const { data: proData } = await supabase
        .from("pro_accounts")
        .select("user_id, billing_email, billing_anchor_day, stripe_subscription_id")
        .eq("id", proAccountId)
        .single()

      if (!proData?.user_id) {
        throw new Error("Pro account not found or has no owner")
      }

      proAccountData = proData
      ownerUserId = proData.user_id

      // Get stripe_customer_id from the owner's users record
      const { data: userData } = await supabase
        .from("users")
        .select("stripe_customer_id")
        .eq("id", ownerUserId)
        .single()

      if (userData?.stripe_customer_id) {
        customerId = userData.stripe_customer_id
      } else {
        // Create new Stripe customer for the Pro account owner
        const customer = await stripe.customers.create({
          email: proData?.billing_email || email,
          metadata: {
            supabase_user_id: ownerUserId,
            supabase_pro_account_id: proAccountId,
          },
        })
        customerId = customer.id

        // Save customer ID to users table (owner of pro account)
        await supabase
          .from("users")
          .update({ stripe_customer_id: customerId })
          .eq("id", ownerUserId)
      }
    } else if (userId) {
      // For individual users, check users table
      ownerUserId = userId

      const { data: userData } = await supabase
        .from("users")
        .select("stripe_customer_id")
        .eq("id", userId)
        .single()

      if (userData?.stripe_customer_id) {
        customerId = userData.stripe_customer_id
      } else {
        // Create new Stripe customer
        const customer = await stripe.customers.create({
          email: email,
          metadata: {
            supabase_user_id: userId,
          },
        })
        customerId = customer.id

        // Save customer ID to users table
        await supabase
          .from("users")
          .update({ stripe_customer_id: customerId })
          .eq("id", userId)
      }
    } else {
      throw new Error("Invalid configuration: no user or pro account ID")
    }

    let clientSecret: string = ""
    let paymentIntentId: string | null = null
    let subscriptionId: string | null = null
    let isSubscriptionUpdate = false  // True if updating existing Pro subscription
    let proratedAmountCents: number | null = null  // Prorated amount for mid-cycle additions

    if (isLifetime) {
      // One-time payment for lifetime purchases
      const paymentIntent = await stripe.paymentIntents.create({
        amount: totalAmount,
        currency: "eur",
        customer: customerId,
        metadata: {
          supabase_user_id: userId || "",
          supabase_pro_account_id: proAccountId || "",
          price_type: priceType,
          product_id: productId,
          quantity: quantity.toString(),
        },
        automatic_payment_methods: {
          enabled: true,
        },
      })
      clientSecret = paymentIntent.client_secret!
      paymentIntentId = paymentIntent.id
    } else if (isProLicense && proAccountId && proAccountData?.stripe_subscription_id) {
      // ========================================================================
      // PRO LICENSE MONTHLY - UPDATE EXISTING SUBSCRIPTION
      // ========================================================================
      // Pro account already has a subscription, add licenses by updating quantity
      // This ensures all licenses are billed together on billing_anchor_day
      console.log(`Updating existing Pro subscription: ${proAccountData.stripe_subscription_id}`)

      // Get current subscription to find the subscription item
      const existingSubscription = await stripe.subscriptions.retrieve(
        proAccountData.stripe_subscription_id,
        { expand: ["items"] }
      )

      if (existingSubscription.status === "canceled" || existingSubscription.status === "incomplete_expired") {
        throw new Error("Existing subscription is canceled. Please create a new subscription.")
      }

      const subscriptionItem = existingSubscription.items.data[0]
      if (!subscriptionItem) {
        throw new Error("No subscription item found on existing subscription")
      }

      const currentQuantity = subscriptionItem.quantity || 0
      const newQuantity = currentQuantity + quantity

      console.log(`Updating quantity: ${currentQuantity} → ${newQuantity}`)

      // Update subscription with new quantity (Stripe handles proration automatically)
      const updatedSubscription = await stripe.subscriptions.update(
        proAccountData.stripe_subscription_id,
        {
          items: [
            {
              id: subscriptionItem.id,
              quantity: newQuantity,
            },
          ],
          proration_behavior: "create_prorations", // Charge prorated amount for new licenses
          payment_behavior: "pending_if_incomplete",
          expand: ["latest_invoice.payment_intent"],
          metadata: {
            supabase_user_id: userId || "",
            supabase_pro_account_id: proAccountId || "",
            price_type: priceType,
            product_id: productId,
            quantity: newQuantity.toString(),
          },
        }
      )

      isSubscriptionUpdate = true

      // Get the payment intent for the prorated amount (if any)
      const invoice = updatedSubscription.latest_invoice as Stripe.Invoice | null
      if (invoice?.payment_intent) {
        const paymentIntent = invoice.payment_intent as Stripe.PaymentIntent
        proratedAmountCents = paymentIntent.amount || null
        paymentIntentId = paymentIntent.id
        if (paymentIntent.client_secret && paymentIntent.status !== "succeeded") {
          clientSecret = paymentIntent.client_secret
        } else {
          // Already paid or no payment needed
          clientSecret = ""
        }
      } else if (invoice) {
        // Invoice exists but no payment intent (might be $0 or already paid)
        proratedAmountCents = invoice.amount_due || 0
      }

      subscriptionId = updatedSubscription.id
      console.log(`Subscription updated. Prorated amount: ${proratedAmountCents} cents, requires payment: ${clientSecret !== ""}`)

    } else {
      // ========================================================================
      // NEW SUBSCRIPTION (Individual monthly OR first Pro license purchase)
      // ========================================================================
      const stripePriceId = PRICE_IDS[priceType as PriceType]

      // Calculate billing_cycle_anchor for Pro accounts
      let billingCycleAnchor: number | undefined = undefined

      if (isProLicense && proAccountId) {
        // For Pro licenses, align billing to billing_anchor_day
        const anchorDay = proAccountData?.billing_anchor_day || new Date().getUTCDate()
        billingCycleAnchor = calculateBillingCycleAnchor(anchorDay)
        console.log(`Setting billing_cycle_anchor to day ${anchorDay}: ${new Date(billingCycleAnchor * 1000).toISOString()}`)
      }

      const subscriptionParams: Stripe.SubscriptionCreateParams = {
        customer: customerId,
        items: [
          {
            price: stripePriceId,
            quantity: quantity,
          },
        ],
        payment_behavior: "default_incomplete",
        payment_settings: {
          save_default_payment_method: "on_subscription",
        },
        expand: ["latest_invoice.payment_intent"],
        metadata: {
          supabase_user_id: userId || "",
          supabase_pro_account_id: proAccountId || "",
          price_type: priceType,
          product_id: productId,
          quantity: quantity.toString(),
        },
      }

      // Add billing_cycle_anchor for Pro accounts (aligns all future renewals)
      if (billingCycleAnchor) {
        subscriptionParams.billing_cycle_anchor = billingCycleAnchor
        // Prorate the first period from now until billing_anchor_day
        subscriptionParams.proration_behavior = "create_prorations"
      }

      const subscription = await stripe.subscriptions.create(subscriptionParams)

      const invoice = subscription.latest_invoice as Stripe.Invoice
      const paymentIntent = invoice.payment_intent as Stripe.PaymentIntent
      clientSecret = paymentIntent.client_secret!
      subscriptionId = subscription.id

      // For Pro accounts, save the subscription ID for future license additions
      if (isProLicense && proAccountId) {
        await supabase
          .from("pro_accounts")
          .update({
            stripe_subscription_id: subscription.id,
            billing_anchor_day: proAccountData?.billing_anchor_day || new Date().getUTCDate(),
            updated_at: new Date().toISOString(),
          })
          .eq("id", proAccountId)

        console.log(`Saved stripe_subscription_id ${subscription.id} to pro_account ${proAccountId}`)
      }
    }

    // Create ephemeral key for PaymentSheet
    const ephemeralKey = await stripe.ephemeralKeys.create(
      { customer: customerId },
      { apiVersion: "2023-10-16" }
    )

    // Determine if payment is required (client_secret present and non-empty)
    const requiresPayment = clientSecret !== ""

    return new Response(
      JSON.stringify({
        client_secret: clientSecret || null,
        customer_id: customerId,
        ephemeral_key: ephemeralKey.secret,
        payment_intent_id: paymentIntentId,
        subscription_id: subscriptionId,
        product_id: productId,
        amount_cents: isSubscriptionUpdate ? (proratedAmountCents || 0) : totalAmount,
        requires_payment: requiresPayment,
        is_subscription_update: isSubscriptionUpdate,
        // For subscription updates, the full amount will be charged on next billing_anchor_day
        message: isSubscriptionUpdate
          ? (requiresPayment
            ? `Ajout de ${quantity} licence(s). Montant proraté à payer maintenant.`
            : `Ajout de ${quantity} licence(s) confirmé. Sera facturé au prochain renouvellement.`)
          : null,
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      }
    )

  } catch (error) {
    console.error("Error:", error)
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
