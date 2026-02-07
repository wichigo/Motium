// supabase/functions/sync-pro-license-quantity/index.ts
// Align Stripe subscription quantity with current billable monthly licenses.
// Deploy: supabase functions deploy sync-pro-license-quantity

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import Stripe from "https://esm.sh/stripe@14.21.0?target=deno";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

interface RequestBody {
  pro_account_id: string;
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ success: false, error: "Method not allowed" }),
      {
        status: 405,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      },
    );
  }

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      return new Response(
        JSON.stringify({
          success: false,
          error: "Missing or invalid authorization",
        }),
        {
          status: 401,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    const userToken = authHeader.replace("Bearer ", "");
    const body: RequestBody = await req.json();
    const proAccountId = body.pro_account_id;

    if (!proAccountId) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing pro_account_id" }),
        {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const stripeSecretKey = Deno.env.get("STRIPE_SECRET_KEY") ?? "";

    if (
      !supabaseUrl || !supabaseAnonKey || !supabaseServiceKey ||
      !stripeSecretKey
    ) {
      throw new Error("Missing environment configuration");
    }

    const supabase = createClient(supabaseUrl, supabaseServiceKey);
    const isServiceRoleCaller = userToken === supabaseServiceKey;

    const { data: proAccount, error: proError } = await supabase
      .from("pro_accounts")
      .select("id, user_id, stripe_subscription_id")
      .eq("id", proAccountId)
      .single();

    if (proError || !proAccount) {
      return new Response(
        JSON.stringify({ success: false, error: "Pro account not found" }),
        {
          status: 404,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    if (!isServiceRoleCaller) {
      const supabaseUser = createClient(supabaseUrl, supabaseAnonKey, {
        global: { headers: { Authorization: `Bearer ${userToken}` } },
      });

      const { data: authUser, error: authError } = await supabaseUser.auth
        .getUser();
      if (authError || !authUser?.user) {
        return new Response(
          JSON.stringify({ success: false, error: "Invalid token" }),
          {
            status: 401,
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          },
        );
      }

      const { data: publicUser, error: publicUserError } = await supabase
        .from("users")
        .select("id")
        .eq("auth_id", authUser.user.id)
        .single();

      if (publicUserError || !publicUser) {
        return new Response(
          JSON.stringify({ success: false, error: "User not found" }),
          {
            status: 404,
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          },
        );
      }

      if (proAccount.user_id !== publicUser.id) {
        return new Response(
          JSON.stringify({
            success: false,
            error: "Not authorized for this pro account",
          }),
          {
            status: 403,
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          },
        );
      }
    }

    let stripeSubscriptionId: string | null =
      proAccount.stripe_subscription_id || null;
    if (!stripeSubscriptionId) {
      const { data: subFallback } = await supabase
        .from("stripe_subscriptions")
        .select("stripe_subscription_id")
        .eq("pro_account_id", proAccountId)
        .eq("subscription_type", "pro_license_monthly")
        .in("status", ["active", "trialing", "past_due", "incomplete"])
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();

      stripeSubscriptionId = subFallback?.stripe_subscription_id || null;
    }

    if (!stripeSubscriptionId) {
      return new Response(
        JSON.stringify({
          success: true,
          action: "noop",
          reason: "no_active_monthly_subscription",
        }),
        {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    const stripe = new Stripe(stripeSecretKey, {
      apiVersion: "2023-10-16",
      httpClient: Stripe.createFetchHttpClient(),
    });

    const subscription = await stripe.subscriptions.retrieve(
      stripeSubscriptionId,
    );
    const subscriptionItem = subscription.items?.data?.[0];

    if (!subscriptionItem?.id) {
      return new Response(
        JSON.stringify({ success: false, error: "Subscription has no item" }),
        {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    const nowIso = new Date().toISOString();
    const { data: monthlyLicenses, error: licensesError } = await supabase
      .from("licenses")
      .select("id, status, unlink_effective_at")
      .eq("pro_account_id", proAccountId)
      .eq("is_lifetime", false);

    if (licensesError) {
      throw new Error(`Failed to load licenses: ${licensesError.message}`);
    }

    // Same eligibility rule as invoice.upcoming (defensive fallback):
    // canceled rows are never billed, and rows already effective for unlink are excluded.
    const targetQuantity = (monthlyLicenses || []).filter((l: any) => {
      if (!l) return false;
      if (l.status === "canceled") return false;
      if (l.unlink_effective_at && l.unlink_effective_at <= nowIso) {
        return false;
      }
      return true;
    }).length;

    const currentQuantity = subscriptionItem.quantity || 0;
    if (targetQuantity <= 0) {
      return new Response(
        JSON.stringify({
          success: true,
          action: "noop",
          reason: "target_quantity_zero",
          current_quantity: currentQuantity,
          target_quantity: targetQuantity,
        }),
        {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    if (targetQuantity === currentQuantity) {
      await supabase.from("stripe_subscriptions")
        .update({
          quantity: targetQuantity,
          updated_at: nowIso,
        })
        .eq("stripe_subscription_id", stripeSubscriptionId);

      return new Response(
        JSON.stringify({
          success: true,
          action: "noop",
          reason: "already_aligned",
          current_quantity: currentQuantity,
          target_quantity: targetQuantity,
        }),
        {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    await stripe.subscriptions.update(stripeSubscriptionId, {
      items: [{ id: subscriptionItem.id, quantity: targetQuantity }],
      proration_behavior: "none",
      metadata: {
        ...(subscription.metadata || {}),
        quantity: targetQuantity.toString(),
        quantity_synced_at: nowIso,
      },
    });

    const unitPrice = subscriptionItem?.price?.unit_amount || null;
    const totalAmountCents = unitPrice !== null
      ? unitPrice * targetQuantity
      : null;

    await supabase.from("stripe_subscriptions")
      .update({
        quantity: targetQuantity,
        unit_amount_cents: totalAmountCents,
        updated_at: nowIso,
      })
      .eq("stripe_subscription_id", stripeSubscriptionId);

    return new Response(
      JSON.stringify({
        success: true,
        action: "updated",
        stripe_subscription_id: stripeSubscriptionId,
        old_quantity: currentQuantity,
        new_quantity: targetQuantity,
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      },
    );
  } catch (error) {
    console.error("sync-pro-license-quantity error:", error);
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "Unexpected error",
      }),
      {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      },
    );
  }
});
