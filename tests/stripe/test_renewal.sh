#!/bin/bash
# =============================================================================
# MOTIUM STRIPE TESTS - RENEWAL & BILLING (Tests 6.1-6.3)
# =============================================================================

set -e

source .env 2>/dev/null || source .env.example

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASSED=0
FAILED=0

log_info() { echo -e "${YELLOW}[INFO]${NC} $1"; }
log_pass() { echo -e "${GREEN}[PASS]${NC} $1"; ((PASSED++)); }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; ((FAILED++)); }

supabase_query() {
    curl -s "$SUPABASE_URL/rest/v1/$1" \
        -H "apikey: $SUPABASE_SERVICE_KEY" \
        -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" \
        -H "Content-Type: application/json"
}

supabase_insert() {
    curl -s "$SUPABASE_URL/rest/v1/$1" \
        -H "apikey: $SUPABASE_SERVICE_KEY" \
        -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" \
        -H "Content-Type: application/json" \
        -H "Prefer: return=representation" \
        -d "$2"
}

supabase_update() {
    curl -s -X PATCH "$SUPABASE_URL/rest/v1/$1" \
        -H "apikey: $SUPABASE_SERVICE_KEY" \
        -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" \
        -H "Content-Type: application/json" \
        -H "Prefer: return=representation" \
        -d "$2"
}

supabase_rpc() {
    curl -s "$SUPABASE_URL/rest/v1/rpc/$1" \
        -H "apikey: $SUPABASE_SERVICE_KEY" \
        -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" \
        -H "Content-Type: application/json" \
        -d "$2"
}

wait_for_webhook() {
    log_info "Waiting ${WEBHOOK_WAIT_SECONDS}s for webhook..."
    sleep $WEBHOOK_WAIT_SECONDS
}

# =============================================================================
# TEST 6.1: Renouvellement réussi avec licenses canceled
# =============================================================================
test_6_1() {
    echo ""
    echo "============================================="
    echo "TEST 6.1: Renouvellement avec licenses canceled"
    echo "============================================="

    TEST_EMAIL="test_renewal_61_$(date +%s)@${TEST_EMAIL_DOMAIN}"

    # 1. Create pro account with subscription
    USER_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$TEST_EMAIL\",
        \"name\": \"Test Renewal 6.1\",
        \"role\": \"ENTERPRISE\",
        \"subscription_type\": \"TRIAL\"
    }")
    USER_ID=$(echo $USER_RESPONSE | jq -r '.[0].id')

    PRO_RESPONSE=$(supabase_insert "pro_accounts" "{
        \"user_id\": \"$USER_ID\",
        \"company_name\": \"Test Renewal Company\",
        \"status\": \"active\",
        \"billing_anchor_day\": 15
    }")
    PRO_ID=$(echo $PRO_RESPONSE | jq -r '.[0].id')

    # 2. Create 3 licenses: 1 active, 1 canceled, 1 unlinked
    COLLAB_EMAIL="collab_61_$(date +%s)@${TEST_EMAIL_DOMAIN}"
    COLLAB_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$COLLAB_EMAIL\",
        \"name\": \"Collaborator 6.1\",
        \"role\": \"INDIVIDUAL\",
        \"subscription_type\": \"LICENSED\"
    }")
    COLLAB_ID=$(echo $COLLAB_RESPONSE | jq -r '.[0].id')

    # License 1: Active (assigned)
    supabase_insert "licenses" "{
        \"pro_account_id\": \"$PRO_ID\",
        \"status\": \"active\",
        \"is_lifetime\": false,
        \"linked_account_id\": \"$COLLAB_ID\"
    }"

    # License 2: Canceled (pending removal at billing)
    supabase_insert "licenses" "{
        \"pro_account_id\": \"$PRO_ID\",
        \"status\": \"canceled\",
        \"is_lifetime\": false
    }"

    # License 3: Unlinked (pending effective date)
    supabase_insert "licenses" "{
        \"pro_account_id\": \"$PRO_ID\",
        \"status\": \"unlinked\",
        \"is_lifetime\": false,
        \"unlink_effective_at\": \"$(date -d '+2 days' --iso-8601=seconds)\"
    }"

    # 3. Create Stripe subscription
    CLOCK=$(stripe test_clocks create --frozen-time=$(date +%s))
    CLOCK_ID=$(echo $CLOCK | jq -r '.id')

    CUSTOMER=$(stripe customers create \
        --email="$TEST_EMAIL" \
        --metadata[pro_account_id]="$PRO_ID" \
        --metadata[type]="pro" \
        --test-clock=$CLOCK_ID)
    CUSTOMER_ID=$(echo $CUSTOMER | jq -r '.id')

    PM=$(stripe payment_methods create \
        --type=card \
        --card[number]=4242424242424242 \
        --card[exp_month]=12 \
        --card[exp_year]=2030 \
        --card[cvc]=123)
    PM_ID=$(echo $PM | jq -r '.id')

    stripe payment_methods attach $PM_ID --customer=$CUSTOMER_ID
    stripe customers update $CUSTOMER_ID \
        --invoice-settings[default-payment-method]=$PM_ID

    SUB=$(stripe subscriptions create \
        --customer=$CUSTOMER_ID \
        --items[0][price]=$STRIPE_PRICE_PRO_LICENSE \
        --items[0][quantity]=3 \
        --metadata[pro_account_id]="$PRO_ID" \
        --metadata[type]="pro")
    SUB_ID=$(echo $SUB | jq -r '.id')

    # Update pro_account with subscription ID
    supabase_update "pro_accounts?id=eq.$PRO_ID" "{\"stripe_subscription_id\": \"$SUB_ID\"}"

    wait_for_webhook

    # 4. Advance clock to trigger billing
    log_info "Advancing clock to trigger renewal..."
    NEW_TIME=$(($(date +%s) + 32*24*60*60))
    stripe test_clocks advance $CLOCK_ID --frozen-time=$NEW_TIME

    wait_for_webhook
    wait_for_webhook

    # 5. Verify: canceled license deleted, subscription quantity updated
    LICENSE_COUNT=$(supabase_query "licenses?pro_account_id=eq.$PRO_ID&select=id" | jq 'length')
    CANCELED_COUNT=$(supabase_query "licenses?pro_account_id=eq.$PRO_ID&status=eq.canceled&select=id" | jq 'length')

    log_info "Remaining licenses: $LICENSE_COUNT, Canceled: $CANCELED_COUNT"

    if [ "$CANCELED_COUNT" -eq 0 ]; then
        log_pass "TEST 6.1a: Canceled licenses removed at billing"
    else
        log_fail "TEST 6.1a: Canceled licenses still exist: $CANCELED_COUNT"
    fi

    # Verify Stripe subscription quantity reduced
    SUB_CHECK=$(stripe subscriptions retrieve $SUB_ID)
    SUB_QTY=$(echo $SUB_CHECK | jq -r '.items.data[0].quantity')

    log_info "Subscription quantity: $SUB_QTY"

    if [ "$SUB_QTY" -lt 3 ]; then
        log_pass "TEST 6.1b: Subscription quantity reduced"
    else
        log_fail "TEST 6.1b: Subscription quantity not reduced: $SUB_QTY"
    fi
}

