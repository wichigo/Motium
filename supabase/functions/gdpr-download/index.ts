// supabase/functions/gdpr-download/index.ts
// GDPR Data Export Download - Proxy for secure file download
// Deploy: supabase functions deploy gdpr-download

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.0"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

const textEncoder = new TextEncoder()

const base64UrlEncode = (data: ArrayBuffer): string => {
  let binary = ""
  const bytes = new Uint8Array(data)
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

const createDownloadToken = async (secret: string, requestId: string): Promise<string> => {
  const key = await crypto.subtle.importKey(
    "raw",
    textEncoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  )
  const signature = await crypto.subtle.sign("HMAC", key, textEncoder.encode(requestId))
  return base64UrlEncode(signature)
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  try {
    // Get request_id from query params
    const url = new URL(req.url)
    const requestId = url.searchParams.get("request_id")
    const downloadToken = url.searchParams.get("token")

    if (!requestId) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing request_id parameter" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Get auth header or download token
    const authHeader = req.headers.get("Authorization")
    if (!authHeader && !downloadToken) {
      return new Response(
        JSON.stringify({ success: false, error: "Unauthorized - No token or auth header provided" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY")!
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!

    // Service client for admin operations
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey)

    let authUserId: string | null = null
    let internalUserId: string | null = null

    if (authHeader) {
      // Client with user's JWT for RLS
      const supabaseUser = createClient(supabaseUrl, supabaseAnonKey, {
        global: { headers: { Authorization: authHeader } }
      })

      // Get current user from JWT
      const { data: { user }, error: authError } = await supabaseUser.auth.getUser()
      if (authError || !user) {
        console.error("Auth error:", authError)
        return new Response(
          JSON.stringify({ success: false, error: "Invalid or expired token" }),
          { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      authUserId = user.id

      // Get user's internal ID from users table
      const { data: userData, error: userError } = await supabaseAdmin
        .from("users")
        .select("id")
        .eq("auth_id", user.id)
        .single()

      if (userError || !userData) {
        return new Response(
          JSON.stringify({ success: false, error: "User profile not found" }),
          { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      internalUserId = userData.id
    }

    // Get the GDPR request and verify ownership
    const { data: requestData, error: requestError } = await supabaseAdmin
      .from("gdpr_data_requests")
      .select("id, user_id, status, export_file_url, expires_at")
      .eq("id", requestId)
      .single()

    if (requestError || !requestData) {
      return new Response(
        JSON.stringify({ success: false, error: "Export request not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Verify access: either valid auth header or valid download token
    if (authHeader) {
      if (requestData.user_id !== internalUserId) {
        return new Response(
          JSON.stringify({ success: false, error: "Access denied" }),
          { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }
    } else {
      const expectedToken = await createDownloadToken(supabaseServiceKey, requestId)
      if (!downloadToken || downloadToken !== expectedToken) {
        return new Response(
          JSON.stringify({ success: false, error: "Unauthorized - Invalid download token" }),
          { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }
    }

    // Check if export is completed
    if (requestData.status !== "completed") {
      return new Response(
        JSON.stringify({ success: false, error: "Export not ready yet" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Check if export has expired
    if (requestData.expires_at && new Date(requestData.expires_at) < new Date()) {
      return new Response(
        JSON.stringify({ success: false, error: "Export has expired" }),
        { status: 410, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    let storageUserId = authUserId
    if (!storageUserId) {
      if (!requestData.user_id) {
        return new Response(
          JSON.stringify({ success: false, error: "User profile not found" }),
          { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      const { data: authData, error: authIdError } = await supabaseAdmin
        .from("users")
        .select("auth_id")
        .eq("id", requestData.user_id)
        .single()

      if (authIdError || !authData?.auth_id) {
        return new Response(
          JSON.stringify({ success: false, error: "User profile not found" }),
          { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      storageUserId = authData.auth_id
    }

    // Try to find the actual file - it might have a timestamp instead of request_id
    const { data: files, error: listError } = await supabaseAdmin
      .storage
      .from("gdpr-exports")
      .list(storageUserId, { limit: 100 })

    if (listError || !files || files.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "Export file not found in storage" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Find the most recent export file for this user
    const exportFiles = files
      .filter(f => f.name.startsWith("gdpr-export-") && f.name.endsWith(".json"))
      .sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime())

    if (exportFiles.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "No export files found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const latestFile = exportFiles[0]
    const filePath = `${storageUserId}/${latestFile.name}`

    // Download the file content
    const { data: fileData, error: downloadError } = await supabaseAdmin
      .storage
      .from("gdpr-exports")
      .download(filePath)

    if (downloadError || !fileData) {
      console.error("Download error:", downloadError)
      return new Response(
        JSON.stringify({ success: false, error: "Failed to download export file" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Log the download
    const ipAddress = req.headers.get("x-forwarded-for") ||
                      req.headers.get("cf-connecting-ip") ||
                      req.headers.get("x-real-ip") ||
                      "unknown"
    const userAgent = req.headers.get("user-agent") || "unknown"

    const logUserId = requestData.user_id || internalUserId
    if (logUserId) {
      await supabaseAdmin.rpc("log_gdpr_action", {
        p_user_id: logUserId,
        p_action: "data_export_downloaded",
        p_details: { request_id: requestId, file_name: latestFile.name },
        p_ip_address: ipAddress,
        p_user_agent: userAgent
      })
    }

    // Generate filename with date
    const exportDate = new Date().toISOString().split("T")[0]
    const downloadFileName = `motium-gdpr-export-${exportDate}.json`

    // Return the file with download headers
    return new Response(fileData, {
      status: 200,
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json",
        "Content-Disposition": `attachment; filename="${downloadFileName}"`,
        "Cache-Control": "no-store, no-cache, must-revalidate",
      }
    })

  } catch (error) {
    console.error("GDPR Download error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
