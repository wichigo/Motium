#!/bin/bash
# =============================================================================
# MOTIUM STRIPE TESTS - INDIVIDUAL SUBSCRIPTIONS (Tests 1.3-1.7)
# =============================================================================
# Prerequisites:
#   - Stripe CLI installed and logged in
#   - stripe listen running in another terminal
#   - .env file configured
# =============================================================================

set -e

# Load environment
source .env 2>/dev/null || source .env.example

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Counters
PASSED=0
FAILED=0

# Helper functions
log_info() { echo -e "${YELLOW}[INFO]${NC} $1"; }
log_pass() { echo -e "${GREEN}[PASS]${NC} $1"; ((PASSED++)); }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; ((FAILED++)); }

supabase_query() {
    curl -s "$SUPABASE_URL/rest/v1/$1" \
        -H "apikey: $SUPABASE_SERVICE_KEY" \
        -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" \
        -H "Content-Type: application/json" \
        -H "Prefer: return=representation"
}

supabase_insert() {
    curl -s "$SUPABASE_URL/rest/v1/$1" \
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
    log_info "Waiting ${WEBHOOK_WAIT_SECONDS}s for webhook processing..."
    sleep $WEBHOOK_WAIT_SECONDS
}

# =============================================================================
# TEST 1.3: Paiement mensuel → PREMIUM
# =============================================================================
test_1_3() {
    echo ""
    echo "============================================="
    echo "TEST 1.3: Paiement mensuel → PREMIUM"
    echo "============================================="

    TEST_EMAIL="test_13_$(date +%s)@${TEST_EMAIL_DOMAIN}"
    log_info "Creating test user: $TEST_EMAIL"

    # 1. Create user in Supabase
    USER_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$TEST_EMAIL\",
        \"name\": \"Test 1.3 Monthly\",
        \"role\": \"INDIVIDUAL\",
        \"subscription_type\": \"TRIAL\"
    }")
    USER_ID=$(echo $USER_RESPONSE | jq -r '.[0].id')
    log_info "User created: $USER_ID"

    # 2. Create Stripe Customer
    log_info "Creating Stripe customer..."
    CUSTOMER=$(stripe customers create \
        --email="$TEST_EMAIL" \
        --name="Test 1.3 Monthly" \
        --metadata[user_id]="$USER_ID" \
        --metadata[type]="individual")
    CUSTOMER_ID=$(echo $CUSTOMER | jq -r '.id')
    log_info "Stripe customer: $CUSTOMER_ID"

    # 3. Create Test Clock
    log_info "Creating test clock..."
    CLOCK=$(stripe test_clocks create --frozen-time=$(date +%s))
    CLOCK_ID=$(echo $CLOCK | jq -r '.id')
    log_info "Test clock: $CLOCK_ID"

    # 4. Attach test card to customer
    log_info "Attaching test card..."
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

    # 5. Create subscription
    log_info "Creating subscription..."
    SUB=$(stripe subscriptions create \
        --customer=$CUSTOMER_ID \
        --items[0][price]=$STRIPE_PRICE_MONTHLY \
        --metadata[user_id]="$USER_ID" \
        --metadata[type]="individual" \
        --metadata[plan]="monthly" \
        --test-clock=$CLOCK_ID)
    SUB_ID=$(echo $SUB | jq -r '.id')
    log_info "Subscription created: $SUB_ID"

    # 6. Wait for webhook
    wait_for_webhook

    # 7. Verify user is now PREMIUM
    log_info "Verifying user subscription_type..."
    USER_CHECK=$(supabase_query "users?id=eq.$USER_ID&select=subscription_type")
    SUB_TYPE=$(echo $USER_CHECK | jq -r '.[0].subscription_type')

    if [ "$SUB_TYPE" == "PREMIUM" ]; then
        log_pass "TEST 1.3: User subscription_type = PREMIUM"
    else
        log_fail "TEST 1.3: Expected PREMIUM, got $SUB_TYPE"
    fi

    # Store for later tests
    echo "$USER_ID|$CUSTOMER_ID|$SUB_ID|$CLOCK_ID" >> /tmp/test_1_3_data.txt
}

