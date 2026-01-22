# Motium Stripe Integration Tests

## Prerequisites

### 1. Install Stripe CLI

**Windows (PowerShell as Admin):**
```powershell
# Option 1: Scoop
scoop install stripe

# Option 2: Chocolatey
choco install stripe-cli

# Option 3: Direct download
# https://github.com/stripe/stripe-cli/releases/latest
```

### 2. Login to Stripe
```bash
stripe login
# Follow the browser authentication flow
```

### 3. Configure Environment
```bash
# Copy the example config
cp .env.example .env

# Edit with your values
```

### 4. Start Webhook Forwarding
```bash
# In a separate terminal, run:
stripe listen --forward-to http://176.168.117.243:8000/functions/v1/stripe-webhook
```

## Running Tests

```bash
# Run all tests
./run_all_tests.sh

# Run specific test suite
./test_individual.sh    # Tests 1.1-1.7
./test_pro.sh           # Tests 2.1-2.4
./test_attribution.sh   # Tests 3.1-3.5
./test_renewal.sh       # Tests 6.1-6.3
```

## Test Cards

| Card Number | Description |
|-------------|-------------|
| 4242424242424242 | Success |
| 4000000000000341 | Decline after attach (for renewal failures) |
| 4000000000009995 | Insufficient funds |
| 4000000000000002 | Generic decline |

## Supabase Self-Hosted

- API: http://176.168.117.243:8000
- Studio: http://176.168.117.243:3000
