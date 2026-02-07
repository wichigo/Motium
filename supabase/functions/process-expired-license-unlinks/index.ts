// supabase/functions/process-expired-license-unlinks/index.ts
// Process licenses whose unlink_effective_at has passed.
// Deploy: supabase functions deploy process-expired-license-unlinks

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

interface RequestBody {
  pro_account_id?: string;
}

interface ProAccountRow {
  id: string;
}

interface LicenseRow {
  id: string;
  linked_account_id: string | null;
  is_lifetime: boolean;
  status: string;
  end_date: string | null;
  unlink_effective_at: string | null;
}

interface SyncQuantityResponse {
  success?: boolean;
  error?: string;
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

    let body: RequestBody = {};
    try {
      body = await req.json();
    } catch {
      // empty body is allowed
    }

    const requestedProAccountId = body.pro_account_id?.trim() || null;
    const userToken = authHeader.replace("Bearer ", "");
    const startedAtIso = new Date().toISOString();

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    if (!supabaseUrl || !supabaseAnonKey || !supabaseServiceKey) {
      throw new Error("Missing environment configuration");
    }

    const isServiceRoleCaller = userToken === supabaseServiceKey;
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    console.log(
      `[process-expired-license-unlinks] start at ${startedAtIso} caller=${
        isServiceRoleCaller ? "service_role" : "user"
      } pro_account_id=${requestedProAccountId ?? "all_owned"}`,
    );

    let proAccounts: ProAccountRow[] | null = null;
    if (isServiceRoleCaller) {
      let proAccountQuery = supabase.from("pro_accounts").select("id");
      if (requestedProAccountId) {
        proAccountQuery = proAccountQuery.eq("id", requestedProAccountId);
      }
      const { data, error } = await proAccountQuery;
      if (error) {
        throw new Error(`Failed to load pro accounts: ${error.message}`);
      }
      proAccounts = (data || []) as ProAccountRow[];
    } else {
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
        .maybeSingle();

      if (publicUserError || !publicUser) {
        return new Response(
          JSON.stringify({ success: false, error: "User not found" }),
          {
            status: 404,
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          },
        );
      }

      let proAccountQuery = supabase
        .from("pro_accounts")
        .select("id")
        .eq("user_id", publicUser.id);

      if (requestedProAccountId) {
        proAccountQuery = proAccountQuery.eq("id", requestedProAccountId);
      }

      const { data, error } = await proAccountQuery;
      if (error) {
        throw new Error(`Failed to load pro accounts: ${error.message}`);
      }
      proAccounts = (data || []) as ProAccountRow[];
    }

