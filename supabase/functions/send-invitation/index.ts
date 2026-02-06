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

    // Generate invitation token and expiration (7 days)
    const invitationToken = crypto.randomUUID()
    const expiresAt = new Date()
    expiresAt.setDate(expiresAt.getDate() + 7)

    // Check if user already exists
    const { data: existingUser } = await supabase
      .from('users')
      .select('id, email')
      .eq('email', email.toLowerCase())
      .maybeSingle()

    // Check if there's already a pending/active link for this email
    const { data: existingLink } = await supabase
      .from('company_links')
      .select('id, status')
      .eq('linked_pro_account_id', pro_account_id)
      .or(`user_id.eq.${existingUser?.id ?? '00000000-0000-0000-0000-000000000000'},invitation_email.eq.${email.toLowerCase()}`)
      .in('status', ['PENDING', 'ACTIVE'])
      .maybeSingle()

    if (existingLink) {
      return new Response(
        JSON.stringify({
          success: false,
          error: existingLink.status === 'ACTIVE'
            ? "user_already_linked"
            : "invitation_already_pending"
        } as InvitationResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

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
