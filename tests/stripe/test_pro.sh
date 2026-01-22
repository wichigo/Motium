#!/bin/bash
# =============================================================================
# MOTIUM STRIPE TESTS - PRO ACCOUNTS (Tests 2.1-2.4)
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

wait_for_webhook() {
    log_info "Waiting ${WEBHOOK_WAIT_SECONDS}s for webhook..."
    sleep $WEBHOOK_WAIT_SECONDS
}

# =============================================================================
# TEST 2.1: Création compte pro → trial
# =============================================================================
test_2_1() {
    echo ""
    echo "============================================="
    echo "TEST 2.1: Création compte pro → trial"
    echo "============================================="

    TEST_EMAIL="test_pro_21_$(date +%s)@${TEST_EMAIL_DOMAIN}"
    log_info "Creating pro user: $TEST_EMAIL"

    # 1. Create user
    USER_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$TEST_EMAIL\",
        \"name\": \"Test Pro 2.1\",
        \"role\": \"ENTERPRISE\",
        \"subscription_type\": \"TRIAL\"
    }")
    USER_ID=$(echo $USER_RESPONSE | jq -r '.[0].id')

    # 2. Create pro_account
    PRO_RESPONSE=$(supabase_insert "pro_accounts" "{
        \"user_id\": \"$USER_ID\",
        \"company_name\": \"Test Company 2.1\",
        \"status\": \"trial\"
    }")
    PRO_ID=$(echo $PRO_RESPONSE | jq -r '.[0].id')
    PRO_STATUS=$(echo $PRO_RESPONSE | jq -r '.[0].status')
    BILLING_ANCHOR=$(echo $PRO_RESPONSE | jq -r '.[0].billing_anchor_day')

    if [ "$PRO_STATUS" == "trial" ] && [ "$BILLING_ANCHOR" == "null" ]; then
        log_pass "TEST 2.1: Pro account created with status=trial, no billing_anchor"
    else
        log_fail "TEST 2.1: Expected trial/null, got $PRO_STATUS/$BILLING_ANCHOR"
    fi

    echo "$USER_ID|$PRO_ID" > /tmp/test_2_1_data.txt
}

# =============================================================================
# TEST 2.2: Achat 1ère licence mensuelle → active + billing_anchor
# =============================================================================
test_2_2() {
    echo ""
    echo "============================================="
    echo "TEST 2.2: Achat licence mensuelle → active"
    echo "============================================="

    # Get or create pro account
    if [ -f /tmp/test_2_1_data.txt ]; then
        IFS='|' read -r USER_ID PRO_ID < /tmp/test_2_1_data.txt
    else
        test_2_1
        IFS='|' read -r USER_ID PRO_ID < /tmp/test_2_1_data.txt
    fi

    TEST_EMAIL="test_pro_22_$(date +%s)@${TEST_EMAIL_DOMAIN}"

    # 1. Create Stripe Customer for Pro Account
    log_info "Creating Stripe customer for pro account..."
    CLOCK=$(stripe test_clocks create --frozen-time=$(date +%s))
    CLOCK_ID=$(echo $CLOCK | jq -r '.id')

    CUSTOMER=$(stripe customers create \
        --email="$TEST_EMAIL" \
        --name="Test Company 2.2" \
        --metadata[pro_account_id]="$PRO_ID" \
        --metadata[type]="pro" \
        --test-clock=$CLOCK_ID)
    CUSTOMER_ID=$(echo $CUSTOMER | jq -r '.id')

    # 2. Attach payment method
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

    # 3. Create subscription with quantity=1 (1 license)
    log_info "Creating pro subscription with 1 license..."
    SUB=$(stripe subscriptions create \
        --customer=$CUSTOMER_ID \
        --items[0][price]=$STRIPE_PRICE_PRO_LICENSE \
        --items[0][quantity]=1 \
        --metadata[pro_account_id]="$PRO_ID" \
        --metadata[type]="pro" \
        --metadata[quantity]="1")
    SUB_ID=$(echo $SUB | jq -r '.id')

    wait_for_webhook

    # 4. Verify pro_account status and billing_anchor
    log_info "Verifying pro account..."
    PRO_CHECK=$(supabase_query "pro_accounts?id=eq.$PRO_ID&select=status,billing_anchor_day")
    PRO_STATUS=$(echo $PRO_CHECK | jq -r '.[0].status')
    BILLING_ANCHOR=$(echo $PRO_CHECK | jq -r '.[0].billing_anchor_day')

    if [ "$PRO_STATUS" == "active" ]; then
        log_pass "TEST 2.2a: Pro account status = active"
    else
        log_fail "TEST 2.2a: Expected active, got $PRO_STATUS"
    fi

    if [ "$BILLING_ANCHOR" != "null" ]; then
        log_pass "TEST 2.2b: billing_anchor_day set to $BILLING_ANCHOR"
    else
        log_fail "TEST 2.2b: billing_anchor_day not set"
    fi

    # 5. Verify license created
    LICENSE_CHECK=$(supabase_query "licenses?pro_account_id=eq.$PRO_ID&status=eq.available&select=id,status")
    LICENSE_COUNT=$(echo $LICENSE_CHECK | jq 'length')

    if [ "$LICENSE_COUNT" -ge 1 ]; then
        log_pass "TEST 2.2c: License created with status=available"
    else
        log_fail "TEST 2.2c: No available license found"
    fi

    echo "$USER_ID|$PRO_ID|$CUSTOMER_ID|$SUB_ID|$CLOCK_ID" > /tmp/test_2_2_data.txt
}

