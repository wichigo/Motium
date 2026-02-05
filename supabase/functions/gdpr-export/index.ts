// supabase/functions/gdpr-export/index.ts
// GDPR Article 15: Right of Access - Data Export
// Deploy: supabase functions deploy gdpr-export

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

interface ExportResponse {
  success: boolean
  request_id?: string
  download_url?: string
  expires_at?: string
  expires_in_hours?: number
  data?: object
  error?: string
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  try {
    // Get auth header
    const authHeader = req.headers.get("Authorization")
    if (!authHeader) {
      return new Response(
        JSON.stringify({ success: false, error: "Unauthorized - No token provided" } as ExportResponse),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY")!
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!

    // Client with user's JWT for RLS
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
        JSON.stringify({ success: false, error: "Invalid or expired token" } as ExportResponse),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Get user's internal ID from users table
    const { data: userData, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, email")
      .eq("auth_id", user.id)
      .single()

    if (userError || !userData) {
      console.error("User lookup error:", userError)
      return new Response(
        JSON.stringify({ success: false, error: "User profile not found" } as ExportResponse),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const userId = userData.id
    const userEmail = userData.email

    // Get request metadata
    const ipAddress = req.headers.get("x-forwarded-for") ||
                      req.headers.get("cf-connecting-ip") ||
                      req.headers.get("x-real-ip") ||
                      "unknown"
    const userAgent = req.headers.get("user-agent") || "unknown"

    // Create GDPR request record
    const { data: requestData, error: requestError } = await supabaseAdmin
      .from("gdpr_data_requests")
      .insert({
        user_id: userId,
        user_email: userEmail,
        request_type: "data_export",
        status: "processing",
        export_format: "json",
        ip_address: ipAddress,
        user_agent: userAgent,
        requested_at: new Date().toISOString()
      })
      .select()
      .single()

    if (requestError) {
      console.error("Failed to create GDPR request:", requestError)
      throw new Error(`Failed to create export request: ${requestError.message}`)
    }

    const requestId = requestData.id

    // Log the action
    await supabaseAdmin.rpc("log_gdpr_action", {
      p_user_id: userId,
      p_action: "data_export_requested",
      p_details: { request_id: requestId },
      p_ip_address: ipAddress,
      p_user_agent: userAgent
    })

    // Call the export function
    const { data: exportData, error: exportError } = await supabaseAdmin
      .rpc("export_user_data_json", { p_user_id: userId })

    if (exportError) {
      console.error("Export function error:", exportError)
      // Update request as failed
      await supabaseAdmin
        .from("gdpr_data_requests")
        .update({
          status: "failed",
          error_message: exportError.message,
          updated_at: new Date().toISOString()
        })
        .eq("id", requestId)

      throw new Error(`Export failed: ${exportError.message}`)
    }

    // Format export data nicely
    const exportJson = JSON.stringify(exportData, null, 2)
    const exportSizeBytes = new TextEncoder().encode(exportJson).length

    // Store export in Supabase Storage
    const fileName = `${user.id}/gdpr-export-${Date.now()}.json`
    const { error: storageError } = await supabaseAdmin
      .storage
      .from("gdpr-exports")
      .upload(fileName, exportJson, {
        contentType: "application/json",
        upsert: false
      })

    const expiresInSeconds = 86400 // 24 hours

    // Generate download URL via our proxy function (hides storage path, forces download)
    let downloadUrl: string | null = null
    if (!storageError) {
      // Use public URL for the download endpoint
      const publicApiUrl = "https://api.motium.app"
      const downloadToken = await createDownloadToken(supabaseServiceKey, requestId)
      downloadUrl = `${publicApiUrl}/functions/v1/gdpr-download?request_id=${requestId}&token=${downloadToken}`
    } else {
      console.warn("Storage upload error (non-fatal):", storageError)
    }

    const expiresAt = new Date(Date.now() + expiresInSeconds * 1000).toISOString()

    // Update request as completed
    await supabaseAdmin
      .from("gdpr_data_requests")
      .update({
        status: "completed",
        completed_at: new Date().toISOString(),
        processed_at: new Date().toISOString(),
        export_file_url: downloadUrl,
        export_size_bytes: exportSizeBytes,
        expires_at: expiresAt,
        processed_by: "system",
        updated_at: new Date().toISOString()
      })
      .eq("id", requestId)

    // Log completion
    await supabaseAdmin.rpc("log_gdpr_action", {
      p_user_id: userId,
      p_action: "data_export_completed",
      p_details: {
        request_id: requestId,
        size_bytes: exportSizeBytes,
        has_download_url: !!downloadUrl
      },
      p_ip_address: ipAddress,
      p_user_agent: userAgent
    })

    // Send email notification (if email function exists)
    try {
      await fetch(`${supabaseUrl}/functions/v1/send-gdpr-email`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${supabaseServiceKey}`
        },
        body: JSON.stringify({
          email: userEmail,
          template: "export_ready",
          data: {
            download_url: downloadUrl,
            expires_in_hours: 24
          }
        })
      })
    } catch (emailError) {
      // Non-fatal - log but don't fail the request
      console.warn("Failed to send email notification:", emailError)
    }

    // Return export data directly (and download URL if available)
    const response: ExportResponse = {
      success: true,
      request_id: requestId,
      download_url: downloadUrl || undefined,
      expires_at: expiresAt,
      expires_in_hours: 24,
      data: exportData
    }

    return new Response(JSON.stringify(response), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    })

  } catch (error) {
    console.error("GDPR Export error:", error)
    const response: ExportResponse = {
      success: false,
      error: error instanceof Error ? error.message : "An unexpected error occurred"
    }
    return new Response(JSON.stringify(response), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    })
  }
})
