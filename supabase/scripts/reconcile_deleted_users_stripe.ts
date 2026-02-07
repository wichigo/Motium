// Reconcile Stripe subscriptions for already deleted GDPR accounts.
// Dry run by default.
//
// Usage:
//   deno run --allow-env --allow-net supabase/scripts/reconcile_deleted_users_stripe.ts
//   deno run --allow-env --allow-net supabase/scripts/reconcile_deleted_users_stripe.ts --execute
//   deno run --allow-env --allow-net supabase/scripts/reconcile_deleted_users_stripe.ts --execute --limit=300
//
// Required env:
//   SUPABASE_URL
//   SUPABASE_SERVICE_ROLE_KEY
//   STRIPE_SECRET_KEY

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.0"
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno"

const TERMINAL_STRIPE_STATUSES = new Set([
  "canceled",
  "incomplete_expired",
])

function isCancelableStripeStatus(status: string): boolean {
  return !TERMINAL_STRIPE_STATUSES.has(status)
}

function parseLimit(args: string[]): number {
  const limitArg = args.find((arg) => arg.startsWith("--limit="))
  if (!limitArg) {
    return 200
  }
  const parsed = Number.parseInt(limitArg.replace("--limit=", ""), 10)
  if (Number.isNaN(parsed) || parsed <= 0) {
    return 200
  }
  return Math.min(parsed, 1000)
}

function normalizeStripeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}

const shouldExecute = Deno.args.includes("--execute")
const limit = parseLimit(Deno.args)

const supabaseUrl = Deno.env.get("SUPABASE_URL")
const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
const stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY")

if (!supabaseUrl || !supabaseServiceKey || !stripeSecretKey) {
  console.error("Missing required env vars: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, STRIPE_SECRET_KEY")
  Deno.exit(1)
}

const supabase = createClient(supabaseUrl, supabaseServiceKey)
const stripe = new Stripe(stripeSecretKey, {
  apiVersion: "2023-10-16",
  httpClient: Stripe.createFetchHttpClient(),
})

type GdprRequestRow = {
  id: string
  stripe_cleanup_status: string | null
  data_deleted: {
    stripe_customer_id?: string | null
  } | null
}

const { data, error } = await supabase
  .from("gdpr_data_requests")
  .select("id, stripe_cleanup_status, data_deleted")
  .eq("request_type", "data_deletion")
  .eq("status", "completed")
  .order("requested_at", { ascending: false })
  .limit(limit)

if (error) {
  console.error("Failed to load gdpr_data_requests:", error)
  Deno.exit(1)
}

const rows = ((data ?? []) as GdprRequestRow[]).filter((row) => {
  const status = row.stripe_cleanup_status ?? ""
  return !status.startsWith("completed")
})

console.log(`Mode: ${shouldExecute ? "EXECUTE" : "DRY_RUN"}`)
console.log(`Loaded: ${data?.length ?? 0}, pending reconcile: ${rows.length}`)

let processed = 0
let completed = 0
let failed = 0
let skipped = 0

for (const row of rows) {
  processed += 1
  const customerId = row.data_deleted?.stripe_customer_id ?? null

  if (!customerId) {
    skipped += 1
    if (shouldExecute) {
      await supabase
        .from("gdpr_data_requests")
        .update({
          stripe_cleanup_status: "not_required: no stripe_customer_id",
          updated_at: new Date().toISOString(),
        })
        .eq("id", row.id)
    }
    continue
  }

  let canceledCount = 0
  let skippedCount = 0
  const errors: string[] = []
  let startingAfter: string | undefined

  try {
    do {
      const page = await stripe.subscriptions.list({
        customer: customerId,
        status: "all",
        limit: 100,
        ...(startingAfter ? { starting_after: startingAfter } : {}),
      })

      for (const sub of page.data) {
        if (!isCancelableStripeStatus(sub.status)) {
          skippedCount += 1
          continue
        }

        if (!shouldExecute) {
          canceledCount += 1
          continue
        }

        try {
          await stripe.subscriptions.cancel(sub.id, {
            prorate: false,
            invoice_now: false,
          })
          canceledCount += 1
        } catch (cancelError) {
          errors.push(`cancel ${sub.id}: ${normalizeStripeError(cancelError)}`)
        }
      }

      if (page.has_more && page.data.length > 0) {
        startingAfter = page.data[page.data.length - 1].id
      } else {
        startingAfter = undefined
      }
    } while (startingAfter)

    if (shouldExecute) {
      try {
        await stripe.customers.del(customerId)
      } catch (customerError) {
        errors.push(`delete_customer ${customerId}: ${normalizeStripeError(customerError)}`)
      }

      const status = errors.length > 0
        ? `partial_failed_backfill: canceled=${canceledCount}, skipped=${skippedCount}, errors=${errors.length}`
        : `completed_backfill: canceled=${canceledCount}, skipped=${skippedCount}`

      await supabase
        .from("gdpr_data_requests")
        .update({
          stripe_cleanup_status: status,
          updated_at: new Date().toISOString(),
        })
        .eq("id", row.id)
    }

    if (errors.length > 0) {
      failed += 1
      console.error(`[${row.id}] FAILED customer=${customerId}:`, errors.join(" | "))
    } else {
      completed += 1
      console.log(`[${row.id}] OK customer=${customerId} canceled=${canceledCount} skipped=${skippedCount}`)
    }
  } catch (runError) {
    failed += 1
    const message = normalizeStripeError(runError)
    console.error(`[${row.id}] ERROR customer=${customerId}: ${message}`)

    if (shouldExecute) {
      await supabase
        .from("gdpr_data_requests")
        .update({
          stripe_cleanup_status: `failed_backfill: ${message}`,
          updated_at: new Date().toISOString(),
        })
        .eq("id", row.id)
    }
  }
}

console.log("Summary:")
console.log(`  processed=${processed}`)
console.log(`  completed=${completed}`)
console.log(`  failed=${failed}`)
console.log(`  skipped=${skipped}`)
