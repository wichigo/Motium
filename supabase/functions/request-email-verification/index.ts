// supabase/functions/request-email-verification/index.ts
// Creates an email verification token and sends verification email via Resend
// Deploy: supabase functions deploy request-email-verification

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  user_id: string
  email: string
  name?: string
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
    // Verify authorization
    const authHeader = req.headers.get("Authorization")
    if (!authHeader) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing authorization header" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Parse request body
    const body: RequestBody = await req.json()
    const { user_id, email, name } = body

    if (!user_id || !email) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing user_id or email" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Supabase client with service role
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Create verification token using database function
    const { data: tokenResult, error: tokenError } = await supabase
      .rpc('create_email_verification_token', {
        p_user_id: user_id,
        p_email: email
      })

    if (tokenError) {
      console.error("Token creation error:", tokenError)
      return new Response(
        JSON.stringify({ success: false, error: "Failed to create verification token" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const token = tokenResult

    // Send verification email via send-gdpr-email function
    const emailResponse = await fetch(
      `${Deno.env.get('SUPABASE_URL')}/functions/v1/send-gdpr-email`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')}`
        },
        body: JSON.stringify({
          email: email,
          template: 'email_verification',
          data: {
            name: name || '',
            token: token
          }
        })
      }
    )

    if (!emailResponse.ok) {
      const emailError = await emailResponse.text()
      console.error("Email sending error:", emailError)
      // Don't fail the request, just log the error
      // The token was created, user can request a new email
    }

    return new Response(
      JSON.stringify({ success: true }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Request email verification error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
