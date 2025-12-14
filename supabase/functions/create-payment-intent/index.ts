// Supabase Edge Function: Create Payment Intent for Stripe
// Deploy: supabase functions deploy create-payment-intent

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.0"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
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

    // Get Supabase client
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // Parse request body
    const { userId, email, priceType, quantity = 1 } = await req.json()

    if (!userId || !email || !priceType) {
      return new Response(
        JSON.stringify({ error: "Missing required fields: userId, email, priceType" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Validate priceType
    const validPriceTypes = [
      "individual_monthly",
      "individual_lifetime",
      "pro_license_monthly",
      "pro_license_lifetime"
    ]
    if (!validPriceTypes.includes(priceType)) {
      return new Response(
        JSON.stringify({ error: `Invalid priceType. Must be one of: ${validPriceTypes.join(", ")}` }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Price configuration (in cents)
    const prices: Record<string, { amount: number; mode: "payment" | "subscription" }> = {
      individual_monthly: { amount: 500, mode: "subscription" },      // 5€ TTC/mois
      individual_lifetime: { amount: 10000, mode: "payment" },        // 100€ TTC
      pro_license_monthly: { amount: 600, mode: "subscription" },     // 6€ TTC/mois (5€ HT + 20% TVA)
      pro_license_lifetime: { amount: 12000, mode: "payment" },       // 120€ TTC (100€ HT + 20% TVA)
    }

    const priceConfig = prices[priceType]
    const totalAmount = priceConfig.amount * quantity

    // Create or retrieve Stripe customer
    let customerId: string

    // Check if user already has a Stripe customer ID
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

      // Save customer ID to database
      await supabase
        .from("users")
        .update({ stripe_customer_id: customerId })
        .eq("id", userId)
    }

    let clientSecret: string
    let paymentIntentId: string | null = null
    let subscriptionId: string | null = null

    if (priceConfig.mode === "payment") {
      // One-time payment (lifetime)
      const paymentIntent = await stripe.paymentIntents.create({
        amount: totalAmount,
        currency: "eur",
        customer: customerId,
        metadata: {
          supabase_user_id: userId,
          price_type: priceType,
          quantity: quantity.toString(),
        },
        automatic_payment_methods: {
          enabled: true,
        },
      })
      clientSecret = paymentIntent.client_secret!
      paymentIntentId = paymentIntent.id
    } else {
      // Subscription (monthly)
      // First, create or get price ID from Stripe
      // For production, you should create these prices in Stripe Dashboard
      // and store the price IDs in environment variables

      const subscription = await stripe.subscriptions.create({
        customer: customerId,
        items: [
          {
            price_data: {
              currency: "eur",
              product_data: {
                name: priceType === "individual_monthly" ? "Motium Premium" : "Motium Pro License",
              },
              unit_amount: priceConfig.amount,
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
          supabase_user_id: userId,
          price_type: priceType,
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
