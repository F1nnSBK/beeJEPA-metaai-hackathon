<script setup>
import { ref } from 'vue'

const emit = defineEmits(['authenticated'])

const username = ref('beekeeper')
const password = ref('')
const showPassword = ref(false)
const errorMessage = ref('')
const isSubmitting = ref(false)
const passwordInputRef = ref(null)

function fillDemoCredentials() {
  username.value = 'beekeeper'
  password.value = 'beejepa2026'
  errorMessage.value = ''
}

async function submit() {
  errorMessage.value = ''
  isSubmitting.value = true
  try {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    const data = await response.json()
    if (response.ok && data.success) {
      password.value = ''
      emit('authenticated', { token: data.token, username: data.username, role: data.role })
    } else {
      errorMessage.value = data.message || 'Invalid username or password'
    }
  } catch {
    errorMessage.value = 'Failed to connect to the beeJEPA server.'
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <form class="login-card" @submit.prevent="submit" aria-label="Beekeeper console sign in">
    <div class="login-card__header">
      <div class="login-card__node">
        <span class="login-card__dot" aria-hidden="true"></span>
        <span>Colony #04 · Entrance node</span>
      </div>
      <span class="login-card__protocol">TLS · JWT</span>
    </div>

    <p v-if="errorMessage" class="login-card__error" role="alert">{{ errorMessage }}</p>

    <div class="login-card__field">
      <label for="login-username" class="login-card__label">Beekeeper ID</label>
      <input
        id="login-username"
        v-model="username"
        name="username"
        type="text"
        required
        autocomplete="username"
        autocapitalize="none"
        autocorrect="off"
        spellcheck="false"
        enterkeyhint="next"
        placeholder="beekeeper"
        @keydown.enter.prevent="passwordInputRef?.focus()"
      />
    </div>

    <div class="login-card__field">
      <div class="login-card__label-row">
        <label for="login-password" class="login-card__label">Access key</label>
        <span class="login-card__meta">Session 12h</span>
      </div>
      <div class="login-card__password-wrap">
        <input
          id="login-password"
          ref="passwordInputRef"
          v-model="password"
          name="password"
          :type="showPassword ? 'text' : 'password'"
          required
          autocomplete="current-password"
          autocapitalize="none"
          autocorrect="off"
          spellcheck="false"
          enterkeyhint="done"
          placeholder="Enter access key"
        />
        <button
          type="button"
          class="login-card__toggle"
          :aria-label="showPassword ? 'Hide password' : 'Show password'"
          :aria-pressed="showPassword"
          @click="showPassword = !showPassword"
        >
          <svg v-if="!showPassword" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
            <circle cx="12" cy="12" r="3" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M9.88 9.88a3 3 0 1 0 4.24 4.24" />
            <path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c6.5 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68" />
            <path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3.5 7 10 7a9.74 9.74 0 0 0 5.39-1.61" />
            <line x1="2" y1="2" x2="22" y2="22" />
          </svg>
        </button>
      </div>
    </div>

    <button type="submit" class="login-card__submit" :disabled="isSubmitting">
      <span v-if="isSubmitting" class="login-card__spinner" aria-hidden="true"></span>
      <span>{{ isSubmitting ? 'Authenticating...' : 'Enter the hive' }}</span>
      <svg v-if="!isSubmitting" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M5 12h14" />
        <path d="m12 5 7 7-7 7" />
      </svg>
    </button>

    <div class="login-card__footer">
      <div class="login-card__hint">
        <span class="login-card__hint-label">Demo credentials</span>
        <span class="login-card__creds"><code>beekeeper</code> / <code>beejepa2026</code></span>
      </div>
      <button type="button" class="login-card__fill" @click="fillDemoCredentials">
        Fill demo key
      </button>
    </div>
  </form>
</template>

<style scoped>
.login-card {
  display: grid;
  gap: 1rem;
  width: 100%;
  padding: clamp(1.15rem, 3.5vw, 1.5rem);
  border: 1px solid #202224;
  border-radius: 0.75rem;
  background: #111315;
  text-align: left;
}

.login-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid #181a1c;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.72rem;
  color: #939599;
}

.login-card__node {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  color: #eaebed;
}

.login-card__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7ec59f;
}

