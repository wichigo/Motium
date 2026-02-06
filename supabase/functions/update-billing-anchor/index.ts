// supabase/functions/update-billing-anchor/index.ts
// Updates billing_anchor_day and aligns licenses + Stripe renewal date
// Deploy: supabase functions deploy update-billing-anchor

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  pro_account_id: string
  billing_anchor_day: number
}

const MAX_BILLING_ANCHOR_DAY = 15

const clampAnchorDay = (day: number): number => {
  const safe = Math.trunc(day)
  return Math.min(Math.max(safe, 1), MAX_BILLING_ANCHOR_DAY)
}

const shiftToAnchorDay = (base: Date, anchorDay: number): Date => {
  const day = clampAnchorDay(anchorDay)
  const candidate = new Date(Date.UTC(
    base.getUTCFullYear(),
    base.getUTCMonth(),
    day,
    base.getUTCHours(),
    base.getUTCMinutes(),
    base.getUTCSeconds(),
    base.getUTCMilliseconds()
  ))

  if (candidate.getTime() < base.getTime()) {
    candidate.setUTCMonth(candidate.getUTCMonth() + 1)
  }

  return candidate
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ success: false, error: "Method not allowed" }),
      { status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }

  try {
    const authHeader = req.headers.get("Authorization")
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing or invalid authorization" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const userToken = authHeader.replace("Bearer ", "")

    const body: RequestBody = await req.json()
    const { pro_account_id, billing_anchor_day } = body

    if (!pro_account_id || !billing_anchor_day) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing required fields" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const anchorDay = clampAnchorDay(billing_anchor_day)

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? ""
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? ""
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""

    const supabaseUser = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: `Bearer ${userToken}` } }
    })

    const { data: { user }, error: authError } = await supabaseUser.auth.getUser()
    if (authError || !user) {
      return new Response(
        JSON.stringify({ success: false, error: "Invalid token" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const { data: publicUser, error: publicUserError } = await supabase
      .from("users")
      .select("id")
      .eq("auth_id", user.id)
      .single()

    if (publicUserError || !publicUser) {
      return new Response(
        JSON.stringify({ success: false, error: "User not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const { data: proAccount, error: proError } = await supabase
      .from("pro_accounts")
      .select("id, user_id, stripe_subscription_id")
      .eq("id", pro_account_id)
      .single()

    if (proError || !proAccount) {
      return new Response(
        JSON.stringify({ success: false, error: "Pro account not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    if (proAccount.user_id !== publicUser.id) {
      return new Response(
        JSON.stringify({ success: false, error: "Not authorized for this pro account" }),
        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    let stripeSubscriptionId: string | null = proAccount.stripe_subscription_id
    let updatedPeriodEndIso: string | null = null
    let stripeUpdated = false
    let stripeUpdateError: string | null = null
    let stripeScheduleId: string | null = null

    if (!stripeSubscriptionId) {
      const { data: subFallback } = await supabase
        .from("stripe_subscriptions")
        .select("stripe_subscription_id")
        .eq("pro_account_id", pro_account_id)
        .eq("subscription_type", "pro_license_monthly")
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle()

      stripeSubscriptionId = subFallback?.stripe_subscription_id || null
    }

    if (stripeSubscriptionId) {
      const stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY")
      if (!stripeSecretKey) {
        throw new Error("Missing STRIPE_SECRET_KEY")
      }

      const stripe = new Stripe(stripeSecretKey, {
        apiVersion: "2023-10-16",
        httpClient: Stripe.createFetchHttpClient(),
      })

      const subscription = await stripe.subscriptions.retrieve(stripeSubscriptionId, {
        expand: ["items.data.price", "schedule"],
      })
      const subscriptionItem = subscription.items?.data?.[0]

      const periodEnd = subscription.current_period_end
        || (subscriptionItem as any)?.current_period_end
        || null
      const periodStart = subscription.current_period_start
        || (subscriptionItem as any)?.current_period_start
        || null

      if (!periodEnd) {
        return new Response(
          JSON.stringify({ success: false, error: "Subscription has no current_period_end" }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      if (!subscriptionItem?.price?.id) {
        return new Response(
          JSON.stringify({ success: false, error: "Subscription has no price to reschedule" }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      const baseDate = new Date(Math.max(periodEnd * 1000, Date.now()))
      const newAnchorDate = shiftToAnchorDay(baseDate, anchorDay)
      const newAnchorUnix = Math.floor(newAnchorDate.getTime() / 1000)

      if (newAnchorUnix === periodEnd) {
        updatedPeriodEndIso = new Date(periodEnd * 1000).toISOString()
        stripeUpdated = true
      } else {
        try {
          const scheduleFromSubscription = typeof subscription.schedule === "string"
            ? subscription.schedule
            : subscription.schedule?.id

          let schedule = null as any
          if (scheduleFromSubscription) {
            schedule = typeof subscription.schedule === "string"
              ? await stripe.subscriptionSchedules.retrieve(scheduleFromSubscription)
              : subscription.schedule
            if (schedule?.status === "released" || schedule?.status === "canceled") {
              schedule = null
            } else {
              stripeScheduleId = schedule?.id || scheduleFromSubscription
            }
          }

          if (!schedule) {
            const created = await stripe.subscriptionSchedules.create({
              from_subscription: stripeSubscriptionId,
            })
            schedule = created
            stripeScheduleId = created.id
          }

          const currentPhaseStart = schedule?.current_phase?.start_date
            || schedule?.phases?.[0]?.start_date
            || periodStart
            || Math.floor(Date.now() / 1000)

          const phaseItems = [{
            price: subscriptionItem.price.id,
            quantity: subscriptionItem.quantity || 1,
          }]

          await stripe.subscriptionSchedules.update(stripeScheduleId!, {
            end_behavior: "release",
            phases: [
              {
                start_date: currentPhaseStart,
                end_date: newAnchorUnix,
                items: phaseItems,
                proration_behavior: "none",
              },
              {
                start_date: newAnchorUnix,
                items: phaseItems,
                proration_behavior: "none",
                iterations: 1,
              }
            ],
          })

          updatedPeriodEndIso = newAnchorDate.toISOString()
          stripeUpdated = true
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error)
          stripeUpdateError = message
          throw error
        }
      }
    } else {
      const { data: latestLicense } = await supabase
        .from("licenses")
        .select("end_date")
        .eq("pro_account_id", pro_account_id)
        .eq("is_lifetime", false)
        .not("end_date", "is", null)
        .order("end_date", { ascending: false })
        .limit(1)
        .maybeSingle()

      if (!latestLicense?.end_date) {
        const nowIso = new Date().toISOString()
        const { error: proUpdateError } = await supabase
          .from("pro_accounts")
          .update({ billing_anchor_day: anchorDay, updated_at: nowIso })
          .eq("id", pro_account_id)

        if (proUpdateError) {
          throw new Error("Failed to update pro account")
        }

        return new Response(
          JSON.stringify({
            success: true,
            pro_account_id,
            billing_anchor_day: anchorDay,
            stripe_subscription_id: null,
            stripe_current_period_end: null,
            updated_licenses: 0,
            updated_users: 0,
            updated_stripe_rows: 0,
            note: "No Stripe subscription or monthly licenses to update"
          }),
          { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      const baseDate = new Date(latestLicense.end_date)
      const newAnchorDate = shiftToAnchorDay(baseDate, anchorDay)
      updatedPeriodEndIso = newAnchorDate.toISOString()
    }

    if (!updatedPeriodEndIso) {
      throw new Error("Failed to compute updated period end")
    }

    const nowIso = new Date().toISOString()

    const { error: proUpdateError } = await supabase
      .from("pro_accounts")
      .update({ billing_anchor_day: anchorDay, updated_at: nowIso })
      .eq("id", pro_account_id)

    if (proUpdateError) {
      throw new Error("Failed to update pro account")
    }

    let stripeSubRows: { id: string }[] = []
    if (stripeSubscriptionId) {
      const { data, error: stripeSubUpdateError } = await supabase
        .from("stripe_subscriptions")
        .update({ current_period_end: updatedPeriodEndIso, updated_at: nowIso })
        .eq("stripe_subscription_id", stripeSubscriptionId)
        .select("id")

      if (stripeSubUpdateError) {
        throw new Error("Failed to update stripe_subscriptions")
      }

      stripeSubRows = data || []
    }

    const { data: licenseRows, error: licenseUpdateError } = await supabase
      .from("licenses")
      .update({ end_date: updatedPeriodEndIso, updated_at: nowIso })
      .eq("pro_account_id", pro_account_id)
      .eq("is_lifetime", false)
      .not("end_date", "is", null)
      .select("id")

    if (licenseUpdateError) {
      throw new Error("Failed to update licenses")
    }

    const { data: linkedUsers, error: linkedUsersError } = await supabase
      .from("licenses")
      .select("linked_account_id, status")
      .eq("pro_account_id", pro_account_id)
      .eq("is_lifetime", false)
      .not("linked_account_id", "is", null)
      .in("status", ["active", "canceled"])

    if (linkedUsersError) {
      throw new Error("Failed to load linked users")
    }

    const userIds = Array.from(new Set(
      (linkedUsers || [])
        .map((row: any) => row.linked_account_id)
        .filter(Boolean)
    ))

    let updatedUsers = 0
    for (const userId of userIds) {
      const { data: updatedUserRows, error: userUpdateError } = await supabase
        .from("users")
        .update({ subscription_expires_at: updatedPeriodEndIso, updated_at: nowIso })
        .eq("id", userId)
        .eq("subscription_type", "LICENSED")
        .select("id")

      if (userUpdateError) {
        throw new Error("Failed to update users")
      }

      updatedUsers += updatedUserRows?.length || 0
    }

    return new Response(
      JSON.stringify({
        success: true,
        pro_account_id,
        billing_anchor_day: anchorDay,
        stripe_subscription_id: stripeSubscriptionId,
        stripe_current_period_end: updatedPeriodEndIso,
        updated_licenses: licenseRows?.length || 0,
        updated_users: updatedUsers,
        updated_stripe_rows: stripeSubRows.length,
        stripe_updated: stripeUpdated,
        stripe_update_error: stripeUpdateError,
        stripe_schedule_id: stripeScheduleId
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Update billing anchor error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