# =============================================================================
# TEST 1.5: Renouvellement mensuel réussi
# =============================================================================
test_1_5() {
    echo ""
    echo "============================================="
    echo "TEST 1.5: Renouvellement mensuel réussi"
    echo "============================================="

    # Use data from test 1.3 or create new
    if [ -f /tmp/test_1_3_data.txt ]; then
        IFS='|' read -r USER_ID CUSTOMER_ID SUB_ID CLOCK_ID < /tmp/test_1_3_data.txt
        log_info "Using existing subscription: $SUB_ID"
    else
        log_info "No existing subscription, creating new..."
        test_1_3
        IFS='|' read -r USER_ID CUSTOMER_ID SUB_ID CLOCK_ID < /tmp/test_1_3_data.txt
    fi

    # Advance test clock by 32 days
    log_info "Advancing test clock by 32 days..."
    NEW_TIME=$(($(date +%s) + 32*24*60*60))
    stripe test_clocks advance $CLOCK_ID --frozen-time=$NEW_TIME

    # Wait for invoice.paid webhook
    wait_for_webhook
    wait_for_webhook  # Extra wait for renewal processing

    # Verify user still PREMIUM and subscription_expires_at updated
    log_info "Verifying renewal..."
    USER_CHECK=$(supabase_query "users?id=eq.$USER_ID&select=subscription_type,subscription_expires_at")
    SUB_TYPE=$(echo $USER_CHECK | jq -r '.[0].subscription_type')

    if [ "$SUB_TYPE" == "PREMIUM" ]; then
        log_pass "TEST 1.5: User still PREMIUM after renewal"
    else
        log_fail "TEST 1.5: Expected PREMIUM, got $SUB_TYPE"
    fi
}

# =============================================================================
# TEST 1.6: Échec paiement → EXPIRED
# =============================================================================
test_1_6() {
    echo ""
    echo "============================================="
    echo "TEST 1.6: Échec paiement → EXPIRED"
    echo "============================================="

    TEST_EMAIL="test_16_$(date +%s)@${TEST_EMAIL_DOMAIN}"
    log_info "Creating test user: $TEST_EMAIL"

    # 1. Create user in Supabase
    USER_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$TEST_EMAIL\",
        \"name\": \"Test 1.6 Payment Fail\",
        \"role\": \"INDIVIDUAL\",
        \"subscription_type\": \"TRIAL\"
    }")
    USER_ID=$(echo $USER_RESPONSE | jq -r '.[0].id')
    log_info "User created: $USER_ID"

    # 2. Create Stripe Customer with Test Clock
    log_info "Creating Stripe customer with test clock..."
    CLOCK=$(stripe test_clocks create --frozen-time=$(date +%s))
    CLOCK_ID=$(echo $CLOCK | jq -r '.id')

    CUSTOMER=$(stripe customers create \
        --email="$TEST_EMAIL" \
        --name="Test 1.6 Payment Fail" \
        --metadata[user_id]="$USER_ID" \
        --test-clock=$CLOCK_ID)
    CUSTOMER_ID=$(echo $CUSTOMER | jq -r '.id')

    # 3. Attach DECLINE card (4000000000000341 - decline after attach)
    log_info "Attaching decline-after-attach card..."
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

    # 4. Create subscription (first payment succeeds)
    log_info "Creating subscription..."
    SUB=$(stripe subscriptions create \
        --customer=$CUSTOMER_ID \
        --items[0][price]=$STRIPE_PRICE_MONTHLY \
        --metadata[user_id]="$USER_ID" \
        --metadata[type]="individual")
    SUB_ID=$(echo $SUB | jq -r '.id')

    wait_for_webhook

    # Verify user is PREMIUM
    USER_CHECK=$(supabase_query "users?id=eq.$USER_ID&select=subscription_type")
    SUB_TYPE=$(echo $USER_CHECK | jq -r '.[0].subscription_type')
    log_info "User is now: $SUB_TYPE"

    # 5. Advance clock to trigger renewal (card will decline)
    log_info "Advancing clock to trigger renewal failure..."
    NEW_TIME=$(($(date +%s) + 32*24*60*60))
    stripe test_clocks advance $CLOCK_ID --frozen-time=$NEW_TIME

    # Wait for invoice.payment_failed webhook
    wait_for_webhook
    wait_for_webhook

    # 6. Verify user is EXPIRED
    log_info "Verifying user is EXPIRED..."
    USER_CHECK=$(supabase_query "users?id=eq.$USER_ID&select=subscription_type")
    SUB_TYPE=$(echo $USER_CHECK | jq -r '.[0].subscription_type')

    if [ "$SUB_TYPE" == "EXPIRED" ]; then
        log_pass "TEST 1.6: User subscription_type = EXPIRED after payment failure"
    else
        log_fail "TEST 1.6: Expected EXPIRED, got $SUB_TYPE"
    fi
}

