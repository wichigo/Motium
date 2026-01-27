# Debug Report: Linked Accounts showing "?" instead of names

**Date:** 2026-01-27
**Bug ID:** linked_accounts_question_mark
**Status:** FIXED

## Description du bug

Sur l'ecran "Comptes lies" (LinkedAccountsScreen), les noms des collaborateurs
s'affichaient comme "?" dans l'avatar et aucun nom/email n'etait visible.
Le meme probleme affectait la fiche detail du collaborateur (AccountDetailsScreen).

## Root Cause

La fonction SQL `get_linked_employee_ids(share_type text)` utilisee dans la
RLS policy `users_select_combined` filtrait par `cl.status = 'ACTIVE'` de
maniere globale, y compris quand `share_type = 'all'`.

Les deux company_links avaient le status `INACTIVE`, ce qui faisait que :

1. La requete PostgREST `select(*, users(id, name, email, phone_number))` sur
   `company_links` retournait les lignes de company_links
2. MAIS le join embedded sur `users` retournait `null` car la RLS policy
   bloquait l'acces aux lignes `users` des employes non-ACTIVE
3. `CompanyLinkWithUserDto.users` etait donc `null`
4. `toLinkedUserDto()` mappait `userName = null`, `userEmail = ""`
5. `displayName = userName ?: userEmail = ""` (chaine vide)
6. `displayName.firstOrNull()?.uppercase() ?: "?"` = `"?"`

## Chaine de donnees affectee

```
Supabase RLS policy users_select_combined
  -> get_linked_employee_ids('all')
    -> WHERE cl.status = 'ACTIVE' (BLOQUAIT les INACTIVE)
      -> PostgREST join users(...) retourne NULL
        -> CompanyLinkWithUserDto.users = null
          -> LinkedUserDto.userName = null, userEmail = ""
            -> displayName = "" -> avatar "?"
```

## Fix applique

**Fonction modifiee:** `get_linked_employee_ids(share_type text)`

**Avant:**
```sql
SELECT cl.user_id
FROM company_links cl
WHERE cl.linked_pro_account_id = current_pro_account_id()
AND cl.status = 'ACTIVE'  -- Bloquait INACTIVE et PENDING
AND (
  share_type = 'all' OR
  (share_type = 'professional_trips' AND cl.share_professional_trips = true) OR
  ...
);
```

**Apres:**
```sql
SELECT cl.user_id
FROM company_links cl
WHERE cl.linked_pro_account_id = current_pro_account_id()
AND cl.user_id IS NOT NULL
AND (
  -- share_type 'all': Pro peut voir tous ses employes lies (gestion)
  (share_type = 'all' AND cl.status IN ('ACTIVE', 'INACTIVE', 'PENDING')) OR
  -- Autres share_types: uniquement ACTIVE (partage de donnees)
  (share_type != 'all' AND cl.status = 'ACTIVE' AND (
    (share_type = 'professional_trips' AND cl.share_professional_trips = true) OR
    (share_type = 'personal_trips' AND cl.share_personal_trips = true) OR
    (share_type = 'expenses' AND cl.share_expenses = true) OR
    (share_type = 'personal_info' AND cl.share_personal_info = true)
  ))
);
```

**Logique du fix:**
- `share_type = 'all'` : le Pro voit les infos basiques de TOUS ses employes
  (ACTIVE, INACTIVE, PENDING) pour la gestion
- Autres share_types : seuls les liens ACTIVE partagent les donnees (trips,
  expenses, etc.) - securite preservee

## Ecrans impactes

1. **LinkedAccountsScreen** (liste) - Affiche maintenant les noms et emails
2. **AccountDetailsScreen** (fiche) - Affiche maintenant le nom et email

Les deux utilisent le join PostgREST `users(id, name, email, phone_number)`
qui depend de la RLS policy corrigee.

## Verification

- Screenshot avant: avatars "?" sans noms
- Screenshot apres: avatars "T" avec "Test User One/Two" et emails visibles
- Aucun code applicatif modifie (fix purement cote base de donnees)
- Les share_types specifiques restent filtres par ACTIVE (pas de regression
  sur le partage de donnees)

## Non-regression

- Les liens REVOKED restent exclus de `get_linked_employee_ids('all')`
- Le partage de donnees (trips, expenses, etc.) reste restreint aux liens ACTIVE
- Ajout de `cl.user_id IS NOT NULL` pour eviter les pending invitations sans user
