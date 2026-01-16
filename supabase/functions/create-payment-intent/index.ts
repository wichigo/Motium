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

// Prices in cents
const PRICES = {
  individual_monthly: 499,      // 4.99 EUR/month
  individual_lifetime: 12000,   // 120.00 EUR one-time
  pro_license_monthly: 499,     // 4.99 EUR/month per license
  pro_license_lifetime: 12000,  // 120.00 EUR per license one-time
}

type PriceType = keyof typeof PRICES

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

    if (isProLicense && proAccountId) {
      // For Pro licenses, get the owner's user_id from pro_accounts
      const { data: proData } = await supabase
        .from("pro_accounts")
        .select("user_id, billing_email")
        .eq("id", proAccountId)
        .single()

      if (!proData?.user_id) {
        throw new Error("Pro account not found or has no owner")
      }

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

    let clientSecret: string
    let paymentIntentId: string | null = null
    let subscriptionId: string | null = null

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
    } else {
      // Subscription for monthly payments
      const subscription = await stripe.subscriptions.create({
        customer: customerId,
        items: [
          {
            price_data: {
              currency: "eur",
              product: productId,
              unit_amount: priceConfig,
              recurring: {
                interval: "month",
              },
            },
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
      })

      const invoice = subscription.latest_invoice as Stripe.Invoice
      const paymentIntent = invoice.payment_intent as Stripe.PaymentIntent
      clientSecret = paymentIntent.client_secret!
      subscriptionId = subscription.id
    }

    // Create ephemeral key for PaymentSheet
    const ephemeralKey = await stripe.ephemeralKeys.create(
      { customer: customerId },
      { apiVersion: "2023-10-16" }
    )

    return new Response(
      JSON.stringify({
        client_secret: clientSecret,
        customer_id: customerId,
        ephemeral_key: ephemeralKey.secret,
        payment_intent_id: paymentIntentId,
        subscription_id: subscriptionId,
        product_id: productId,
        amount_cents: totalAmount,
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