# =============================================================================
# TEST 1.7: Résiliation volontaire
# =============================================================================
test_1_7() {
    echo ""
    echo "============================================="
    echo "TEST 1.7: Résiliation volontaire"
    echo "============================================="

    TEST_EMAIL="test_17_$(date +%s)@${TEST_EMAIL_DOMAIN}"
    log_info "Creating test user: $TEST_EMAIL"

    # 1. Create user
    USER_RESPONSE=$(supabase_insert "users" "{
        \"email\": \"$TEST_EMAIL\",
        \"name\": \"Test 1.7 Cancellation\",
        \"role\": \"INDIVIDUAL\",
        \"subscription_type\": \"TRIAL\"
    }")
    USER_ID=$(echo $USER_RESPONSE | jq -r '.[0].id')

    # 2. Create customer, clock, subscription (same as 1.3)
    CLOCK=$(stripe test_clocks create --frozen-time=$(date +%s))
    CLOCK_ID=$(echo $CLOCK | jq -r '.id')

    CUSTOMER=$(stripe customers create \
        --email="$TEST_EMAIL" \
        --metadata[user_id]="$USER_ID" \
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
        --items[0][price]=$STRIPE_PRICE_MONTHLY \
        --metadata[user_id]="$USER_ID" \
        --metadata[type]="individual")
    SUB_ID=$(echo $SUB | jq -r '.id')

    wait_for_webhook

    # 3. Cancel at period end
    log_info "Cancelling subscription at period end..."
    stripe subscriptions update $SUB_ID --cancel-at-period-end=true

    wait_for_webhook

    # 4. Verify still PREMIUM (not cancelled yet)
    USER_CHECK=$(supabase_query "users?id=eq.$USER_ID&select=subscription_type")
    SUB_TYPE=$(echo $USER_CHECK | jq -r '.[0].subscription_type')

    if [ "$SUB_TYPE" == "PREMIUM" ]; then
        log_pass "TEST 1.7a: User still PREMIUM during grace period"
    else
        log_fail "TEST 1.7a: Expected PREMIUM during grace, got $SUB_TYPE"
    fi

    # 5. Advance clock past period end
    log_info "Advancing clock past period end..."
    NEW_TIME=$(($(date +%s) + 32*24*60*60))
    stripe test_clocks advance $CLOCK_ID --frozen-time=$NEW_TIME

    wait_for_webhook
    wait_for_webhook

    # 6. Verify EXPIRED
    USER_CHECK=$(supabase_query "users?id=eq.$USER_ID&select=subscription_type")
    SUB_TYPE=$(echo $USER_CHECK | jq -r '.[0].subscription_type')

    if [ "$SUB_TYPE" == "EXPIRED" ]; then
        log_pass "TEST 1.7b: User EXPIRED after cancellation period"
    else
        log_fail "TEST 1.7b: Expected EXPIRED after period, got $SUB_TYPE"
    fi
}

# =============================================================================
# MAIN
# =============================================================================
echo "============================================="
echo "MOTIUM STRIPE TESTS - INDIVIDUAL SUBSCRIPTIONS"
echo "============================================="
echo "Supabase: $SUPABASE_URL"
echo "============================================="

# Check prerequisites
if ! command -v stripe &> /dev/null; then
    echo -e "${RED}ERROR: Stripe CLI not installed${NC}"
    echo "Install with: scoop install stripe"
    exit 1
fi

if ! command -v jq &> /dev/null; then
    echo -e "${RED}ERROR: jq not installed${NC}"
    echo "Install with: scoop install jq"
    exit 1
fi

# Run tests
test_1_3
test_1_5
test_1_6
test_1_7

# Summary
echo ""
echo "============================================="
echo "SUMMARY"
echo "============================================="
echo -e "Passed: ${GREEN}$PASSED${NC}"
echo -e "Failed: ${RED}$FAILED${NC}"
echo "============================================="

# Cleanup temp files
rm -f /tmp/test_1_3_data.txt

exit $FAILED
