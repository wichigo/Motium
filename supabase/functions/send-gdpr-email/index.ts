// supabase/functions/send-gdpr-email/index.ts
// Sends GDPR-related notification emails
// Deploy: supabase functions deploy send-gdpr-email
//
// Required environment variable:
// - RESEND_API_KEY: API key from resend.com (or use alternative email provider)

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

interface EmailRequest {
  email: string
  template: "export_ready" | "account_deleted" | "policy_updated" | "welcome" | "password_reset" | "collaborator_invitation" | "email_verification" | "unlink_confirmation" | "unlink_completed"
  data: Record<string, unknown>
}

interface EmailResponse {
  success: boolean
  message_id?: string
  error?: string
}

// Email templates in French
const templates = {
  export_ready: (data: Record<string, unknown>) => ({
    subject: "Vos donnees Motium sont pretes a telecharger",
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Export de donnees Motium</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Vos donnees sont pretes</h2>

    <p>Conformement a l'article 15 du RGPD (droit d'acces), nous avons prepare l'export de toutes vos donnees personnelles.</p>

    <p style="background: #fef3c7; border-left: 4px solid #f59e0b; padding: 12px; border-radius: 4px;">
      <strong>Important :</strong> Ce lien est valide pendant <strong>${data.expires_in_hours || 24} heures</strong>.
    </p>

    ${data.download_url ? `
    <div style="text-align: center; margin: 24px 0;">
      <a href="${data.download_url}"
         style="display: inline-block; background: #2563eb; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600;">
        Telecharger mes donnees
      </a>
    </div>
    ` : `
    <p>Votre export sera disponible dans l'application Motium.</p>
    `}

    <p style="font-size: 14px; color: #64748b;">
      L'export contient : votre profil, vos trajets (avec traces GPS), vos vehicules, vos depenses,
      vos horaires de travail, et vos consentements.
    </p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Cet email a ete envoye automatiquement suite a votre demande d'export de donnees.</p>
    <p>Si vous n'avez pas fait cette demande, veuillez nous contacter immediatement.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Vos donnees sont pretes

Conformement a l'article 15 du RGPD (droit d'acces), nous avons prepare l'export de toutes vos donnees personnelles.

Important : Ce lien est valide pendant ${data.expires_in_hours || 24} heures.

${data.download_url ? `Telecharger vos donnees : ${data.download_url}` : "Votre export sera disponible dans l'application Motium."}

L'export contient : votre profil, vos trajets (avec traces GPS), vos vehicules, vos depenses, vos horaires de travail, et vos consentements.

---
Cet email a ete envoye automatiquement suite a votre demande d'export de donnees.
Si vous n'avez pas fait cette demande, veuillez nous contacter immediatement.

(c) ${new Date().getFullYear()} Motium
    `
  }),

  account_deleted: (data: Record<string, unknown>) => {
    const counts = data.counts as Record<string, number> || {}
    return {
      subject: "Votre compte Motium a ete supprime",
      html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Compte Motium supprime</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Compte supprime avec succes</h2>

    <p>Conformement a l'article 17 du RGPD (droit a l'effacement), votre compte Motium et toutes les donnees associees ont ete definitivement supprimes.</p>

    <div style="background: white; border-radius: 8px; padding: 16px; margin: 16px 0;">
      <h3 style="margin-top: 0; font-size: 14px; color: #64748b;">Donnees supprimees :</h3>
      <ul style="margin: 0; padding-left: 20px; color: #475569;">
        <li>${counts.trips || 0} trajet(s)</li>
        <li>${counts.vehicles || 0} vehicule(s)</li>
        <li>${counts.expenses || 0} depense(s)</li>
        <li>${counts.work_schedules || 0} horaire(s) de travail</li>
        <li>${counts.company_links || 0} lien(s) entreprise</li>
        <li>Tous vos consentements</li>
        <li>Votre profil utilisateur</li>
      </ul>
    </div>

    <p style="font-size: 14px; color: #64748b;">
      Date de suppression : ${new Date(data.deletion_date as string || Date.now()).toLocaleDateString('fr-FR', {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      })}
    </p>

    <p>Nous sommes tristes de vous voir partir. Si vous changez d'avis, vous pouvez toujours creer un nouveau compte.</p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Cet email confirme la suppression de votre compte.</p>
    <p>Si vous n'avez pas demande cette suppression, veuillez nous contacter immediatement.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
      `,
      text: `
Motium - Compte supprime avec succes

Conformement a l'article 17 du RGPD (droit a l'effacement), votre compte Motium et toutes les donnees associees ont ete definitivement supprimes.

Donnees supprimees :
- ${counts.trips || 0} trajet(s)
- ${counts.vehicles || 0} vehicule(s)
- ${counts.expenses || 0} depense(s)
- ${counts.work_schedules || 0} horaire(s) de travail
- ${counts.company_links || 0} lien(s) entreprise
- Tous vos consentements
- Votre profil utilisateur

Date de suppression : ${new Date(data.deletion_date as string || Date.now()).toLocaleDateString('fr-FR')}

Nous sommes tristes de vous voir partir. Si vous changez d'avis, vous pouvez toujours creer un nouveau compte.

---
Cet email confirme la suppression de votre compte.
Si vous n'avez pas demande cette suppression, veuillez nous contacter immediatement.

(c) ${new Date().getFullYear()} Motium
      `
    }
  },

  policy_updated: (data: Record<string, unknown>) => ({
    subject: "Mise a jour de notre politique de confidentialite",
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Politique de confidentialite mise a jour</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Politique de confidentialite mise a jour</h2>

    <p>Nous avons mis a jour notre politique de confidentialite (version ${data.policy_version || "nouvelle"}).</p>

    <p>Pour continuer a utiliser Motium, veuillez accepter la nouvelle politique lors de votre prochaine connexion.</p>

    ${data.policy_url ? `
    <div style="text-align: center; margin: 24px 0;">
      <a href="${data.policy_url}"
         style="display: inline-block; background: #2563eb; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600;">
        Lire la politique
      </a>
    </div>
    ` : ''}

    <h3 style="font-size: 16px; color: #1e293b;">Principaux changements :</h3>
    <ul style="color: #475569;">
      ${(data.changes as string[] || ["Mise a jour generale"]).map((change: string) =>
        `<li>${change}</li>`
      ).join('')}
    </ul>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Vous recevez cet email car vous avez un compte Motium.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Politique de confidentialite mise a jour

Nous avons mis a jour notre politique de confidentialite (version ${data.policy_version || "nouvelle"}).

Pour continuer a utiliser Motium, veuillez accepter la nouvelle politique lors de votre prochaine connexion.

${data.policy_url ? `Lire la politique : ${data.policy_url}` : ''}

Principaux changements :
${(data.changes as string[] || ["Mise a jour generale"]).map((change: string) =>
  `- ${change}`
).join('\n')}

---
Vous recevez cet email car vous avez un compte Motium.

(c) ${new Date().getFullYear()} Motium
    `
  }),

  welcome: (data: Record<string, unknown>) => ({
    subject: "Bienvenue sur Motium !",
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Bienvenue sur Motium</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Bienvenue ${data.name || ''} !</h2>

    <p>Votre compte Motium a ete cree avec succes.</p>

    <div style="background: white; border-radius: 8px; padding: 16px; margin: 16px 0;">
      <h3 style="margin-top: 0; font-size: 14px; color: #64748b;">Details de votre compte :</h3>
      <ul style="margin: 0; padding-left: 20px; color: #475569;">
        <li>Email : ${data.email || ''}</li>
        <li>Type de compte : ${data.account_type === 'ENTERPRISE' ? 'Professionnel' : 'Particulier'}</li>
        <li>Periode d'essai : 7 jours gratuits</li>
      </ul>
    </div>

    <p>Vous beneficiez d'une periode d'essai de <strong>7 jours</strong> pour decouvrir toutes les fonctionnalites de Motium :</p>

    <ul style="color: #475569;">
      <li>Suivi automatique de vos trajets</li>
      <li>Calcul des indemnites kilometriques</li>
      <li>Export PDF et Excel</li>
      <li>Reconnaissance OCR des tickets</li>
    </ul>

    <div style="text-align: center; margin: 24px 0;">
      <a href="https://motium.org"
         style="display: inline-block; background: #2563eb; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600;">
        Ouvrir l'application
      </a>
    </div>

    <p style="font-size: 14px; color: #64748b;">
      Besoin d'aide ? Consultez notre centre d'aide ou contactez-nous a support@motium.org
    </p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Bienvenue ${data.name || ''} !

Votre compte Motium a ete cree avec succes.

Details de votre compte :
- Email : ${data.email || ''}
- Type de compte : ${data.account_type === 'ENTERPRISE' ? 'Professionnel' : 'Particulier'}
- Periode d'essai : 7 jours gratuits

Vous beneficiez d'une periode d'essai de 7 jours pour decouvrir toutes les fonctionnalites de Motium :
- Suivi automatique de vos trajets
- Calcul des indemnites kilometriques
- Export PDF et Excel
- Reconnaissance OCR des tickets

Besoin d'aide ? Contactez-nous a support@motium.org

---
(c) ${new Date().getFullYear()} Motium
    `
  }),

  password_reset: (data: Record<string, unknown>) => ({
    subject: "Reinitialisation de votre mot de passe Motium",
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Reinitialisation mot de passe</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Reinitialisation du mot de passe</h2>

    <p>Vous avez demande la reinitialisation de votre mot de passe Motium.</p>

    <p style="background: #fef3c7; border-left: 4px solid #f59e0b; padding: 12px; border-radius: 4px;">
      <strong>Important :</strong> Ce lien est valide pendant <strong>1 heure</strong>.
    </p>

    <div style="text-align: center; margin: 24px 0;">
      <a href="${data.reset_url || ''}"
         style="display: inline-block; background: #2563eb; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600;">
        Reinitialiser mon mot de passe
      </a>
    </div>

    <p style="font-size: 14px; color: #64748b;">
      Si vous n'avez pas demande cette reinitialisation, ignorez cet email. Votre mot de passe restera inchange.
    </p>

    <p style="font-size: 12px; color: #94a3b8; margin-top: 20px;">
      Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>
      <span style="word-break: break-all;">${data.reset_url || ''}</span>
    </p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Cet email a ete envoye automatiquement suite a votre demande.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Reinitialisation du mot de passe

Vous avez demande la reinitialisation de votre mot de passe Motium.

Important : Ce lien est valide pendant 1 heure.

Reinitialiser votre mot de passe : ${data.reset_url || ''}

Si vous n'avez pas demande cette reinitialisation, ignorez cet email. Votre mot de passe restera inchange.

---
Cet email a ete envoye automatiquement suite a votre demande.

(c) ${new Date().getFullYear()} Motium
    `
  }),

  collaborator_invitation: (data: Record<string, unknown>) => ({
    subject: `${data.company_name || 'Une entreprise'} vous invite a rejoindre Motium`,
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Invitation Motium</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Bonjour ${data.employee_name || ''}</h2>

    <p><strong>${data.company_name || 'Une entreprise'}</strong> vous invite a rejoindre son espace professionnel sur Motium.</p>

    <div style="background: white; border-radius: 8px; padding: 16px; margin: 16px 0;">
      <h3 style="margin-top: 0; font-size: 14px; color: #64748b;">Details de l'invitation :</h3>
      <ul style="margin: 0; padding-left: 20px; color: #475569;">
        <li>Entreprise : ${data.company_name || ''}</li>
        ${data.department ? `<li>Departement : ${data.department}</li>` : ''}
        <li>Licence Pro incluse</li>
      </ul>
    </div>

    <p>En acceptant cette invitation, vous pourrez :</p>
    <ul style="color: #475569;">
      <li>Enregistrer vos trajets professionnels automatiquement</li>
      <li>Partager vos trajets avec votre entreprise</li>
      <li>Beneficier d'une licence Pro gratuite</li>
      <li>Exporter vos notes de frais</li>
    </ul>

    <div style="text-align: center; margin: 24px 0;">
      <a href="https://motium.org/link?token=${data.invitation_token || ''}"
         style="display: inline-block; background: #2563eb; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600;">
        Accepter l'invitation
      </a>
    </div>

    <p style="font-size: 14px; color: #64748b;">
      Si vous n'avez pas de compte Motium, vous serez invite a en creer un gratuitement.
    </p>

    <p style="font-size: 12px; color: #94a3b8; margin-top: 20px;">
      Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>
      <span style="word-break: break-all;">https://motium.org/link?token=${data.invitation_token || ''}</span>
    </p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Cette invitation a ete envoyee par ${data.company_name || 'une entreprise'} via Motium.</p>
    <p>Si vous ne connaissez pas cette entreprise, ignorez cet email.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Invitation professionnelle

Bonjour ${data.employee_name || ''},

${data.company_name || 'Une entreprise'} vous invite a rejoindre son espace professionnel sur Motium.

Details de l'invitation :
- Entreprise : ${data.company_name || ''}
${data.department ? `- Departement : ${data.department}` : ''}
- Licence Pro incluse

En acceptant cette invitation, vous pourrez :
- Enregistrer vos trajets professionnels automatiquement
- Partager vos trajets avec votre entreprise
- Beneficier d'une licence Pro gratuite
- Exporter vos notes de frais

Accepter l'invitation : https://motium.org/link?token=${data.invitation_token || ''}

Si vous n'avez pas de compte Motium, vous serez invite a en creer un gratuitement.

---
Cette invitation a ete envoyee par ${data.company_name || 'une entreprise'} via Motium.
Si vous ne connaissez pas cette entreprise, ignorez cet email.

(c) ${new Date().getFullYear()} Motium
    `
  }),

  email_verification: (data: Record<string, unknown>) => ({
    subject: "Verifiez votre adresse email - Motium",
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Verification email Motium</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Verifiez votre adresse email</h2>

    <p>Bonjour ${data.name || ''} !</p>

    <p>Pour activer votre compte Motium, veuillez verifier votre adresse email en cliquant sur le bouton ci-dessous.</p>

    <p style="background: #fef3c7; border-left: 4px solid #f59e0b; padding: 12px; border-radius: 4px;">
      <strong>Important :</strong> Ce lien est valide pendant <strong>24 heures</strong>.
    </p>

    <div style="text-align: center; margin: 24px 0;">
      <a href="https://motium.org/verify?token=${data.token || ''}"
         style="display: inline-block; background: #2563eb; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600;">
        Verifier mon email
      </a>
    </div>

    <p style="font-size: 14px; color: #64748b;">
      Si vous n'avez pas cree de compte Motium, ignorez cet email.
    </p>

    <p style="font-size: 12px; color: #94a3b8; margin-top: 20px;">
      Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>
      <span style="word-break: break-all;">https://motium.org/verify?token=${data.token || ''}</span>
    </p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Cet email a ete envoye automatiquement lors de votre inscription.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Verifiez votre adresse email

Bonjour ${data.name || ''} !

Pour activer votre compte Motium, veuillez verifier votre adresse email.

Important : Ce lien est valide pendant 24 heures.

Verifier votre email : https://motium.org/verify?token=${data.token || ''}

Si vous n'avez pas cree de compte Motium, ignorez cet email.

---
Cet email a ete envoye automatiquement lors de votre inscription.

(c) ${new Date().getFullYear()} Motium
    `
  }),

  unlink_confirmation: (data: Record<string, unknown>) => ({
    subject: "Confirmation de deliaison - Motium",
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Confirmation deliaison Motium</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Demande de deliaison</h2>

    <p>Bonjour,</p>

    <p>Une demande de deliaison a ete initiee ${data.initiated_by === 'employee' ? 'par le collaborateur' : "par l'entreprise"} pour le compte suivant :</p>

    <div style="background: white; border-radius: 8px; padding: 16px; margin: 16px 0;">
      <ul style="margin: 0; padding-left: 20px; color: #475569;">
        <li>Collaborateur : ${data.employee_name || ''}</li>
        <li>Entreprise : ${data.company_name || ''}</li>
      </ul>
    </div>

    <p style="background: #fef3c7; border-left: 4px solid #f59e0b; padding: 12px; border-radius: 4px;">
      <strong>Attention :</strong> Cette action est irreversible. Une fois confirmee, le collaborateur ne sera plus lie a l'espace professionnel de l'entreprise.
    </p>

    <p>Si vous etes a l'origine de cette demande, cliquez sur le bouton ci-dessous pour confirmer :</p>

    <div style="text-align: center; margin: 24px 0;">
      <a href="https://motium.org/unlink?token=${data.token || ''}"
         style="display: inline-block; background: #dc2626; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: 600;">
        Confirmer la deliaison
      </a>
    </div>

    <p style="font-size: 14px; color: #64748b;">
      Ce lien est valide pendant <strong>24 heures</strong>. Si vous n'avez pas demande cette deliaison, ignorez cet email - aucune action ne sera effectuee.
    </p>

    <p style="font-size: 12px; color: #94a3b8; margin-top: 20px;">
      Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>
      <span style="word-break: break-all;">https://motium.org/unlink?token=${data.token || ''}</span>
    </p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Cet email a ete envoye suite a une demande de deliaison sur Motium.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Demande de deliaison

Bonjour,

Une demande de deliaison a ete initiee ${data.initiated_by === 'employee' ? 'par le collaborateur' : "par l'entreprise"} pour le compte suivant :

- Collaborateur : ${data.employee_name || ''}
- Entreprise : ${data.company_name || ''}

ATTENTION : Cette action est irreversible. Une fois confirmee, le collaborateur ne sera plus lie a l'espace professionnel de l'entreprise.

Confirmer la deliaison : https://motium.org/unlink?token=${data.token || ''}

Ce lien est valide pendant 24 heures. Si vous n'avez pas demande cette deliaison, ignorez cet email.

---
Cet email a ete envoye suite a une demande de deliaison sur Motium.

(c) ${new Date().getFullYear()} Motium
    `
  }),

  unlink_completed: (data: Record<string, unknown>) => ({
    subject: "Deliaison confirmee - Motium",
    html: `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Deliaison confirmee Motium</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
  <div style="text-align: center; margin-bottom: 30px;">
    <h1 style="color: #2563eb; margin: 0;">Motium</h1>
  </div>

  <div style="background: #f8fafc; border-radius: 12px; padding: 24px; margin-bottom: 24px;">
    <h2 style="margin-top: 0; color: #1e293b;">Deliaison confirmee</h2>

    <p>Bonjour,</p>

    <p>La deliaison entre le collaborateur et l'entreprise a ete effectuee avec succes.</p>

    <div style="background: white; border-radius: 8px; padding: 16px; margin: 16px 0;">
      <ul style="margin: 0; padding-left: 20px; color: #475569;">
        <li>Collaborateur : ${data.employee_name || ''}</li>
        <li>Entreprise : ${data.company_name || ''}</li>
        <li>Date : ${new Date().toLocaleDateString('fr-FR', { year: 'numeric', month: 'long', day: 'numeric' })}</li>
      </ul>
    </div>

    <p style="font-size: 14px; color: #64748b;">
      ${data.is_employee
        ? "Vous pouvez continuer a utiliser Motium avec votre compte personnel. Vos trajets ne seront plus partages avec l'entreprise."
        : "Le collaborateur n'apparaitra plus dans votre espace professionnel."}
    </p>
  </div>

  <div style="font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; padding-top: 20px;">
    <p>Cet email confirme la deliaison effectuee sur Motium.</p>
    <p>&copy; ${new Date().getFullYear()} Motium. Tous droits reserves.</p>
  </div>
</body>
</html>
    `,
    text: `
Motium - Deliaison confirmee

Bonjour,

La deliaison entre le collaborateur et l'entreprise a ete effectuee avec succes.

- Collaborateur : ${data.employee_name || ''}
- Entreprise : ${data.company_name || ''}
- Date : ${new Date().toLocaleDateString('fr-FR')}

${data.is_employee
  ? "Vous pouvez continuer a utiliser Motium avec votre compte personnel. Vos trajets ne seront plus partages avec l'entreprise."
  : "Le collaborateur n'apparaitra plus dans votre espace professionnel."}

---
Cet email confirme la deliaison effectuee sur Motium.

(c) ${new Date().getFullYear()} Motium
    `
  })
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  // Only accept POST
  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ success: false, error: "Method not allowed" } as EmailResponse),
      { status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }

  try {
    // This function should only be called by other Edge Functions (service role)
    const authHeader = req.headers.get("Authorization")
    if (!authHeader) {
      return new Response(
        JSON.stringify({ success: false, error: "Unauthorized" } as EmailResponse),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Parse request body
    const body: EmailRequest = await req.json()
    const { email, template, data } = body

    // Validate
    if (!email || !template) {
      return new Response(
        JSON.stringify({ success: false, error: "Missing email or template" } as EmailResponse),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    if (!templates[template]) {
      return new Response(
        JSON.stringify({ success: false, error: `Unknown template: ${template}` } as EmailResponse),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Get email template
    const emailContent = templates[template](data || {})

    // Get Resend API key (or alternative email service)
    const resendApiKey = Deno.env.get("RESEND_API_KEY")

    if (!resendApiKey) {
      console.warn("RESEND_API_KEY not configured - email not sent")
      // Return success but log warning (email is optional)
      return new Response(
        JSON.stringify({
          success: true,
          message_id: "email-disabled"
        } as EmailResponse),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // Send email via Resend
    const resendResponse = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${resendApiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        from: "Motium <noreply@motium.org>", // Configure your domain in Resend
        to: [email],
        subject: emailContent.subject,
        html: emailContent.html,
        text: emailContent.text
      })
    })

    if (!resendResponse.ok) {
      const error = await resendResponse.text()
      console.error("Resend error:", error)
      throw new Error(`Email sending failed: ${error}`)
    }

    const resendData = await resendResponse.json()

    const response: EmailResponse = {
      success: true,
      message_id: resendData.id
    }

    return new Response(JSON.stringify(response), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    })

  } catch (error) {
    console.error("Email error:", error)
    const response: EmailResponse = {
      success: false,
      error: error instanceof Error ? error.message : "An unexpected error occurred"
    }
    return new Response(JSON.stringify(response), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    })
  }
})