# =============================================================================
# TEST 6.2: Échec paiement pro → suspended (monthly only)
# =============================================================================
test_6_2() {
    echo ""
    echo "============================================="
    echo "TEST 6.2: Échec paiement → monthly suspended, lifetime active"
    echo "============================================="

    TEST_EMAIL="test_renewal_62_$(date +%s)@${TEST_EMAIL_DOMAIN}"

    # 1. Create pro account
    USER_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$TEST_EMAIL\",
        \"name\": \"Test Renewal 6.2\",
        \"role\": \"ENTERPRISE\",
        \"subscription_type\": \"TRIAL\"
    }")
    USER_ID=$(echo $USER_RESPONSE | jq -r '.[0].id')

    PRO_RESPONSE=$(supabase_insert "pro_accounts" "{
        \"user_id\": \"$USER_ID\",
        \"company_name\": \"Test Suspend Company\",
        \"status\": \"active\",
        \"billing_anchor_day\": 15
    }")
    PRO_ID=$(echo $PRO_RESPONSE | jq -r '.[0].id')

    # 2. Create collaborators
    COLLAB1_EMAIL="collab_62a_$(date +%s)@${TEST_EMAIL_DOMAIN}"
    COLLAB1_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$COLLAB1_EMAIL\",
        \"name\": \"Collab Monthly\",
        \"subscription_type\": \"LICENSED\"
    }")
    COLLAB1_ID=$(echo $COLLAB1_RESPONSE | jq -r '.[0].id')

    COLLAB2_EMAIL="collab_62b_$(date +%s)@${TEST_EMAIL_DOMAIN}"
    COLLAB2_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$COLLAB2_EMAIL\",
        \"name\": \"Collab Lifetime\",
        \"subscription_type\": \"LICENSED\"
    }")
    COLLAB2_ID=$(echo $COLLAB2_RESPONSE | jq -r '.[0].id')

    # 3. Create licenses: 1 monthly, 1 lifetime
    LICENSE1=$(supabase_insert "licenses" "{
        \"pro_account_id\": \"$PRO_ID\",
        \"status\": \"active\",
        \"is_lifetime\": false,
        \"linked_account_id\": \"$COLLAB1_ID\"
    }")
    LICENSE1_ID=$(echo $LICENSE1 | jq -r '.[0].id')

    LICENSE2=$(supabase_insert "licenses" "{
        \"pro_account_id\": \"$PRO_ID\",
        \"status\": \"active\",
        \"is_lifetime\": true,
        \"linked_account_id\": \"$COLLAB2_ID\"
    }")
    LICENSE2_ID=$(echo $LICENSE2 | jq -r '.[0].id')

    # 4. Create subscription with decline card
    CLOCK=$(stripe test_clocks create --frozen-time=$(date +%s))
    CLOCK_ID=$(echo $CLOCK | jq -r '.id')

    CUSTOMER=$(stripe customers create \
        --email="$TEST_EMAIL" \
        --metadata[pro_account_id]="$PRO_ID" \
        --test-clock=$CLOCK_ID)
    CUSTOMER_ID=$(echo $CUSTOMER | jq -r '.id')

    PM=$(stripe payment_methods create \
        --type=card \
        --card[number]=4000000000000341 \
        --card[exp_month]=12 \
        --card[exp_year]=2030 \
        --card[cvc]=123)
    PM_ID=$(echo $PM | jq -r '.id')

    stripe payment_methods attach $PM_ID --customer=$CUSTOMER_ID
    stripe customers update $CUSTOMER_ID \
        --invoice-settings[default-payment-method]=$PM_ID

    SUB=$(stripe subscriptions create \
        --customer=$CUSTOMER_ID \
        --items[0][price]=$STRIPE_PRICE_PRO_LICENSE \
        --items[0][quantity]=1 \
        --metadata[pro_account_id]="$PRO_ID" \
        --metadata[type]="pro")
    SUB_ID=$(echo $SUB | jq -r '.id')

    supabase_update "pro_accounts?id=eq.$PRO_ID" "{\"stripe_subscription_id\": \"$SUB_ID\"}"

    wait_for_webhook

    # 5. Advance clock to trigger payment failure
    log_info "Advancing clock to trigger payment failure..."
    NEW_TIME=$(($(date +%s) + 32*24*60*60))
    stripe test_clocks advance $CLOCK_ID --frozen-time=$NEW_TIME

    wait_for_webhook
    wait_for_webhook

    # 6. Verify pro_account suspended
    PRO_CHECK=$(supabase_query "pro_accounts?id=eq.$PRO_ID&select=status")
    PRO_STATUS=$(echo $PRO_CHECK | jq -r '.[0].status')

    if [ "$PRO_STATUS" == "suspended" ]; then
        log_pass "TEST 6.2a: Pro account suspended"
    else
        log_fail "TEST 6.2a: Expected suspended, got $PRO_STATUS"
    fi

    # 7. Verify monthly license suspended
    LICENSE1_CHECK=$(supabase_query "licenses?id=eq.$LICENSE1_ID&select=status")
    LICENSE1_STATUS=$(echo $LICENSE1_CHECK | jq -r '.[0].status')

    if [ "$LICENSE1_STATUS" == "suspended" ]; then
        log_pass "TEST 6.2b: Monthly license suspended"
    else
        log_fail "TEST 6.2b: Monthly license expected suspended, got $LICENSE1_STATUS"
    fi

    # 8. Verify lifetime license still active
    LICENSE2_CHECK=$(supabase_query "licenses?id=eq.$LICENSE2_ID&select=status")
    LICENSE2_STATUS=$(echo $LICENSE2_CHECK | jq -r '.[0].status')

    if [ "$LICENSE2_STATUS" == "active" ]; then
        log_pass "TEST 6.2c: Lifetime license still active"
    else
        log_fail "TEST 6.2c: Lifetime license expected active, got $LICENSE2_STATUS"
    fi

    # 9. Verify collaborator access
    COLLAB1_CHECK=$(supabase_query "users?id=eq.$COLLAB1_ID&select=subscription_type")
    COLLAB1_TYPE=$(echo $COLLAB1_CHECK | jq -r '.[0].subscription_type')

    if [ "$COLLAB1_TYPE" == "EXPIRED" ]; then
        log_pass "TEST 6.2d: Monthly collaborator EXPIRED"
    else
        log_fail "TEST 6.2d: Monthly collaborator expected EXPIRED, got $COLLAB1_TYPE"
    fi

    COLLAB2_CHECK=$(supabase_query "users?id=eq.$COLLAB2_ID&select=subscription_type")
    COLLAB2_TYPE=$(echo $COLLAB2_CHECK | jq -r '.[0].subscription_type')

    if [ "$COLLAB2_TYPE" == "LICENSED" ]; then
        log_pass "TEST 6.2e: Lifetime collaborator still LICENSED"
    else
        log_fail "TEST 6.2e: Lifetime collaborator expected LICENSED, got $COLLAB2_TYPE"
    fi
}

# =============================================================================
# MAIN
# =============================================================================
echo "============================================="
echo "MOTIUM STRIPE TESTS - RENEWAL & BILLING"
echo "============================================="

if ! command -v stripe &> /dev/null; then
    echo -e "${RED}ERROR: Stripe CLI not installed${NC}"
    exit 1
fi

test_6_1
test_6_2

echo ""
echo "============================================="
echo "SUMMARY"
echo "============================================="
echo -e "Passed: ${GREEN}$PASSED${NC}"
echo -e "Failed: ${RED}$FAILED${NC}"

exit $FAILED
