// supabase/functions/send-invitation/index.ts
// Creates an invitation for a user (who may or may not have an account yet)
// Uses service role to bypass RLS and create company_link with null user_id
// Deploy: supabase functions deploy send-invitation

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  pro_account_id: string
  company_name: string
  email: string
  full_name: string
  phone?: string
  department?: string
}

interface InvitationResult {
  success: boolean
  invitation_token?: string
  company_link_id?: string
  user_exists?: boolean
  retry_after_minutes?: number
  error?: string
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
    // Get Authorization header for user verification
    const authHeader = req.headers.get("Authorization")
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing or invalid authorization" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }
    const userToken = authHeader.replace("Bearer ", "")

    // Parse request body
    const body: RequestBody = await req.json()
    const { pro_account_id, company_name, email, full_name, phone, department } = body

    // Validate required fields
    if (!pro_account_id || !company_name || !email || !full_name) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing required fields" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Supabase client with user's token to verify identity
    const supabaseUser = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_ANON_KEY') ?? '',
      { global: { headers: { Authorization: `Bearer ${userToken}` } } }
    )

    // Verify the user is authenticated
    const { data: { user }, error: authError } = await supabaseUser.auth.getUser()
    if (authError || !user) {
      console.error("Auth error:", authError)
      return new Response(
        JSON.stringify({ success: false, error: "Invalid token" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Supabase client with service role (bypasses RLS)
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Verify the user owns this pro_account
    const { data: proAccount, error: proError } = await supabase
      .from('pro_accounts')
      .select('id, user_id')
      .eq('id', pro_account_id)
      .single()

    if (proError || !proAccount) {
      return new Response(
        JSON.stringify({ success: false, error: "Pro account not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Get the user's public.users.id from auth.users.id
    const { data: publicUser } = await supabase
      .from('users')
      .select('id')
      .eq('auth_id', user.id)
      .single()

    if (!publicUser || proAccount.user_id !== publicUser.id) {
      return new Response(
        JSON.stringify({ success: false, error: "Not authorized for this pro account" }),
        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Check if user already exists
    const { data: existingUser } = await supabase
      .from('users')
      .select('id, email')
      .eq('email', email.toLowerCase())
      .maybeSingle()

    // Check if there's already a pending/active link for this email
    const { data: existingLink } = await supabase
      .from('company_links')
      .select('id, status, invitation_token, invitation_expires_at, created_at, updated_at')
      .eq('linked_pro_account_id', pro_account_id)
      .or(`user_id.eq.${existingUser?.id ?? '00000000-0000-0000-0000-000000000000'},invitation_email.eq.${email.toLowerCase()}`)
      .in('status', ['PENDING', 'ACTIVE'])
      .maybeSingle()

    if (existingLink) {
      if (existingLink.status === 'ACTIVE') {
        return new Response(
          JSON.stringify({
            success: false,
            error: "user_already_linked"
          } as InvitationResult),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      // Existing pending invitation:
      // - keep same token when still valid (do not invalidate old link)
      // - enforce 1-hour cooldown before resending
      const now = new Date()
      const existingExpiresAt = existingLink.invitation_expires_at
        ? new Date(existingLink.invitation_expires_at)
        : null
      const derivedSentAt = existingExpiresAt
        ? new Date(existingExpiresAt.getTime() - (7 * 24 * 60 * 60 * 1000))
        : null
      const lastSentAt = new Date(
        Math.max(
          existingLink.updated_at ? new Date(existingLink.updated_at).getTime() : 0,
          derivedSentAt ? derivedSentAt.getTime() : 0,
          existingLink.created_at ? new Date(existingLink.created_at).getTime() : 0
        )
      )
      const nextAllowedAt = new Date(lastSentAt.getTime() + 60 * 60 * 1000)

      if (now < nextAllowedAt) {
        const retryAfterMinutes = Math.max(1, Math.ceil((nextAllowedAt.getTime() - now.getTime()) / 60000))
        return new Response(
          JSON.stringify({
            success: false,
            error: "invitation_recently_sent",
            retry_after_minutes: retryAfterMinutes
          } as InvitationResult),
          { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      const hasValidToken = !!existingLink.invitation_token &&
        !!existingExpiresAt &&
        existingExpiresAt > now

      const tokenToSend = hasValidToken ? existingLink.invitation_token : crypto.randomUUID()
      const expiresAtToUse = hasValidToken ? existingExpiresAt! : new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000)

      if (!hasValidToken) {
        const { error: refreshError } = await supabase
          .from('company_links')
          .update({
            invitation_token: tokenToSend,
            invitation_expires_at: expiresAtToUse.toISOString(),
            updated_at: now.toISOString()
          })
          .eq('id', existingLink.id)
          .eq('status', 'PENDING')

        if (refreshError) {
          console.error("Refresh invitation token error:", refreshError)
          return new Response(
            JSON.stringify({ success: false, error: "Failed to refresh invitation" } as InvitationResult),
            { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
          )
        }
      }

      const emailUrl = `${Deno.env.get('SUPABASE_URL')}/functions/v1/send-gdpr-email`
      const resendResponse = await fetch(emailUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')}`
        },
        body: JSON.stringify({
          email: email,
          template: "collaborator_invitation",
          data: {
            employee_name: full_name,
            company_name: company_name,
            department: department,
            invitation_token: tokenToSend
          }
        })
      })

      if (!resendResponse.ok) {
        const errorBody = await resendResponse.text()
        console.error("Resend invitation email failed:", errorBody)
        return new Response(
          JSON.stringify({ success: false, error: "email_send_failed" } as InvitationResult),
          { status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }

      // Keep updated_at aligned with the last successful email send time (cooldown source)
      await supabase
        .from('company_links')
        .update({ updated_at: now.toISOString() })
        .eq('id', existingLink.id)
        .eq('status', 'PENDING')

      return new Response(
        JSON.stringify({
          success: true,
          invitation_token: tokenToSend,
          company_link_id: existingLink.id,
          user_exists: !!existingUser
        } as InvitationResult),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Generate invitation token and expiration (7 days)
    const invitationToken = crypto.randomUUID()
    const expiresAt = new Date()
    expiresAt.setDate(expiresAt.getDate() + 7)

    // Create company_link entry
    // If user exists, set user_id; otherwise set invitation_email
    const linkData: Record<string, unknown> = {
      linked_pro_account_id: pro_account_id,
      company_name: company_name,
      department: department || null,
      status: 'PENDING',
      invitation_token: invitationToken,
      invitation_expires_at: expiresAt.toISOString(),
      share_professional_trips: true,
      share_personal_trips: false,
      share_personal_info: true,
      share_expenses: false,
      linked_at: new Date().toISOString()
    }

    if (existingUser) {
      linkData.user_id = existingUser.id
      linkData.invitation_email = null
    } else {
      linkData.user_id = null
      linkData.invitation_email = email.toLowerCase()
    }

    const { data: companyLink, error: insertError } = await supabase
      .from('company_links')
      .insert(linkData)
      .select('id')
      .single()

    if (insertError) {
      console.error("Insert error:", insertError)
      return new Response(
        JSON.stringify({ success: false, error: "Failed to create invitation" } as InvitationResult),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Send invitation email
    const emailUrl = `${Deno.env.get('SUPABASE_URL')}/functions/v1/send-gdpr-email`
    const emailResponse = await fetch(emailUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')}`
      },
      body: JSON.stringify({
        email: email,
        template: "collaborator_invitation",
        data: {
          employee_name: full_name,
          company_name: company_name,
          department: department,
          invitation_token: invitationToken
        }
      })
    })

    if (!emailResponse.ok) {
      console.warn("Email sending failed but invitation was created:", await emailResponse.text())
      // Don't fail the whole request if email fails
    }

    console.log(`Invitation created for ${email} by Pro account ${pro_account_id}`)

    const result: InvitationResult = {
      success: true,
      invitation_token: invitationToken,
      company_link_id: companyLink.id,
      user_exists: !!existingUser
    }

    return new Response(
      JSON.stringify(result),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Send invitation error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
