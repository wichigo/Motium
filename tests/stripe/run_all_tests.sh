#!/bin/bash
# =============================================================================
# MOTIUM STRIPE TESTS - RUN ALL
# =============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo -e "${BLUE}"
echo "============================================="
echo "   MOTIUM STRIPE INTEGRATION TESTS"
echo "============================================="
echo -e "${NC}"

# Check prerequisites
echo -e "${YELLOW}Checking prerequisites...${NC}"

if ! command -v stripe &> /dev/null; then
    echo -e "${RED}ERROR: Stripe CLI not installed${NC}"
    echo "Install with one of:"
    echo "  - scoop install stripe"
    echo "  - choco install stripe-cli"
    echo "  - Download from https://github.com/stripe/stripe-cli/releases"
    exit 1
fi
echo -e "${GREEN}✓ Stripe CLI installed${NC}"

if ! command -v jq &> /dev/null; then
    echo -e "${RED}ERROR: jq not installed${NC}"
    echo "Install with: scoop install jq"
    exit 1
fi
echo -e "${GREEN}✓ jq installed${NC}"

if ! command -v curl &> /dev/null; then
    echo -e "${RED}ERROR: curl not installed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ curl installed${NC}"

# Check Stripe login
if ! stripe config --list | grep -q "test_mode"; then
    echo -e "${YELLOW}Stripe not logged in. Running stripe login...${NC}"
    stripe login
fi
echo -e "${GREEN}✓ Stripe logged in${NC}"

# Load and validate config
if [ -f .env ]; then
    source .env
    echo -e "${GREEN}✓ .env loaded${NC}"
else
    echo -e "${YELLOW}No .env found, using .env.example${NC}"
    source .env.example
fi

# Validate Supabase connection
echo -e "${YELLOW}Testing Supabase connection...${NC}"
SUPABASE_TEST=$(curl -s -o /dev/null -w "%{http_code}" \
    "$SUPABASE_URL/rest/v1/users?limit=1" \
    -H "apikey: $SUPABASE_SERVICE_KEY")

if [ "$SUPABASE_TEST" == "200" ]; then
    echo -e "${GREEN}✓ Supabase connection OK${NC}"
else
    echo -e "${RED}ERROR: Cannot connect to Supabase (HTTP $SUPABASE_TEST)${NC}"
    echo "URL: $SUPABASE_URL"
    exit 1
fi

echo ""
echo -e "${BLUE}Starting webhook listener...${NC}"
echo -e "${YELLOW}Make sure 'stripe listen' is running in another terminal:${NC}"
echo "  stripe listen --forward-to $SUPABASE_URL/functions/v1/stripe-webhook"
echo ""
read -p "Press Enter when webhook listener is ready..."

# Run test suites
TOTAL_PASSED=0
TOTAL_FAILED=0

run_suite() {
    SUITE_NAME=$1
    SUITE_SCRIPT=$2

    echo ""
    echo -e "${BLUE}=============================================${NC}"
    echo -e "${BLUE}Running: $SUITE_NAME${NC}"
    echo -e "${BLUE}=============================================${NC}"

    if [ -f "$SUITE_SCRIPT" ]; then
        chmod +x "$SUITE_SCRIPT"
        if bash "$SUITE_SCRIPT"; then
            echo -e "${GREEN}$SUITE_NAME completed${NC}"
        else
            echo -e "${RED}$SUITE_NAME had failures${NC}"
        fi
    else
        echo -e "${RED}Script not found: $SUITE_SCRIPT${NC}"
    fi
}

# Ask which suites to run
echo ""
echo "Which test suites to run?"
echo "  1) Individual (1.3-1.7)"
echo "  2) Pro (2.1-2.4)"
echo "  3) Renewal (6.1-6.2)"
echo "  4) All"
echo ""
read -p "Enter choice [4]: " CHOICE
CHOICE=${CHOICE:-4}

case $CHOICE in
    1)
        run_suite "Individual Subscriptions" "./test_individual.sh"
        ;;
    2)
        run_suite "Pro Accounts" "./test_pro.sh"
        ;;
    3)
        run_suite "Renewal & Billing" "./test_renewal.sh"
        ;;
    4)
        run_suite "Individual Subscriptions" "./test_individual.sh"
        run_suite "Pro Accounts" "./test_pro.sh"
        run_suite "Renewal & Billing" "./test_renewal.sh"
        ;;
    *)
        echo "Invalid choice"
        exit 1
        ;;
esac

echo ""
echo -e "${BLUE}=============================================${NC}"
echo -e "${BLUE}ALL TESTS COMPLETED${NC}"
echo -e "${BLUE}=============================================${NC}"
echo ""
echo "Check Stripe Dashboard for webhook logs:"
echo "  https://dashboard.stripe.com/test/webhooks"
echo ""
echo "Check Supabase Studio for data:"
echo "  http://176.168.117.243:3000"