# =============================================================================
# TEST 2.3: Ajout licences supplémentaires
# =============================================================================
test_2_3() {
    echo ""
    echo "============================================="
    echo "TEST 2.3: Ajout licences supplémentaires"
    echo "============================================="

    if [ -f /tmp/test_2_2_data.txt ]; then
        IFS='|' read -r USER_ID PRO_ID CUSTOMER_ID SUB_ID CLOCK_ID < /tmp/test_2_2_data.txt
    else
        test_2_2
        IFS='|' read -r USER_ID PRO_ID CUSTOMER_ID SUB_ID CLOCK_ID < /tmp/test_2_2_data.txt
    fi

    # Get current license count
    BEFORE_COUNT=$(supabase_query "licenses?pro_account_id=eq.$PRO_ID&select=id" | jq 'length')
    log_info "Current license count: $BEFORE_COUNT"

    # Update subscription quantity to add 2 more licenses
    log_info "Updating subscription quantity to add 2 licenses..."

    # Get subscription item ID
    SUB_DETAILS=$(stripe subscriptions retrieve $SUB_ID)
    ITEM_ID=$(echo $SUB_DETAILS | jq -r '.items.data[0].id')

    stripe subscription_items update $ITEM_ID --quantity=3

    wait_for_webhook

    # Verify new licenses created
    AFTER_COUNT=$(supabase_query "licenses?pro_account_id=eq.$PRO_ID&select=id" | jq 'length')
    log_info "New license count: $AFTER_COUNT"

    if [ "$AFTER_COUNT" -eq 3 ]; then
        log_pass "TEST 2.3: License count increased to 3"
    else
        log_fail "TEST 2.3: Expected 3 licenses, got $AFTER_COUNT"
    fi
}

# =============================================================================
# TEST 2.4: Échec paiement pro → suspended
# =============================================================================
test_2_4() {
    echo ""
    echo "============================================="
    echo "TEST 2.4: Échec paiement pro → suspended"
    echo "============================================="

    TEST_EMAIL="test_pro_24_$(date +%s)@${TEST_EMAIL_DOMAIN}"

    # 1. Create new pro account with decline card
    USER_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$TEST_EMAIL\",
        \"name\": \"Test Pro 2.4 Fail\",
        \"role\": \"ENTERPRISE\",
        \"subscription_type\": \"TRIAL\"
    }")
    USER_ID=$(echo $USER_RESPONSE | jq -r '.[0].id')

    PRO_RESPONSE=$(supabase_insert "pro_accounts" "{
        \"user_id\": \"$USER_ID\",
        \"company_name\": \"Test Company 2.4\",
        \"status\": \"trial\"
    }")
    PRO_ID=$(echo $PRO_RESPONSE | jq -r '.[0].id')

    # 2. Create customer with decline card
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
        --card[number]=4000000000000341 \
        --card[exp_month]=12 \
        --card[exp_year]=2030 \
        --card[cvc]=123)
    PM_ID=$(echo $PM | jq -r '.id')

    stripe payment_methods attach $PM_ID --customer=$CUSTOMER_ID
    stripe customers update $CUSTOMER_ID \
        --invoice-settings[default-payment-method]=$PM_ID

    # 3. Create subscription
    SUB=$(stripe subscriptions create \
        --customer=$CUSTOMER_ID \
        --items[0][price]=$STRIPE_PRICE_PRO_LICENSE \
        --items[0][quantity]=2 \
        --metadata[pro_account_id]="$PRO_ID" \
        --metadata[type]="pro")
    SUB_ID=$(echo $SUB | jq -r '.id')

    wait_for_webhook

    # 4. Advance clock to trigger payment failure
    log_info "Advancing clock to trigger payment failure..."
    NEW_TIME=$(($(date +%s) + 32*24*60*60))
    stripe test_clocks advance $CLOCK_ID --frozen-time=$NEW_TIME

    wait_for_webhook
    wait_for_webhook

    # 5. Verify pro_account suspended
    PRO_CHECK=$(supabase_query "pro_accounts?id=eq.$PRO_ID&select=status")
    PRO_STATUS=$(echo $PRO_CHECK | jq -r '.[0].status')

    if [ "$PRO_STATUS" == "suspended" ]; then
        log_pass "TEST 2.4a: Pro account status = suspended"
    else
        log_fail "TEST 2.4a: Expected suspended, got $PRO_STATUS"
    fi

    # 6. Verify monthly licenses suspended (lifetime should stay active)
    LICENSE_CHECK=$(supabase_query "licenses?pro_account_id=eq.$PRO_ID&is_lifetime=eq.false&select=status")
    SUSPENDED_COUNT=$(echo $LICENSE_CHECK | jq '[.[] | select(.status=="suspended")] | length')

    if [ "$SUSPENDED_COUNT" -ge 1 ]; then
        log_pass "TEST 2.4b: Monthly licenses suspended"
    else
        log_fail "TEST 2.4b: Monthly licenses not suspended"
    fi
}

# =============================================================================
# MAIN
# =============================================================================
echo "============================================="
echo "MOTIUM STRIPE TESTS - PRO ACCOUNTS"
echo "============================================="

if ! command -v stripe &> /dev/null; then
    echo -e "${RED}ERROR: Stripe CLI not installed${NC}"
    exit 1
fi

test_2_1
test_2_2
test_2_3
test_2_4

echo ""
echo "============================================="
echo "SUMMARY"
echo "============================================="
echo -e "Passed: ${GREEN}$PASSED${NC}"
echo -e "Failed: ${RED}$FAILED${NC}"

rm -f /tmp/test_2_1_data.txt /tmp/test_2_2_data.txt

exit $FAILED
