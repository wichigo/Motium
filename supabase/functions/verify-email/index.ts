// supabase/functions/verify-email/index.ts
// Verifies an email token and marks the email as verified
// Deploy: supabase functions deploy verify-email

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  token: string
}

interface VerifyResult {
  success: boolean
  error?: string
  email?: string
  user_id?: string
  already_verified?: boolean
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
    // Parse request body
    const body: RequestBody = await req.json()
    const { token } = body

    if (!token) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing token" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Supabase client with service role
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Verify token using database function
    const { data: result, error: verifyError } = await supabase
      .rpc('verify_email_token', {
        p_token: token
      })

    if (verifyError) {
      console.error("Token verification error:", verifyError)
      return new Response(
        JSON.stringify({ success: false, error: "Verification failed" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const verifyResult = result as VerifyResult

    if (!verifyResult.success) {
      // Handle specific error cases
      if (verifyResult.error === 'already_verified') {
        return new Response(
          JSON.stringify({
            success: false,
            error: "already_verified",
            already_verified: true,
            email: verifyResult.email
          }),
          { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      if (verifyResult.error === 'token_expired') {
        return new Response(
          JSON.stringify({ success: false, error: "Token expired" }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      return new Response(
        JSON.stringify({ success: false, error: "Invalid token" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    return new Response(
      JSON.stringify({
        success: true,
        email: verifyResult.email,
        user_id: verifyResult.user_id
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Verify email error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
