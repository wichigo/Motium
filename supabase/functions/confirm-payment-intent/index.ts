// Follow this setup guide to integrate the Deno language server with your editor:
// https://deno.land/manual/getting_started/setup_your_environment
// This enables autocomplete, go to definition, etc.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

// Price mapping (in cents TTC)
const PRICES = {
  individual_monthly: 499,       // 4.99 EUR TTC
  individual_lifetime: 12000,    // 120.00 EUR TTC
  pro_license_monthly: 600,      // 6.00 EUR TTC (5.00 EUR HT)
  pro_license_lifetime: 14400,   // 144.00 EUR TTC (120.00 EUR HT)
}

// Stripe Product IDs
const PRODUCTS = {
  individual_monthly: "prod_TdmBT4sDscYZer",
  individual_lifetime: "prod_Tdm94ZsJEGevzK",
  pro_license_monthly: "prod_Tdm6mAbHVHJLxz",
  pro_license_lifetime: "prod_TdmC9Jq3tCk94E",
}

// Stripe recurring Price IDs (monthly subscriptions)
// TODO: Create these in Stripe Dashboard and update the IDs
const RECURRING_PRICE_IDS = {
  individual_monthly: Deno.env.get('STRIPE_PRICE_INDIVIDUAL_MONTHLY') || '',
  pro_license_monthly: Deno.env.get('STRIPE_PRICE_PRO_LICENSE_MONTHLY') || '',
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const stripe = new Stripe(Deno.env.get('STRIPE_SECRET_KEY')!, {
      apiVersion: '2023-10-16',
    })

    const supabaseUrl = Deno.env.get('SUPABASE_URL')!
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const {
      payment_method_id,
      user_id,
      pro_account_id,
      price_type,
      quantity = 1,
      email,
      billing_anchor_day, // Optional: day of month (1-28) for Pro accounts
    } = await req.json()

    // Validate required fields
    if (!payment_method_id) {
      return new Response(
        JSON.stringify({ error: 'Missing payment_method_id' }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    if (!price_type || !(price_type in PRICES)) {
      return new Response(
        JSON.stringify({ error: 'Invalid price_type' }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Determine customer context
    const isProPurchase = !!pro_account_id
    const isIndividual = !!user_id && !pro_account_id
    const isMonthly = price_type.includes('monthly')
    const isLifetime = price_type.includes('lifetime')

    if (!isProPurchase && !isIndividual) {
      return new Response(
        JSON.stringify({ error: 'Either user_id or pro_account_id is required' }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Calculate amount
    const unitPrice = PRICES[price_type as keyof typeof PRICES]
    const totalAmount = unitPrice * quantity
    const productId = PRODUCTS[price_type as keyof typeof PRODUCTS]

    console.log(`Processing: ${price_type}, quantity=${quantity}, amount=${totalAmount} cents, isMonthly=${isMonthly}`)

    // Get or create Stripe Customer
    let stripeCustomerId: string | null = null
    let ownerUserId: string | null = null
    let proAccountData: any = null

    if (isIndividual && user_id) {
      // Individual purchase: use the user's stripe_customer_id
      ownerUserId = user_id
      const { data: userData } = await supabase
        .from('users')
        .select('stripe_customer_id, email')
        .eq('id', user_id)
        .single()

      if (userData?.stripe_customer_id) {
        stripeCustomerId = userData.stripe_customer_id
        console.log(`Found existing customer for user: ${stripeCustomerId}`)
      } else {
        // Create new Stripe customer
        const customerEmail = email || userData?.email || `user_${user_id}@motium.org`
        const customer = await stripe.customers.create({
          email: customerEmail,
          metadata: {
            supabase_user_id: user_id,
          }
        })
        stripeCustomerId = customer.id
        console.log(`Created new customer: ${stripeCustomerId}`)

        // Save customer ID to users table
        await supabase
          .from('users')
          .update({ stripe_customer_id: stripeCustomerId })
          .eq('id', user_id)
      }
    } else if (isProPurchase && pro_account_id) {
      // Pro purchase: get the OWNER of the pro_account and use their stripe_customer_id
      const { data: proData } = await supabase
        .from('pro_accounts')
        .select('user_id, company_name, billing_anchor_day, stripe_subscription_id')
        .eq('id', pro_account_id)
        .single()

      if (!proData?.user_id) {
        return new Response(
          JSON.stringify({ error: 'Pro account owner not found' }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      }

      proAccountData = proData
      ownerUserId = proData.user_id

      // Get the owner's stripe_customer_id
      const { data: ownerData } = await supabase
        .from('users')
        .select('stripe_customer_id, email')
        .eq('id', ownerUserId)
        .single()

      if (ownerData?.stripe_customer_id) {
        stripeCustomerId = ownerData.stripe_customer_id
        console.log(`Found existing customer for pro owner: ${stripeCustomerId}`)
      } else {
        // Create new Stripe customer for the pro account owner
        const customerEmail = email || ownerData?.email || `user_${ownerUserId}@motium.org`
        const customer = await stripe.customers.create({
          email: customerEmail,
          name: proData?.company_name || undefined,
          metadata: {
            supabase_user_id: ownerUserId,
            supabase_pro_account_id: pro_account_id,
          }
        })
        stripeCustomerId = customer.id
        console.log(`Created new customer for pro owner: ${stripeCustomerId}`)

        // Save customer ID to owner's users table
        await supabase
          .from('users')
          .update({ stripe_customer_id: stripeCustomerId })
          .eq('id', ownerUserId)
      }
    }

    // Attach payment method to customer and set as default
    if (stripeCustomerId) {
      try {
        await stripe.paymentMethods.attach(payment_method_id, {
          customer: stripeCustomerId,
        })
        // Set as default payment method
        await stripe.customers.update(stripeCustomerId, {
          invoice_settings: {
            default_payment_method: payment_method_id,
          },
        })
        console.log(`Attached and set default payment method for customer ${stripeCustomerId}`)
      } catch (attachError: unknown) {
        // Payment method might already be attached
        const errorMessage = attachError instanceof Error ? attachError.message : String(attachError)
        console.log(`Payment method attach note: ${errorMessage}`)
      }
    }

    // ========================================
    // LIFETIME PURCHASES: One-time PaymentIntent
    // ========================================
    if (isLifetime) {
      const paymentIntent = await stripe.paymentIntents.create({
        amount: totalAmount,
        currency: 'eur',
        customer: stripeCustomerId || undefined,
        payment_method: payment_method_id,
        confirm: true,
        automatic_payment_methods: {
          enabled: true,
          allow_redirects: 'never'
        },
        metadata: {
          supabase_user_id: user_id || '',
          supabase_pro_account_id: pro_account_id || '',
          price_type,
          quantity: quantity.toString(),
          product_id: productId,
        },
        return_url: 'https://motium.org/payment/complete',
      })

      console.log(`✅ Lifetime PaymentIntent created: ${paymentIntent.id}, status: ${paymentIntent.status}`)

      return new Response(
        JSON.stringify({
          client_secret: paymentIntent.client_secret,
          payment_intent_id: paymentIntent.id,
          status: paymentIntent.status,
          type: 'one_time',
        }),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // ========================================
    // MONTHLY PURCHASES: Subscription + Immediate PaymentIntent
    // ========================================
    if (isMonthly) {
      // 1. Create immediate PaymentIntent for the first payment (no proration)
      const paymentIntent = await stripe.paymentIntents.create({
        amount: totalAmount,
        currency: 'eur',
        customer: stripeCustomerId || undefined,
        payment_method: payment_method_id,
        confirm: true,
        automatic_payment_methods: {
          enabled: true,
          allow_redirects: 'never'
        },
        metadata: {
          supabase_user_id: user_id || '',
          supabase_pro_account_id: pro_account_id || '',
          price_type,
          quantity: quantity.toString(),
          product_id: productId,
          is_initial_payment: 'true', // Mark as initial payment
        },
        return_url: 'https://motium.org/payment/complete',
      })

      console.log(`✅ Initial PaymentIntent: ${paymentIntent.id}, status: ${paymentIntent.status}`)

      if (paymentIntent.status !== 'succeeded') {
        return new Response(
          JSON.stringify({
            client_secret: paymentIntent.client_secret,
            payment_intent_id: paymentIntent.id,
            status: paymentIntent.status,
            type: 'subscription_pending',
          }),
          { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      }

      // 2. Get or create the recurring Price ID
      const recurringPriceId = RECURRING_PRICE_IDS[price_type as keyof typeof RECURRING_PRICE_IDS]

      if (!recurringPriceId) {
        // Create price on-the-fly if not configured
        console.log(`Creating recurring price for ${price_type}`)
        const price = await stripe.prices.create({
          unit_amount: unitPrice,
          currency: 'eur',
          recurring: { interval: 'month' },
          product: productId,
          metadata: { price_type },
        })
        console.log(`Created recurring price: ${price.id}`)

        // Use this price for the subscription
        const createdPriceId = price.id

        // 3. Handle subscription based on purchase type
        if (isProPurchase && pro_account_id) {
          await handleProSubscription(
            stripe, supabase, pro_account_id, stripeCustomerId!,
            createdPriceId, quantity, proAccountData, billing_anchor_day,
            user_id, paymentIntent.id
          )
        } else if (isIndividual && user_id) {
          await handleIndividualSubscription(
            stripe, supabase, user_id, stripeCustomerId!,
            createdPriceId, paymentIntent.id
          )
        }
      } else {
        // Use existing price ID
        if (isProPurchase && pro_account_id) {
          await handleProSubscription(
            stripe, supabase, pro_account_id, stripeCustomerId!,
            recurringPriceId, quantity, proAccountData, billing_anchor_day,
            user_id, paymentIntent.id
          )
        } else if (isIndividual && user_id) {
          await handleIndividualSubscription(
            stripe, supabase, user_id, stripeCustomerId!,
            recurringPriceId, paymentIntent.id
          )
        }
      }

      return new Response(
        JSON.stringify({
          client_secret: paymentIntent.client_secret,
          payment_intent_id: paymentIntent.id,
          status: paymentIntent.status,
          type: 'subscription_created',
        }),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Fallback (should not reach here)
    return new Response(
      JSON.stringify({ error: 'Invalid price_type configuration' }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) {
    console.error('Error processing payment:', error)

    const errorMessage = error instanceof Error ? error.message : 'Unknown error'

    return new Response(
      JSON.stringify({ error: errorMessage }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})

/**
 * Handle Pro account subscription (unified billing)
 * - Creates or updates the main subscription for the Pro account
 * - All licenses are billed together on billing_anchor_day
 */
async function handleProSubscription(
  stripe: Stripe,
  supabase: any,
  proAccountId: string,
  customerId: string,
  priceId: string,
  quantity: number,
  proAccountData: any,
  billingAnchorDay: number | undefined,
  userId: string,
  paymentIntentId: string
) {
  const existingSubscriptionId = proAccountData?.stripe_subscription_id
  const existingAnchorDay = proAccountData?.billing_anchor_day

  if (existingSubscriptionId) {
    // Update existing subscription: add more licenses
    console.log(`Updating existing subscription ${existingSubscriptionId} with +${quantity} licenses`)

    const subscription = await stripe.subscriptions.retrieve(existingSubscriptionId)
    const subscriptionItem = subscription.items.data[0]

    if (subscriptionItem) {
      // Increment quantity without proration
      await stripe.subscriptions.update(existingSubscriptionId, {
        items: [{
          id: subscriptionItem.id,
          quantity: (subscriptionItem.quantity || 0) + quantity,
        }],
        proration_behavior: 'none', // No proration, user already paid
        metadata: {
          ...subscription.metadata,
          last_updated: new Date().toISOString(),
          last_payment_intent: paymentIntentId,
        },
      })
      console.log(`✅ Updated subscription quantity to ${(subscriptionItem.quantity || 0) + quantity}`)
    }
  } else {
    // Create new subscription for this Pro account
    const anchorDay = billingAnchorDay || existingAnchorDay || new Date().getDate()

    // Calculate the next billing_cycle_anchor date
    const now = new Date()
    let anchorDate = new Date(now.getFullYear(), now.getMonth(), anchorDay)

    // If anchor day has passed this month, use next month
    if (anchorDate <= now) {
      anchorDate = new Date(now.getFullYear(), now.getMonth() + 1, anchorDay)
    }

    console.log(`Creating new subscription with anchor day ${anchorDay}, next billing: ${anchorDate.toISOString()}`)

    const subscription = await stripe.subscriptions.create({
      customer: customerId,
      items: [{ price: priceId, quantity }],
      billing_cycle_anchor: Math.floor(anchorDate.getTime() / 1000),
      proration_behavior: 'none', // No proration for first period (already paid via PaymentIntent)
      metadata: {
        supabase_pro_account_id: proAccountId,
        supabase_user_id: userId || '',
        price_type: 'pro_license_monthly',
        initial_payment_intent: paymentIntentId,
      },
    })

    console.log(`✅ Created subscription ${subscription.id} for Pro account ${proAccountId}`)

    // Save subscription ID and billing anchor day to pro_accounts
    await supabase
      .from('pro_accounts')
      .update({
        stripe_subscription_id: subscription.id,
        billing_anchor_day: anchorDay,
        updated_at: new Date().toISOString(),
      })
      .eq('id', proAccountId)
  }
}

/**
 * Handle Individual subscription (simple monthly)
 * - Creates a simple subscription for the user
 */
async function handleIndividualSubscription(
  stripe: Stripe,
  supabase: any,
  userId: string,
  customerId: string,
  priceId: string,
  paymentIntentId: string
) {
  // Check if user already has an active subscription
  const { data: existingSub } = await supabase
    .from('stripe_subscriptions')
    .select('stripe_subscription_id')
    .eq('user_id', userId)
    .eq('subscription_type', 'individual_monthly')
    .eq('status', 'active')
    .single()

  if (existingSub?.stripe_subscription_id) {
    console.log(`User ${userId} already has active subscription ${existingSub.stripe_subscription_id}`)
    return
  }

  // Get the customer's default payment method
  const customer = await stripe.customers.retrieve(customerId) as Stripe.Customer
  const defaultPaymentMethod = customer.invoice_settings?.default_payment_method as string | null

  if (!defaultPaymentMethod) {
    console.error(`❌ No default payment method for customer ${customerId}`)
    throw new Error('No default payment method configured')
  }

  // Create new subscription starting next month (user already paid for this month)
  const now = new Date()
  const nextMonth = new Date(now.getFullYear(), now.getMonth() + 1, now.getDate())

  const subscription = await stripe.subscriptions.create({
    customer: customerId,
    items: [{ price: priceId, quantity: 1 }],
    billing_cycle_anchor: Math.floor(nextMonth.getTime() / 1000),
    proration_behavior: 'none',
    default_payment_method: defaultPaymentMethod, // CRITICAL: Use customer's default payment method for renewals
    metadata: {
      supabase_user_id: userId,
      price_type: 'individual_monthly',
      initial_payment_intent: paymentIntentId,
    },
  })

  console.log(`✅ Created individual subscription ${subscription.id} for user ${userId}`)
  console.log(`   Default payment method: ${defaultPaymentMethod}`)

  // Also save subscription ID to users table for easy access
  await supabase.from('users').update({
    stripe_subscription_id: subscription.id,
    updated_at: new Date().toISOString(),
  }).eq('id', userId)

  console.log(`✅ Saved subscription ID to user ${userId}`)
}