.login-card__protocol {
  font-variant-numeric: tabular-nums;
  color: #939599;
}

.login-card__field {
  display: grid;
  gap: 0.38rem;
}

.login-card__label-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
}

.login-card__label {
  font-size: 0.78rem;
  font-weight: 500;
  color: #c2c4c6;
}

.login-card__meta {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.68rem;
  font-variant-numeric: tabular-nums;
  color: #939599;
}

.login-card__field input {
  width: 100%;
  min-height: 48px;
  padding: 0 0.85rem;
  border: 1px solid #202224;
  border-radius: 0.5rem;
  background: #060709;
  color: #eaebed;
  /* 16px prevents iOS Safari from zooming into focused inputs. */
  font-size: 16px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  transition: border-color 0.15s ease;
  touch-action: manipulation;
}

.login-card__field input::placeholder {
  color: #5c5f62;
}

.login-card__field input:focus {
  outline: none;
  border-color: #eaa23a;
}

.login-card__field input:focus-visible {
  outline: 2px solid rgba(234, 162, 58, 0.25);
  outline-offset: 1px;
}

.login-card__password-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.login-card__password-wrap input {
  padding-right: 2.9rem;
}

.login-card__toggle {
  position: absolute;
  right: 2px;
  top: 2px;
  bottom: 2px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  min-height: 44px;
  padding: 0 0.6rem;
  border: none;
  border-radius: 0.4rem;
  background: transparent;
  color: #939599;
  cursor: pointer;
  touch-action: manipulation;
  transition: color 0.15s ease, background-color 0.15s ease;
}

.login-card__toggle:hover {
  color: #eaebed;
  background: #171a1d;
}

.login-card__toggle:focus-visible {
  outline: 2px solid #eaa23a;
  outline-offset: -2px;
}

.login-card__submit {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.55rem;
  min-height: 48px;
  margin-top: 0.25rem;
  padding: 0 1rem;
  border: 1px solid #eaa23a;
  border-radius: 0.5rem;
  background: #eaa23a;
  color: #060709;
  font-family: ui-sans-serif, system-ui, -apple-system, sans-serif;
  font-size: 0.92rem;
  font-weight: 600;
  letter-spacing: 0.01em;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.login-card__submit:hover:not(:disabled) {
  background: #f6bd73;
  border-color: #f6bd73;
}

.login-card__submit:focus-visible {
  outline: 2px solid #f6bd73;
  outline-offset: 2px;
}

.login-card__submit:disabled {
  opacity: 0.65;
  cursor: progress;
}

.login-card__spinner {
  width: 0.95rem;
  height: 0.95rem;
  border: 2px solid #060709;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.login-card__error {
  margin: 0;
  padding: 0.65rem 0.75rem;
  border: 1px solid rgba(243, 108, 71, 0.45);
  border-radius: 0.5rem;
  background: rgba(243, 108, 71, 0.1);
  color: #feb4a0;
  font-size: 0.82rem;
  line-height: 1.4;
}

.login-card__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
  padding-top: 0.75rem;
  border-top: 1px solid #181a1c;
}

.login-card__hint {
  display: grid;
  gap: 0.12rem;
  font-size: 0.73rem;
  color: #939599;
}

.login-card__hint-label {
  font-size: 0.68rem;
  color: #939599;
}

.login-card__creds code {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.74rem;
  color: #eaa23a;
}

.login-card__fill {
  min-height: 40px;
  padding: 0 0.85rem;
  border: 1px solid #333638;
  border-radius: 0.4rem;
  background: #171a1d;
  color: #eaebed;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.72rem;
  cursor: pointer;
  touch-action: manipulation;
  transition: border-color 0.15s ease, color 0.15s ease, background-color 0.15s ease;
}

.login-card__fill:hover {
  border-color: #eaa23a;
  color: #eaa23a;
  background: #111315;
}

.login-card__fill:focus-visible {
  outline: 2px solid #eaa23a;
  outline-offset: 2px;
}

@media (pointer: coarse) {
  .login-card__fill {
    min-height: 44px;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
