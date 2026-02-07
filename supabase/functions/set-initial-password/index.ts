// supabase/functions/set-initial-password/index.ts
// Creates a new user account for an invited user who doesn't have one yet
// This is called when an invited user clicks "Create password" instead of Google Sign-In
// Deploy: supabase functions deploy set-initial-password

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface RequestBody {
  invitation_token: string
  email: string
  password: string
  name?: string
  phone?: string
}

interface SetPasswordResult {
  success: boolean
  user_id?: string
  company_link_id?: string
  company_name?: string
  error?: string
  error_code?: string
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
    const { invitation_token, email, password, name, phone } = body

    // Validate required fields
    if (!invitation_token || !email || !password) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing required fields", error_code: "missing_fields" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Validate password strength
    if (password.length < 8) {
      return new Response(
        JSON.stringify({ success: false, error: "Password must be at least 8 characters", error_code: "weak_password" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Supabase client with service role (bypasses RLS)
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Find the invitation
    const { data: companyLink, error: findError } = await supabase
      .from('company_links')
      .select(`
        id,
        invitation_email,
        invitation_expires_at,
        user_id,
        status,
        company_name,
        department,
        linked_pro_account_id
      `)
      .eq('invitation_token', invitation_token)
      .single()

    if (findError || !companyLink) {
      console.error("Find error:", findError)
      return new Response(
        JSON.stringify({ success: false, error: "Invalid invitation token", error_code: "invalid_token" } as SetPasswordResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Check if already used
    if (companyLink.status === 'ACTIVE') {
      return new Response(
        JSON.stringify({ success: false, error: "Invitation already accepted", error_code: "already_accepted" } as SetPasswordResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Check if expired
    if (companyLink.invitation_expires_at) {
      const expiresAt = new Date(companyLink.invitation_expires_at)
      if (expiresAt < new Date()) {
        return new Response(
          JSON.stringify({ success: false, error: "Invitation has expired", error_code: "invitation_expired" } as SetPasswordResult),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }
    }

    // Verify email matches the invitation
    if (companyLink.invitation_email && companyLink.invitation_email.toLowerCase() !== email.toLowerCase()) {
      return new Response(
        JSON.stringify({ success: false, error: "Email does not match invitation", error_code: "email_mismatch" } as SetPasswordResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Check if user already exists with this email
    const { data: existingUser } = await supabase
      .from('users')
      .select('id')
      .eq('email', email.toLowerCase())
      .maybeSingle()

    if (existingUser) {
      return new Response(
        JSON.stringify({
          success: false,
          error: "An account with this email already exists. Please log in instead.",
          error_code: "user_exists"
        } as SetPasswordResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Create Auth user
    const { data: authData, error: authError } = await supabase.auth.admin.createUser({
      email: email.toLowerCase(),
      password: password,
      email_confirm: true, // Auto-confirm since they clicked invitation link
      user_metadata: {
        name: name || email.split('@')[0],
        created_via: 'invitation'
      }
    })

    if (authError || !authData.user) {
      console.error("Auth error:", authError)
      return new Response(
        JSON.stringify({
          success: false,
          error: authError?.message || "Failed to create account",
          error_code: "auth_error"
        } as SetPasswordResult),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const userId = authData.user.id
    const now = new Date().toISOString()

    // Create user record in users table
    // Calculate trial period (7 days)
    const trialEndsAt = new Date()
    trialEndsAt.setDate(trialEndsAt.getDate() + 7)

    const { data: userInsertData, error: userInsertError } = await supabase
      .from('users')
      .insert({
        auth_id: userId,  // Link to auth.users.id
        email: email.toLowerCase(),
        name: name || email.split('@')[0],
        phone_number: phone || '',
        role: 'INDIVIDUAL',
        subscription_type: 'TRIAL', // Will be upgraded to LICENSED after link activation
        trial_started_at: now,
        trial_ends_at: trialEndsAt.toISOString(),
        address: '',
        profile_photo_url: null,
        consider_full_distance: false,
        favorite_colors: [],
        created_at: now,
        updated_at: now
      })
      .select('id')
      .single()

    if (userInsertError || !userInsertData) {
      console.error("User insert error:", userInsertError)
      // Try to clean up auth user
      await supabase.auth.admin.deleteUser(userId)
      return new Response(
        JSON.stringify({ success: false, error: "Failed to create user profile", error_code: "user_insert_error" } as SetPasswordResult),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const publicUserId = userInsertData.id  // This is the public.users.id

    // Update company_link to set user_id and activate
    const { error: linkUpdateError } = await supabase
      .from('company_links')
      .update({
        user_id: publicUserId,
        status: 'ACTIVE',
        invitation_token: null,
        invitation_email: null,
        invitation_expires_at: null,
        linked_activated_at: now,
        updated_at: now
      })
      .eq('id', companyLink.id)

    if (linkUpdateError) {
      console.error("Link update error:", linkUpdateError)
      // Don't fail - user was created successfully, they can manually accept later
    }

    console.log(`New user ${email} (public.users.id: ${publicUserId}) created via invitation for company ${companyLink.company_name}`)

    const result: SetPasswordResult = {
      success: true,
      user_id: publicUserId,  // Return public.users.id
      company_link_id: companyLink.id,
      company_name: companyLink.company_name
    }

    return new Response(
      JSON.stringify(result),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (error) {
    console.error("Set initial password error:", error)
    return new Response(
      JSON.stringify({
        success: false,
        error: error instanceof Error ? error.message : "An unexpected error occurred",
        error_code: "unknown_error"
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
