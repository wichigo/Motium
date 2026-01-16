// supabase/functions/accept-invitation/index.ts
// Validates an invitation token and activates the company link
// Handles both existing users and newly signed-up users
// Deploy: supabase functions deploy accept-invitation

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  token: string
  user_id?: string   // The user accepting the invitation (required if invitation has no user_id)
  user_email?: string // Email of the accepting user (for verification)
}

interface AcceptResult {
  success: boolean
  error?: string
  error_code?: string
  company_link_id?: string
  company_name?: string
  department?: string
  pro_account_id?: string
  already_accepted?: boolean
  requires_login?: boolean   // True if user needs to log in first
  invitation_email?: string  // Email the invitation was sent to
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
    const { token, user_id, user_email } = body

    if (!token) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing token", error_code: "missing_token" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Supabase client with service role
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Find the company link by invitation token
    const { data: companyLink, error: findError } = await supabase
      .from('company_links')
      .select(`
        id,
        user_id,
        linked_pro_account_id,
        company_name,
        status,
        department,
        invitation_token,
        invitation_email,
        invitation_expires_at
      `)
      .eq('invitation_token', token)
      .single()

    if (findError || !companyLink) {
      console.error("Find error:", findError)
      return new Response(
        JSON.stringify({ success: false, error: "Invalid or expired invitation token", error_code: "invalid_token" } as AcceptResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Check if already accepted
    if (companyLink.status === 'ACTIVE' || companyLink.status === 'active') {
      const result: AcceptResult = {
        success: true,
        already_accepted: true,
        company_link_id: companyLink.id,
        company_name: companyLink.company_name || '',
        department: companyLink.department || '',
        pro_account_id: companyLink.linked_pro_account_id
      }
      return new Response(
        JSON.stringify(result),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Check if invitation has expired
    if (companyLink.invitation_expires_at) {
      const expiresAt = new Date(companyLink.invitation_expires_at)
      if (expiresAt < new Date()) {
        return new Response(
          JSON.stringify({ success: false, error: "Invitation has expired", error_code: "invitation_expired" } as AcceptResult),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }
    }

    // Check if invitation was rejected/cancelled
    if (companyLink.status === 'REJECTED' || companyLink.status === 'INACTIVE' ||
        companyLink.status === 'rejected' || companyLink.status === 'inactive') {
      return new Response(
        JSON.stringify({ success: false, error: "Invitation was cancelled", error_code: "invitation_cancelled" } as AcceptResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Determine the user_id to use
    let finalUserId = companyLink.user_id

    // If invitation was sent to email (no user_id), we need a user_id now
    if (!finalUserId && !user_id) {
      // User needs to log in or create account first
      return new Response(
        JSON.stringify({
          success: false,
          error: "Please log in or create an account to accept this invitation",
          error_code: "requires_login",
          requires_login: true,
          invitation_email: companyLink.invitation_email
        } as AcceptResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // If user_id is provided, verify email matches invitation_email (if set)
    if (user_id && companyLink.invitation_email && user_email) {
      if (user_email.toLowerCase() !== companyLink.invitation_email.toLowerCase()) {
        return new Response(
          JSON.stringify({
            success: false,
            error: `This invitation was sent to ${companyLink.invitation_email}. Please log in with that email address.`,
            error_code: "email_mismatch",
            invitation_email: companyLink.invitation_email
          } as AcceptResult),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }
    }

    // Use provided user_id if invitation doesn't have one
    if (!finalUserId && user_id) {
      finalUserId = user_id
    }

    // Update the company link to active
    const updateData: Record<string, unknown> = {
      status: 'ACTIVE',
      user_id: finalUserId,
      invitation_token: null, // Clear token after use
      invitation_email: null, // Clear invitation email
      invitation_expires_at: null,
      linked_activated_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    }

    const { error: updateError } = await supabase
      .from('company_links')
      .update(updateData)
      .eq('id', companyLink.id)

    if (updateError) {
      console.error("Update error:", updateError)
      return new Response(
        JSON.stringify({ success: false, error: "Failed to activate invitation", error_code: "activation_failed" } as AcceptResult),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Return success with company info
    const result: AcceptResult = {
      success: true,
      company_link_id: companyLink.id,
      company_name: companyLink.company_name || '',
      department: companyLink.department || '',
      pro_account_id: companyLink.linked_pro_account_id
    }

    console.log(`Invitation accepted: ${companyLink.id} for company ${result.company_name} by user ${finalUserId}`)

    return new Response(
      JSON.stringify(result),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Accept invitation error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
