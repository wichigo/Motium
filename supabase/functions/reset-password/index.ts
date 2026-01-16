// supabase/functions/reset-password/index.ts
// Handles password reset token validation and password update
// Deploy: supabase functions deploy reset-password
//
// Required environment variables:
// - SUPABASE_URL: Supabase project URL
// - SUPABASE_SERVICE_ROLE_KEY: Service role key for admin operations

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.38.4"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface ResetPasswordRequest {
  token: string
  new_password: string
}

interface ResetPasswordResponse {
  success: boolean
  error?: string
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  // Only accept POST
  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ success: false, error: "Method not allowed" } as ResetPasswordResponse),
      { status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }

  try {
    // Get Supabase credentials
    const supabaseUrl = Deno.env.get("SUPABASE_URL")
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")

    if (!supabaseUrl || !supabaseServiceKey) {
      console.error("Missing Supabase credentials")
      return new Response(
        JSON.stringify({ success: false, error: "Server configuration error" } as ResetPasswordResponse),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create admin client
    const supabase = createClient(supabaseUrl, supabaseServiceKey, {
      auth: {
        autoRefreshToken: false,
        persistSession: false
      }
    })

    // Parse request body
    const body: ResetPasswordRequest = await req.json()
    const { token, new_password } = body

    // Validate input
    if (!token || !new_password) {
      return new Response(
        JSON.stringify({ success: false, error: "Token et nouveau mot de passe requis" } as ResetPasswordResponse),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Validate password strength
    if (new_password.length < 8) {
      return new Response(
        JSON.stringify({ success: false, error: "Le mot de passe doit contenir au moins 8 caracteres" } as ResetPasswordResponse),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Validate token using the database function
    const { data: tokenResult, error: tokenError } = await supabase
      .rpc('validate_password_reset_token', { p_token: token })

    if (tokenError) {
      console.error("Token validation error:", tokenError)
      return new Response(
        JSON.stringify({ success: false, error: "Erreur de validation du token" } as ResetPasswordResponse),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    if (!tokenResult || tokenResult.length === 0 || !tokenResult[0].is_valid) {
      const errorMessage = tokenResult?.[0]?.error_message || "Token invalide ou expire"
      return new Response(
        JSON.stringify({ success: false, error: errorMessage } as ResetPasswordResponse),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const userId = tokenResult[0].user_id

    // Update the user's password using admin API
    const { error: updateError } = await supabase.auth.admin.updateUserById(
      userId,
      { password: new_password }
    )

    if (updateError) {
      console.error("Password update error:", updateError)
      return new Response(
        JSON.stringify({ success: false, error: "Erreur lors de la mise a jour du mot de passe" } as ResetPasswordResponse),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    console.log(`Password successfully reset for user ${userId}`)

    return new Response(
      JSON.stringify({ success: true } as ResetPasswordResponse),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Reset password error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "Une erreur inattendue s'est produite"
      } as ResetPasswordResponse),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
