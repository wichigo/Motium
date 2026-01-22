// supabase/functions/accept-invitation/index.ts
// Validates an invitation token and activates the company link
// Handles both existing users and newly signed-up users
// Deploy: supabase functions deploy accept-invitation
//
// ============================================================================
// ARBRE 3: ATTRIBUTION LICENCE - Flow
// ============================================================================
// 1. Validate invitation token and company_link status
// 2. Check pro_account is not suspended (payment failure blocks new links)
// 3. ATOMIC license assignment using UPDATE with WHERE status='available'
//    - This prevents race conditions: only one concurrent request succeeds
// 4. Update user.subscription_type = 'LICENSED' (direct update OK here because
//    licenses table is the source of truth for LICENSED users, not stripe_subscriptions)
// 5. Activate company_link
//
// NOTE: If license assignment fails due to race condition or no availability,
// the company_link is still activated - user is linked but without license.
// Pro admin can manually assign a license later via the dashboard.
// ============================================================================

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
  // ARBRE 3: License attribution info
  license_assigned?: boolean      // True if a license was assigned from the pool
  license_id?: string             // ID of the assigned license
  license_is_lifetime?: boolean   // True if assigned license is lifetime
  subscription_type?: string      // User's new subscription type (LICENSED)
  subscription_expires_at?: string | null  // Expiration date (null for lifetime)
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

    // ========================================================================
    // ARBRE 3: ATTRIBUTION LICENCE (Boucle 2 - Race condition fix)
    // ========================================================================
    // When a collaborator accepts an invitation, we need to:
    // 0. VERIFY pro_account is not suspended (security check)
    // 1. Check if user already has an active license (prevent double-assignment)
    // 2. ATOMIC license assignment: UPDATE with WHERE status='available' AND linked_account_id IS NULL
    //    - This prevents race conditions: if two requests hit simultaneously, only one wins
    // 3. Update user.subscription_type = LICENSED (OK to do directly, licenses is source of truth)
    // 4. Update the company_link to ACTIVE
    // ========================================================================

    const proAccountId = companyLink.linked_pro_account_id
    const now = new Date().toISOString()

    // STEP 0.A: IMMEDIATELY invalidate the invitation token to prevent replay attacks
    // This is done BEFORE any other operation to ensure the token can only be used once
    // even if concurrent requests arrive
    const { data: tokenClaim, error: tokenClaimError } = await supabase
      .from('company_links')
      .update({
        invitation_token: null, // Invalidate immediately
        updated_at: now,
      })
      .eq('id', companyLink.id)
      .eq('invitation_token', token) // Only succeed if token still matches (atomic)
      .select('id')
      .maybeSingle()

    if (tokenClaimError || !tokenClaim) {
      // Token was already used by another concurrent request
      console.error(`❌ Token already claimed by another request or race condition`)
      return new Response(
        JSON.stringify({
          success: false,
          error: "This invitation link has already been used",
          error_code: "token_already_used"
        } as AcceptResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }
    console.log(`🔒 Token claimed successfully for company_link ${companyLink.id}`)

    // STEP 0.B: Verify pro_account is not suspended (payment failure blocks new links)
    const { data: proAccount, error: proAccountError } = await supabase
      .from('pro_accounts')
      .select('id, status, company_name')
      .eq('id', proAccountId)
      .single()

    if (proAccountError || !proAccount) {
      console.error(`❌ Pro account ${proAccountId} not found:`, proAccountError)
      return new Response(
        JSON.stringify({
          success: false,
          error: "Company account not found",
          error_code: "pro_account_not_found"
        } as AcceptResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    if (proAccount.status === 'suspended') {
      console.error(`❌ BLOCKED: Pro account ${proAccountId} (${proAccount.company_name}) is suspended`)
      console.error(`   Cannot accept invitations until the company resolves payment issues`)
      return new Response(
        JSON.stringify({
          success: false,
          error: "This company's account is temporarily suspended. Please contact them directly.",
          error_code: "pro_account_suspended"
        } as AcceptResult),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // STEP 1: Check if user already has an assigned license from this pro_account
    // (prevents double-assignment)
    const { data: existingLicense } = await supabase
      .from('licenses')
      .select('id, is_lifetime, end_date')
      .eq('pro_account_id', proAccountId)
      .eq('linked_account_id', finalUserId)
      .eq('status', 'active')
      .single()

    if (existingLicense) {
      console.log(`ℹ️ User ${finalUserId} already has an active license (${existingLicense.id}) from pro_account ${proAccountId}`)
      // Continue to activate company_link even if license exists

      // BOUCLE 3 FIX: Ensure user.subscription_type is LICENSED even if they already had a license
      // This handles edge case where subscription_type might have been incorrectly set to something else
      const { error: fixSubTypeError } = await supabase
        .from('users')
        .update({
          subscription_type: 'LICENSED',
          subscription_expires_at: existingLicense.is_lifetime ? null : existingLicense.end_date,
          updated_at: now,
        })
        .eq('id', finalUserId)
        .neq('subscription_type', 'LICENSED') // Only update if not already LICENSED

      if (fixSubTypeError) {
        console.log(`⚠️ Could not fix subscription_type for user with existing license: ${fixSubTypeError.message}`)
      }
    }

    // STEP 2: ATOMIC license assignment to prevent race conditions
    // We use UPDATE with multiple WHERE conditions - only succeeds if license is still available
    let licenseAssigned = false
    let assignedLicenseId: string | null = null
    let isLifetimeLicense = false
    let licenseEndDate: string | null = null

    if (!existingLicense) {
      // Try to atomically claim a lifetime license first (better for user)
      // The WHERE clause ensures only one concurrent request can succeed
      const { data: claimedLifetime, error: lifetimeError } = await supabase
        .from('licenses')
        .update({
          linked_account_id: finalUserId,
          linked_at: now,
          status: 'active',
          updated_at: now,
        })
        .eq('pro_account_id', proAccountId)
        .eq('status', 'available')
        .eq('is_lifetime', true)
        .is('linked_account_id', null)
        .select('id, is_lifetime, end_date')
        .limit(1)
        .maybeSingle()

      if (claimedLifetime && !lifetimeError) {
        assignedLicenseId = claimedLifetime.id
        isLifetimeLicense = true
        licenseEndDate = null // Lifetime never expires
        licenseAssigned = true
        console.log(`✅ ATOMIC: Claimed lifetime license ${assignedLicenseId} for user ${finalUserId}`)
      } else {
        // No lifetime available, try monthly license
        const { data: claimedMonthly, error: monthlyError } = await supabase
          .from('licenses')
          .update({
            linked_account_id: finalUserId,
            linked_at: now,
            status: 'active',
            updated_at: now,
          })
          .eq('pro_account_id', proAccountId)
          .eq('status', 'available')
          .eq('is_lifetime', false)
          .is('linked_account_id', null)
          .select('id, is_lifetime, end_date')
          .limit(1)
          .maybeSingle()

        if (claimedMonthly && !monthlyError) {
          assignedLicenseId = claimedMonthly.id
          isLifetimeLicense = false
          licenseEndDate = claimedMonthly.end_date
          licenseAssigned = true
          console.log(`✅ ATOMIC: Claimed monthly license ${assignedLicenseId} for user ${finalUserId} (expires: ${licenseEndDate})`)
        } else {
          // No license available - user will be linked but without license
          console.log(`⚠️ No available license for pro_account ${proAccountId} - user will be linked but without license`)
          console.log(`   The Pro admin can manually assign a license later via the dashboard`)
        }
      }

      // STEP 3: Update user subscription_type to LICENSED (if license was assigned)
      // NOTE: This direct update is OK because for LICENSED users, the licenses table
      // is the source of truth, not stripe_subscriptions (which uses a trigger)
      if (licenseAssigned && assignedLicenseId) {
        const expiresAt = isLifetimeLicense ? null : licenseEndDate

        const { error: userUpdateError } = await supabase
          .from('users')
          .update({
            subscription_type: 'LICENSED',
            subscription_expires_at: expiresAt,
            updated_at: now,
          })
          .eq('id', finalUserId)

        if (userUpdateError) {
          console.error("⚠️ User subscription update error:", userUpdateError)
          // Don't fail - the license is assigned, we can fix user.subscription_type later
        } else {
          console.log(`✅ User ${finalUserId} subscription_type set to LICENSED (expires: ${expiresAt || 'never'})`)
        }
      }
    }

    // STEP 4: Update the company link to active
    // Note: invitation_token already cleared in STEP 0.A for security
    const updateData: Record<string, unknown> = {
      status: 'ACTIVE',
      user_id: finalUserId,
      // invitation_token already cleared in STEP 0.A
      invitation_email: null, // Clear invitation email
      invitation_expires_at: null,
      linked_activated_at: now,
      updated_at: now
    }

    const { error: updateError } = await supabase
      .from('company_links')
      .update(updateData)
      .eq('id', companyLink.id)

    if (updateError) {
      console.error("❌ CRITICAL: Update company_link error:", updateError)
      // BOUCLE 4: Log the inconsistent state for manual intervention
      // The license may have been assigned but the link is not activated
      if (licenseAssigned && assignedLicenseId) {
        console.error(`⚠️ INCONSISTENT STATE DETECTED:`)
        console.error(`   - License ${assignedLicenseId} WAS assigned to user ${finalUserId}`)
        console.error(`   - But company_link ${companyLink.id} failed to activate`)
        console.error(`   - Manual intervention may be required to fix this state`)
        console.error(`   - Options: 1) Retry activation, 2) Unassign license, 3) Mark link as failed`)
      }
      return new Response(
        JSON.stringify({ success: false, error: "Failed to activate invitation", error_code: "activation_failed" } as AcceptResult),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Return success with company info and license info
    // licenseEndDate is already captured during atomic assignment, no need to re-fetch
    const expiresAtForResponse = isLifetimeLicense ? null : licenseEndDate

    // Determine the effective license info for response
    // Could be: newly assigned, already had, or none
    const hasLicense = licenseAssigned || !!existingLicense
    const effectiveLicenseId = assignedLicenseId || existingLicense?.id || undefined
    const effectiveIsLifetime = existingLicense ? existingLicense.is_lifetime : isLifetimeLicense
    const effectiveExpiresAt = existingLicense
      ? (existingLicense.is_lifetime ? null : existingLicense.end_date)
      : expiresAtForResponse

    const result: AcceptResult = {
      success: true,
      company_link_id: companyLink.id,
      company_name: companyLink.company_name || '',
      department: companyLink.department || '',
      pro_account_id: companyLink.linked_pro_account_id,
      // ARBRE 3: Include license attribution info
      license_assigned: hasLicense,
      license_id: effectiveLicenseId,
      license_is_lifetime: effectiveIsLifetime,
      subscription_type: hasLicense ? 'LICENSED' : undefined,
      subscription_expires_at: effectiveExpiresAt,
    }

    console.log(`✅ Invitation accepted: ${companyLink.id} for company ${result.company_name} by user ${finalUserId}`)
    console.log(`   License status: ${licenseAssigned ? 'NEWLY_ASSIGNED' : (existingLicense ? 'ALREADY_HAD' : 'NONE')}`)
    if (hasLicense) {
      console.log(`   License ID: ${effectiveLicenseId} (${effectiveIsLifetime ? 'lifetime' : 'monthly'}, expires: ${effectiveExpiresAt || 'never'})`)
    }

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
