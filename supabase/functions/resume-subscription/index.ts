// Supabase Edge Function: Resume Subscription
// Deploy: supabase functions deploy resume-subscription
//
// Reactivates a previously canceled subscription by setting cancel_at_period_end = false
// This allows the subscription to auto-renew at the end of the current billing period

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
    const { userId, subscriptionId } = await req.json()

    if (!userId) {
      return new Response(
        JSON.stringify({ error: "userId is required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    console.log(`Resume subscription request: userId=${userId}, subscriptionId=${subscriptionId}`)

    // Get user's Stripe subscription ID from database if not provided
    let stripeSubscriptionId = subscriptionId

    if (!stripeSubscriptionId) {
      // Check users table for stripe_subscription_id
      // userId can be either auth_id (from Supabase Auth) or id (from public.users)
      // Try auth_id first, then fall back to id
      let user = null
      let userError = null

      // First try: userId is auth_id
      const { data: userByAuthId, error: authIdError } = await supabase
        .from("users")
        .select("id, stripe_subscription_id, stripe_customer_id")
        .eq("auth_id", userId)
        .maybeSingle()

      if (userByAuthId) {
        user = userByAuthId
      } else {
        // Second try: userId is the public.users.id directly
        const { data: userById, error: idError } = await supabase
          .from("users")
          .select("id, stripe_subscription_id, stripe_customer_id")
          .eq("id", userId)
          .maybeSingle()

        user = userById
        userError = idError
      }

      if (!user) {
        console.error("User not found by auth_id or id:", authIdError, userError)
        return new Response(
          JSON.stringify({ error: "User not found" }),
          { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      // Use the public.users.id for subsequent queries
      const publicUserId = user.id
      stripeSubscriptionId = user.stripe_subscription_id

      // If still no subscription ID, check stripe_subscriptions table
      // Look for subscriptions with cancel_at_period_end = true that are still active
      if (!stripeSubscriptionId) {
        const { data: subscription, error: subError } = await supabase
          .from("stripe_subscriptions")
          .select("stripe_subscription_id, cancel_at_period_end, status")
          .eq("user_id", publicUserId)
          .in("status", ["active", "trialing"])
          .order("created_at", { ascending: false })
          .limit(1)
          .maybeSingle()

        if (subscription) {
          stripeSubscriptionId = subscription.stripe_subscription_id

          // Check if subscription is actually pending cancellation
          if (!subscription.cancel_at_period_end) {
            return new Response(
              JSON.stringify({
                error: "Subscription is not pending cancellation",
                subscriptionId: stripeSubscriptionId,
                cancelAtPeriodEnd: false
              }),
              { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
            )
          }
        }
      }
    }

    if (!stripeSubscriptionId) {
      return new Response(
        JSON.stringify({ error: "No subscription found to resume" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    console.log(`Resuming Stripe subscription: ${stripeSubscriptionId}`)

    // First, verify the subscription exists and is pending cancellation
    const existingSubscription = await stripe.subscriptions.retrieve(stripeSubscriptionId)

    if (!existingSubscription.cancel_at_period_end) {
      return new Response(
        JSON.stringify({
          success: true,
          subscriptionId: stripeSubscriptionId,
          cancelAtPeriodEnd: false,
          message: "Abonnement déjà actif (pas de résiliation en cours)"
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Resume the subscription in Stripe by setting cancel_at_period_end = false
    const resumedSubscription = await stripe.subscriptions.update(stripeSubscriptionId, {
      cancel_at_period_end: false,
    })

    console.log(`Subscription ${stripeSubscriptionId} resumed (cancel_at_period_end: false)`)

    // Update local database to reflect reactivation
    // The trigger sync_ended_at_on_cancel will automatically set ended_at = NULL and canceled_at = NULL
    const { data: updatedRows, error: updateError } = await supabase
      .from("stripe_subscriptions")
      .update({
        cancel_at_period_end: false,
        canceled_at: null,
        ended_at: null,
        updated_at: new Date().toISOString(),
      })
      .eq("stripe_subscription_id", stripeSubscriptionId)
      .select("id")

    if (updateError) {
      console.warn("Failed to update local subscription status:", updateError)
      // Don't fail the request - Stripe is the source of truth
    } else if (!updatedRows || updatedRows.length === 0) {
      console.warn(`No rows updated for stripe_subscription_id: ${stripeSubscriptionId}`)
      console.warn("This may indicate the subscription doesn't exist in stripe_subscriptions table")
    } else {
      console.log(`Updated ${updatedRows.length} row(s) in stripe_subscriptions`)
    }

    // Return reactivation details
    return new Response(
      JSON.stringify({
        success: true,
        subscriptionId: stripeSubscriptionId,
        cancelAtPeriodEnd: false,
        currentPeriodEnd: resumedSubscription.current_period_end
          ? new Date(resumedSubscription.current_period_end * 1000).toISOString()
          : null,
        message: "Abonnement réactivé avec succès"
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    )

  } catch (error) {
    console.error("Resume subscription error:", error)
    return new Response(
      JSON.stringify({ error: error.message || "Failed to resume subscription" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