    if (!proAccounts || proAccounts.length === 0) {
      if (requestedProAccountId) {
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

      return new Response(
        JSON.stringify({
          success: true,
          processed_count: 0,
          deleted_count: 0,
          returned_to_pool_count: 0,
          affected_users_expired: 0,
          pro_accounts_processed: 0,
          reason: "no_pro_accounts",
        }),
        {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        },
      );
    }

    const nowIso = new Date().toISOString();
    const affectedUserIds = new Set<string>();
    let deletedCount = 0;
    let returnedToPoolCount = 0;
    let processedCount = 0;

    for (const proAccount of proAccounts as ProAccountRow[]) {
      // Case A: unlink request reached effective date.
      const { data: dueUnlinkLicenses, error: dueUnlinkError } = await supabase
        .from("licenses")
        .select(
          "id, linked_account_id, is_lifetime, status, end_date, unlink_effective_at",
        )
        .eq("pro_account_id", proAccount.id)
        .not("unlink_effective_at", "is", null)
        .lte("unlink_effective_at", nowIso)
        .neq("status", "available");

      if (dueUnlinkError) {
        throw new Error(
          `Failed to load due unlink licenses for ${proAccount.id}: ${dueUnlinkError.message}`,
        );
      }

      // Case B: canceled license reached end_date.
      const { data: dueCanceledLicenses, error: dueCanceledError } =
        await supabase
          .from("licenses")
          .select(
            "id, linked_account_id, is_lifetime, status, end_date, unlink_effective_at",
          )
          .eq("pro_account_id", proAccount.id)
          .eq("status", "canceled")
          .not("end_date", "is", null)
          .lte("end_date", nowIso);

      if (dueCanceledError) {
        throw new Error(
          `Failed to load due canceled licenses for ${proAccount.id}: ${dueCanceledError.message}`,
        );
      }

      const mergedById = new Map<string, LicenseRow>();
      for (const l of (dueUnlinkLicenses || []) as LicenseRow[]) {
        mergedById.set(l.id, l);
      }
      for (const l of (dueCanceledLicenses || []) as LicenseRow[]) {
        mergedById.set(l.id, l);
      }

      const licensesToProcess = [...mergedById.values()];
      console.log(
        `[process-expired-license-unlinks] pro_account=${proAccount.id} due_unlink=${
          (dueUnlinkLicenses || []).length
        } due_canceled=${
          (dueCanceledLicenses || []).length
        } merged=${licensesToProcess.length}`,
      );

      for (const license of licensesToProcess) {
        if (license.linked_account_id) {
          affectedUserIds.add(license.linked_account_id);

          const { error: linkError } = await supabase
            .from("company_links")
            .update({
              status: "INACTIVE",
              unlinked_at: nowIso,
              updated_at: nowIso,
            })
            .eq("user_id", license.linked_account_id)
            .eq("linked_pro_account_id", proAccount.id)
            .eq("status", "ACTIVE");

          if (linkError) {
            throw new Error(
              `Failed to deactivate company link for ${license.id}: ${linkError.message}`,
            );
          }
        }

        if (license.is_lifetime) {
          const { error: updateError } = await supabase
            .from("licenses")
            .update({
              status: "available",
              linked_account_id: null,
              linked_at: null,
              unlink_requested_at: null,
              unlink_effective_at: null,
              updated_at: nowIso,
            })
            .eq("id", license.id);

          if (updateError) {
            throw new Error(
              `Failed to return lifetime license ${license.id} to pool: ${updateError.message}`,
            );
          }

          returnedToPoolCount += 1;
        } else {
          const { error: deleteError } = await supabase
            .from("licenses")
            .delete()
            .eq("id", license.id);

          if (deleteError) {
            throw new Error(
              `Failed to delete monthly license ${license.id}: ${deleteError.message}`,
            );
          }

          deletedCount += 1;
        }

        processedCount += 1;
      }

      // Keep Stripe quantity aligned after any automatic DB processing.
      // Called for every owner pro account in this run to ensure retries can heal drift.
      const syncResponse = await fetch(
        `${supabaseUrl}/functions/v1/sync-pro-license-quantity`,
        {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${supabaseServiceKey}`,
            "Content-Type": "application/json",
            "apikey": supabaseServiceKey,
          },
          body: JSON.stringify({ pro_account_id: proAccount.id }),
        },
      );

      const syncBodyRaw = await syncResponse.text();
      let syncBody: SyncQuantityResponse = {};
      try {
        syncBody = JSON.parse(syncBodyRaw) as SyncQuantityResponse;
      } catch {
        // Keep raw body in error below when needed.
      }

      if (!syncResponse.ok || syncBody.success === false) {
        throw new Error(
          `Failed to sync Stripe quantity for pro account ${proAccount.id}: ` +
            `HTTP ${syncResponse.status} - ${syncBody.error || syncBodyRaw}`,
        );
      }
      console.log(
        `[process-expired-license-unlinks] sync-pro-license-quantity ok for pro_account=${proAccount.id}`,
      );
    }

    let expiredUsersCount = 0;
    const uniqueAffectedUsers = [...affectedUserIds];

    for (const userId of uniqueAffectedUsers) {
      const { data: otherActiveLicenses, error: checkError } = await supabase
        .from("licenses")
        .select("id")
        .eq("linked_account_id", userId)
        .eq("status", "active")
        .limit(1);

      if (checkError) {
        throw new Error(
          `Failed to check active licenses for user ${userId}: ${checkError.message}`,
        );
      }

      if (otherActiveLicenses && otherActiveLicenses.length > 0) {
        continue;
      }

      const { data: updatedUsers, error: userUpdateError } = await supabase
        .from("users")
        .update({
          subscription_type: "EXPIRED",
          subscription_expires_at: null,
          updated_at: nowIso,
        })
        .eq("id", userId)
        .eq("subscription_type", "LICENSED")
        .select("id");

      if (userUpdateError) {
        throw new Error(
          `Failed to update subscription type for user ${userId}: ${userUpdateError.message}`,
        );
      }

      expiredUsersCount += updatedUsers?.length || 0;
    }

    return new Response(
      JSON.stringify({
        success: true,
        processed_count: processedCount,
        deleted_count: deletedCount,
        returned_to_pool_count: returnedToPoolCount,
        affected_users_expired: expiredUsersCount,
        pro_accounts_processed: proAccounts.length,
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      },
    );
  } catch (error) {
    console.error("process-expired-license-unlinks error:", error);
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
