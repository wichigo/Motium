// supabase/functions/confirm-unlink/index.ts
// Confirms an unlink token and performs the account unlinking
// Deploy: supabase functions deploy confirm-unlink

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  token: string
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

    // Confirm unlink using database function
    const { data: result, error: confirmError } = await supabase
      .rpc('confirm_unlink_token', {
        p_token: token
      })

    if (confirmError) {
      console.error("Unlink confirmation error:", confirmError)
      return new Response(
        JSON.stringify({ success: false, error: "Confirmation failed" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const confirmResult = result as {
      success: boolean
      error?: string
      employee_email?: string
      employee_name?: string
      company_name?: string
      initiated_by?: string
    }

    if (!confirmResult.success) {
      // Handle specific error cases
      const errorMessages: Record<string, string> = {
        'invalid_token': 'Lien invalide',
        'already_confirmed': 'Cette déliaison a déjà été confirmée',
        'token_cancelled': 'Cette demande a été annulée',
        'token_expired': 'Ce lien a expiré'
      }

      return new Response(
        JSON.stringify({
          success: false,
          error: errorMessages[confirmResult.error || ''] || 'Erreur de confirmation'
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Send notification email to employee
    if (confirmResult.employee_email) {
      await fetch(
        `${Deno.env.get('SUPABASE_URL')}/functions/v1/send-gdpr-email`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')}`
          },
          body: JSON.stringify({
            email: confirmResult.employee_email,
            template: 'unlink_completed',
            data: {
              employee_name: confirmResult.employee_name,
              company_name: confirmResult.company_name,
              is_employee: true
            }
          })
        }
      ).catch(e => console.error("Failed to send employee notification:", e))
    }

    return new Response(
      JSON.stringify({
        success: true,
        employee_name: confirmResult.employee_name,
        company_name: confirmResult.company_name
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Confirm unlink error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
