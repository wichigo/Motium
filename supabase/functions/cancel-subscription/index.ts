// Supabase Edge Function: Cancel Subscription
// Deploy: supabase functions deploy cancel-subscription

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
    return new Response("ok", { headers: corsHeaders })
  }

  try {
    const stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY")
    if (!stripeSecretKey) {
      throw new Error("Missing STRIPE_SECRET_KEY")
    }

    const stripe = new Stripe(stripeSecretKey, {
      apiVersion: "2023-10-16",
      httpClient: Stripe.createFetchHttpClient(),
    })

    // Get Supabase client
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // Parse request body
    const { userId, subscriptionId, cancelImmediately = false } = await req.json()

    if (!userId) {
      return new Response(
        JSON.stringify({ error: "userId is required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    console.log(`Cancel subscription request: userId=${userId}, subscriptionId=${subscriptionId}, immediate=${cancelImmediately}`)

    // Get user's Stripe subscription ID from database if not provided
    let stripeSubscriptionId = subscriptionId

    if (!stripeSubscriptionId) {
      // Check users table for stripe_subscription_id
      const { data: user, error: userError } = await supabase
        .from("users")
        .select("stripe_subscription_id, stripe_customer_id")
        .eq("auth_id", userId)
        .single()

      if (userError || !user) {
        console.error("User not found:", userError)
        return new Response(
          JSON.stringify({ error: "User not found" }),
          { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      stripeSubscriptionId = user.stripe_subscription_id

      // If still no subscription ID, check stripe_subscriptions table
      if (!stripeSubscriptionId) {
        const { data: subscription, error: subError } = await supabase
          .from("stripe_subscriptions")
          .select("stripe_subscription_id")
          .eq("user_id", userId)
          .eq("status", "active")
          .order("created_at", { ascending: false })
          .limit(1)
          .single()

        if (subscription) {
          stripeSubscriptionId = subscription.stripe_subscription_id
        }
      }
    }

    if (!stripeSubscriptionId) {
      return new Response(
        JSON.stringify({ error: "No active subscription found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    console.log(`Canceling Stripe subscription: ${stripeSubscriptionId}`)

    // Cancel the subscription in Stripe
    // cancel_at_period_end = true means subscription stays active until end of billing period
    // prorate = false for cancel_at_period_end
    const canceledSubscription = await stripe.subscriptions.update(stripeSubscriptionId, {
      cancel_at_period_end: !cancelImmediately,
    })

    // If canceling immediately, delete the subscription
    if (cancelImmediately) {
      await stripe.subscriptions.cancel(stripeSubscriptionId)
    }

    console.log(`Subscription ${stripeSubscriptionId} canceled (at_period_end: ${!cancelImmediately})`)

    // Update local database to reflect cancellation status
    // The webhook will handle the full update, but we set a flag immediately for UX
    const { error: updateError } = await supabase
      .from("stripe_subscriptions")
      .update({
        cancel_at_period_end: !cancelImmediately,
        canceled_at: new Date().toISOString(),
        status: cancelImmediately ? "canceled" : "active", // Still active until period end
        updated_at: new Date().toISOString(),
      })
      .eq("stripe_subscription_id", stripeSubscriptionId)

    if (updateError) {
      console.warn("Failed to update local subscription status:", updateError)
      // Don't fail the request - Stripe is the source of truth
    }

    // Return cancellation details
    return new Response(
      JSON.stringify({
        success: true,
        subscriptionId: stripeSubscriptionId,
        cancelAtPeriodEnd: !cancelImmediately,
        currentPeriodEnd: canceledSubscription.current_period_end
          ? new Date(canceledSubscription.current_period_end * 1000).toISOString()
          : null,
        message: cancelImmediately
          ? "Abonnement résilié immédiatement"
          : "Abonnement résilié - reste actif jusqu'à la fin de la période"
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    )

  } catch (error) {
    console.error("Cancel subscription error:", error)
    return new Response(
      JSON.stringify({ error: error.message || "Failed to cancel subscription" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
