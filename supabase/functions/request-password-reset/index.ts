import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

interface RequestBody {
  email: string
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const { email } = await req.json() as RequestBody

    if (!email) {
      return new Response(
        JSON.stringify({ error: 'Email is required' }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Create Supabase client with service role for admin operations
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Generate secure token
    const token = crypto.randomUUID()

    // Call RPC function to create token (uses service role, bypasses RLS)
    const { data: tokenId, error: tokenError } = await supabase
      .rpc('create_password_reset_token', {
        p_email: email,
        p_token: token,
        p_expires_in_hours: 1
      })

    if (tokenError) {
      // Check if user not found - return success anyway to prevent email enumeration
      if (tokenError.message?.includes('User not found')) {
        console.log(`Password reset requested for unknown email: ${email}`)
        return new Response(
          JSON.stringify({ success: true, message: 'If an account exists, an email will be sent' }),
          { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      }

      console.error('Error creating token:', tokenError)
      return new Response(
        JSON.stringify({ error: 'Failed to process request' }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Send password reset email via send-gdpr-email function
    const resendApiKey = Deno.env.get('RESEND_API_KEY')

    if (resendApiKey) {
      const resetUrl = `https://motium.org/reset?token=${token}`

      const emailResponse = await fetch('https://api.resend.com/emails', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${resendApiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'Motium <noreply@motium.org>',
          to: [email],
          subject: 'Réinitialisation de votre mot de passe Motium',
          html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #6366F1; margin: 0;">Motium</h1>
  </div>

  <h2 style="color: #1f2937;">Réinitialisation de mot de passe</h2>

  <p>Vous avez demandé la réinitialisation de votre mot de passe Motium.</p>

  <p>Cliquez sur le bouton ci-dessous pour créer un nouveau mot de passe :</p>

  <div style="text-align: center; margin: 30px 0;">
    <a href="${resetUrl}" style="background-color: #6366F1; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600; display: inline-block;">
      Réinitialiser mon mot de passe
    </a>
  </div>

  <p style="color: #6b7280; font-size: 14px;">
    Ce lien expire dans <strong>1 heure</strong> et ne peut être utilisé qu'une seule fois.
  </p>

  <p style="color: #6b7280; font-size: 14px;">
    Si vous n'avez pas demandé cette réinitialisation, vous pouvez ignorer cet email en toute sécurité.
  </p>

  <hr style="border: none; border-top: 1px solid #e5e7eb; margin: 30px 0;">

  <p style="color: #9ca3af; font-size: 12px; text-align: center;">
    Motium - Suivi de mobilité professionnelle<br>
    <a href="https://motium.org" style="color: #6366F1;">motium.org</a>
  </p>
</body>
</html>
          `,
          text: `
Réinitialisation de mot de passe Motium

Vous avez demandé la réinitialisation de votre mot de passe Motium.

Cliquez sur le lien ci-dessous pour créer un nouveau mot de passe :
${resetUrl}

Ce lien expire dans 1 heure et ne peut être utilisé qu'une seule fois.

Si vous n'avez pas demandé cette réinitialisation, vous pouvez ignorer cet email.

---
Motium - Suivi de mobilité professionnelle
https://motium.org
          `
        }),
      })

      if (!emailResponse.ok) {
        const emailError = await emailResponse.text()
        console.error('Failed to send email:', emailError)
        // Don't fail the request - token is created, user can request again
      } else {
        console.log(`Password reset email sent to ${email}`)
      }
    } else {
      console.log('RESEND_API_KEY not configured, skipping email')
    }

    return new Response(
      JSON.stringify({ success: true, message: 'If an account exists, an email will be sent' }),
      { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) {
    console.error('Error in request-password-reset:', error)
    return new Response(
      JSON.stringify({ error: 'Internal server error' }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
