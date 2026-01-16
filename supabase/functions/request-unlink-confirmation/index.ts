// supabase/functions/request-unlink-confirmation/index.ts
// Creates an unlink confirmation token and sends confirmation email
// Deploy: supabase functions deploy request-unlink-confirmation

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  company_link_id: string
  initiated_by: "employee" | "pro_account"
  initiator_email: string
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
    const { company_link_id, initiated_by, initiator_email } = body

    if (!company_link_id || !initiated_by || !initiator_email) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing required fields" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    if (initiated_by !== 'employee' && initiated_by !== 'pro_account') {
      return new Response(
        JSON.stringify({ success: false, error: "Invalid initiated_by value" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Supabase client with service role
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Create unlink confirmation token using database function
    const { data: tokenResult, error: tokenError } = await supabase
      .rpc('create_unlink_confirmation_token', {
        p_company_link_id: company_link_id,
        p_initiated_by: initiated_by,
        p_initiator_email: initiator_email
      })

    if (tokenError) {
      console.error("Token creation error:", tokenError)
      return new Response(
        JSON.stringify({ success: false, error: "Failed to create unlink token" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const result = tokenResult as {
      success: boolean
      error?: string
      token?: string
      employee_email?: string
      employee_name?: string
      company_name?: string
      pro_account_email?: string
      initiated_by?: string
    }

    if (!result.success) {
      return new Response(
        JSON.stringify({ success: false, error: result.error || "Failed to create token" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Send confirmation email to the initiator
    const emailResponse = await fetch(
      `${Deno.env.get('SUPABASE_URL')}/functions/v1/send-gdpr-email`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')}`
        },
        body: JSON.stringify({
          email: initiator_email,
          template: 'unlink_confirmation',
          data: {
            token: result.token,
            employee_name: result.employee_name,
            company_name: result.company_name,
            initiated_by: initiated_by
          }
        })
      }
    )

    if (!emailResponse.ok) {
      const emailError = await emailResponse.text()
      console.error("Email sending error:", emailError)
      // Don't fail the request, the token was created
    }

    return new Response(
      JSON.stringify({
        success: true,
        message: "Confirmation email sent"
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Request unlink confirmation error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
