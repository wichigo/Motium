// supabase/functions/gdpr-delete-account/index.ts
// GDPR Article 17: Right to Erasure - Account Deletion
// Deploy: supabase functions deploy gdpr-delete-account

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.0"
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface DeleteRequest {
  confirmation: string  // Must be "DELETE_MY_ACCOUNT"
  reason?: string       // Optional deletion reason
}

interface DeletionSummary {
  user_id: string
  email: string
  deleted_at: string
  reason?: string
  counts: {
    trips: number
    vehicles: number
    expenses: number
    work_schedules: number
    company_links: number
    consents: number
  }
  stripe_cleanup: string
}

interface DeleteResponse {
  success: boolean
  message?: string
  deletion_summary?: DeletionSummary
  error?: string
}

const TERMINAL_STRIPE_STATUSES = new Set([
  "canceled",
  "incomplete_expired",
])

function isCancelableStripeStatus(status: string): boolean {
  return !TERMINAL_STRIPE_STATUSES.has(status)
}

function normalizeStripeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  // Only accept POST
  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ success: false, error: "Method not allowed" } as DeleteResponse),
      { status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }

  try {
    // Get auth header
    const authHeader = req.headers.get("Authorization")
    if (!authHeader) {
      return new Response(
        JSON.stringify({ success: false, error: "Unauthorized - No token provided" } as DeleteResponse),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Parse request body
    let body: DeleteRequest
    try {
      body = await req.json()
    } catch {
      return new Response(
        JSON.stringify({ success: false, error: "Invalid request body" } as DeleteResponse),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const { confirmation, reason } = body

    // Require explicit confirmation
    if (confirmation !== "DELETE_MY_ACCOUNT") {
      return new Response(
        JSON.stringify({
          success: false,
          error: "Please confirm deletion by setting confirmation to 'DELETE_MY_ACCOUNT'"
        } as DeleteResponse),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY")!
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
    const stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY")

    // Client with user's JWT
    const supabaseUser = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: authHeader } }
    })

    // Service client for admin operations
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey)

    // Get current user from JWT
    const { data: { user }, error: authError } = await supabaseUser.auth.getUser()
    if (authError || !user) {
      console.error("Auth error:", authError)
      return new Response(
        JSON.stringify({ success: false, error: "Invalid or expired token" } as DeleteResponse),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Get user data before deletion
    const { data: userData, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, email, stripe_customer_id")
      .eq("auth_id", user.id)
      .single()

    if (userError || !userData) {
      console.error("User lookup error:", userError)
      return new Response(
        JSON.stringify({ success: false, error: "User profile not found" } as DeleteResponse),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const userId = userData.id
    const userEmail = userData.email
    const stripeCustomerId = userData.stripe_customer_id

    // Get request metadata
    const ipAddress = req.headers.get("x-forwarded-for") ||
                      req.headers.get("cf-connecting-ip") ||
                      req.headers.get("x-real-ip") ||
                      "unknown"
    const userAgent = req.headers.get("user-agent") || "unknown"

    // Create GDPR deletion request record
    const { data: requestData, error: requestError } = await supabaseAdmin
      .from("gdpr_data_requests")
      .insert({
        user_id: userId,
        user_email: userEmail,
        request_type: "data_deletion",
        status: "processing",
        deletion_reason: reason || null,
        ip_address: ipAddress,
        user_agent: userAgent,
        requested_at: new Date().toISOString()
      })
      .select()
      .single()

    if (requestError) {
      console.error("Failed to create GDPR request:", requestError)
      throw new Error(`Failed to create deletion request: ${requestError.message}`)
    }

    const requestId = requestData.id

    // Log the deletion request
    await supabaseAdmin.rpc("log_gdpr_action", {
      p_user_id: userId,
      p_action: "data_deletion_requested",
      p_details: { request_id: requestId, reason: reason || null },
      p_ip_address: ipAddress,
      p_user_agent: userAgent
    })

    const { data: dbSubscriptions, error: dbSubscriptionsError } = await supabaseAdmin
      .from("stripe_subscriptions")
      .select("stripe_subscription_id, stripe_customer_id")
      .eq("user_id", userId)

    if (dbSubscriptionsError) {
      console.warn("Unable to load stripe_subscriptions fallback data:", dbSubscriptionsError)
    }

    const dbCustomerIds = Array.from(
      new Set(
        (dbSubscriptions ?? [])
          .map((s) => s.stripe_customer_id)
          .filter((id): id is string => !!id)
      )
    )
    const dbSubscriptionIds = Array.from(
      new Set(
        (dbSubscriptions ?? [])
          .map((s) => s.stripe_subscription_id)
          .filter((id): id is string => !!id)
      )
    )

    const effectiveStripeCustomerId = stripeCustomerId || dbCustomerIds[0] || null
    let stripeCleanupStatus = "not_required"

    // Handle Stripe cleanup if customer exists
    if ((effectiveStripeCustomerId || dbSubscriptionIds.length > 0) && !stripeSecretKey) {
      stripeCleanupStatus = "failed: missing STRIPE_SECRET_KEY"
      console.error("Stripe cleanup skipped: STRIPE_SECRET_KEY is missing")
    } else if ((effectiveStripeCustomerId || dbSubscriptionIds.length > 0) && stripeSecretKey) {
      try {
        const stripe = new Stripe(stripeSecretKey, {
          apiVersion: "2023-10-16",
          httpClient: Stripe.createFetchHttpClient(),
        })

        console.log(
          `Processing Stripe cleanup (customer: ${effectiveStripeCustomerId ?? "n/a"}, db_subscriptions: ${dbSubscriptionIds.length})`
        )

        let canceledCount = 0
        let skippedCount = 0
        const canceledSubscriptionIds = new Set<string>()
        const cleanupErrors: string[] = []

        if (effectiveStripeCustomerId) {
          let startingAfter: string | undefined

          do {
            const subscriptionsPage = await stripe.subscriptions.list({
              customer: effectiveStripeCustomerId,
              status: "all",
              limit: 100,
              ...(startingAfter ? { starting_after: startingAfter } : {}),
            })

            for (const sub of subscriptionsPage.data) {
              if (!isCancelableStripeStatus(sub.status)) {
                skippedCount += 1
                continue
              }

              try {
                console.log(`Canceling customer subscription: ${sub.id} (status: ${sub.status})`)
                await stripe.subscriptions.cancel(sub.id, {
                  prorate: false,
                  invoice_now: false,
                })
                canceledCount += 1
                canceledSubscriptionIds.add(sub.id)
              } catch (cancelError) {
                const message = normalizeStripeError(cancelError)
                cleanupErrors.push(`cancel ${sub.id}: ${message}`)
                console.error(`Failed to cancel subscription ${sub.id}:`, cancelError)
              }
            }

            if (subscriptionsPage.has_more && subscriptionsPage.data.length > 0) {
              startingAfter = subscriptionsPage.data[subscriptionsPage.data.length - 1].id
            } else {
              startingAfter = undefined
            }
          } while (startingAfter)
        }

        for (const dbSubId of dbSubscriptionIds) {
          if (canceledSubscriptionIds.has(dbSubId)) {
            continue
          }

          try {
            const dbSub = await stripe.subscriptions.retrieve(dbSubId)
            if (!isCancelableStripeStatus(dbSub.status)) {
              skippedCount += 1
              continue
            }

            console.log(`Canceling fallback DB subscription: ${dbSubId} (status: ${dbSub.status})`)
            await stripe.subscriptions.cancel(dbSubId, {
              prorate: false,
              invoice_now: false,
            })
            canceledCount += 1
            canceledSubscriptionIds.add(dbSubId)
          } catch (fallbackError) {
            const message = normalizeStripeError(fallbackError)
            cleanupErrors.push(`fallback ${dbSubId}: ${message}`)
            console.error(`Failed fallback cancellation for ${dbSubId}:`, fallbackError)
          }
        }

        if (effectiveStripeCustomerId) {
          try {
            await stripe.customers.del(effectiveStripeCustomerId)
            console.log(`Stripe customer ${effectiveStripeCustomerId} deleted`)
          } catch (customerDeleteError) {
            const message = normalizeStripeError(customerDeleteError)
            cleanupErrors.push(`delete_customer ${effectiveStripeCustomerId}: ${message}`)
            console.error(`Failed to delete Stripe customer ${effectiveStripeCustomerId}:`, customerDeleteError)
          }
        }

        if (cleanupErrors.length > 0) {
          stripeCleanupStatus = `partial_failed: canceled=${canceledCount}, skipped=${skippedCount}, errors=${cleanupErrors.length}`
        } else {
          stripeCleanupStatus = `completed: canceled=${canceledCount}, skipped=${skippedCount}`
        }

      } catch (stripeError) {
        console.error("Stripe cleanup error:", stripeError)
        stripeCleanupStatus = `failed: ${normalizeStripeError(stripeError)}`
        // Continue with deletion even if Stripe fails
      }
    }

    // Call the complete data deletion function
    const { data: deletionResult, error: deletionError } = await supabaseAdmin
      .rpc("delete_user_data_complete", {
        p_user_id: userId,
        p_deletion_reason: reason || null,
        p_cancel_stripe: false  // Already handled above
      })

    if (deletionError) {
      console.error("Deletion function error:", deletionError)
      // Update request as failed
      await supabaseAdmin
        .from("gdpr_data_requests")
        .update({
          status: "failed",
          error_message: deletionError.message,
          stripe_cleanup_status: stripeCleanupStatus,
          updated_at: new Date().toISOString()
        })
        .eq("id", requestId)

      throw new Error(`Deletion failed: ${deletionError.message}`)
    }

    // Delete from Supabase Auth
    const { error: authDeleteError } = await supabaseAdmin.auth.admin.deleteUser(user.id)
    if (authDeleteError) {
      console.error("Auth deletion error (non-fatal):", authDeleteError)
      // Continue - user data is already deleted
    }

    // Update request as completed (user record is gone, update by request ID)
    await supabaseAdmin
      .from("gdpr_data_requests")
      .update({
        user_id: null,  // User no longer exists
        status: "completed",
        completed_at: new Date().toISOString(),
        processed_at: new Date().toISOString(),
        data_deleted: deletionResult,
        stripe_cleanup_status: stripeCleanupStatus,
        processed_by: "system",
        updated_at: new Date().toISOString()
      })
      .eq("id", requestId)

    // Send confirmation email
    try {
      await fetch(`${supabaseUrl}/functions/v1/send-gdpr-email`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${supabaseServiceKey}`
        },
        body: JSON.stringify({
          email: userEmail,
          template: "account_deleted",
          data: {
            deletion_date: new Date().toISOString(),
            counts: deletionResult?.counts || {}
          }
        })
      })
    } catch (emailError) {
      // Non-fatal - log but don't fail the request
      console.warn("Failed to send deletion confirmation email:", emailError)
    }

    // Build response
    const deletionSummary: DeletionSummary = {
      user_id: userId,
      email: userEmail,
      deleted_at: new Date().toISOString(),
      reason: reason,
      counts: deletionResult?.counts || {
        trips: 0,
        vehicles: 0,
        expenses: 0,
        work_schedules: 0,
        company_links: 0,
        consents: 0
      },
      stripe_cleanup: stripeCleanupStatus
    }

    const response: DeleteResponse = {
      success: true,
      message: "Votre compte et toutes les donnees associees ont ete definitivement supprimes.",
      deletion_summary: deletionSummary
    }

    return new Response(JSON.stringify(response), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    })

  } catch (error) {
    console.error("GDPR Deletion error:", error)
    const response: DeleteResponse = {
      success: false,
      error: error instanceof Error ? error.message : "An unexpected error occurred"
    }
    return new Response(JSON.stringify(response), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    })
  }
})
