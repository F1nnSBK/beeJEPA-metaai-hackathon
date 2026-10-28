<script setup>
import { ref } from 'vue'

const emit = defineEmits(['authenticated'])

const username = ref('beekeeper')
const password = ref('')
const errorMessage = ref('')
const isSubmitting = ref(false)

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
  <form class="login-card" @submit.prevent="submit">
    <p v-if="errorMessage" class="login-card__error" role="alert">{{ errorMessage }}</p>

    <label class="login-card__field">
      <span>Beekeeper</span>
      <input v-model="username" type="text" required autocomplete="username" autocapitalize="none" spellcheck="false" />
    </label>

    <label class="login-card__field">
      <span>Password</span>
      <input v-model="password" type="password" required autocomplete="current-password" />
    </label>

    <button type="submit" class="login-card__submit" :disabled="isSubmitting">
      <span v-if="isSubmitting" class="login-card__spinner" aria-hidden="true"></span>
      <span>Enter the hive</span>
    </button>

    <p class="login-card__hint">
      Demo credentials: <code>beekeeper</code> / <code>beejepa2026</code>
    </p>
  </form>
</template>

<style scoped>
.login-card {
  display: grid;
  gap: 1rem;
  width: 100%;
  padding: clamp(1.25rem, 4vw, 2rem);
  border: 1px solid rgba(232, 163, 61, 0.22);
  border-radius: 1.25rem;
  background: rgba(17, 13, 8, 0.82);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: 0 30px 80px rgba(0, 0, 0, 0.55);
}

.login-card__field {
  display: grid;
  gap: 0.4rem;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.72rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #8c7a5b;
}

.login-card__field input {
  min-height: 48px;
  padding: 0 0.9rem;
  border: 1px solid rgba(243, 233, 210, 0.14);
  border-radius: 0.75rem;
  background: rgba(11, 9, 6, 0.9);
  color: #f3e9d2;
  /* 16px prevents iOS Safari from zooming into focused inputs. */
  font-size: 16px;
  font-family: ui-sans-serif, system-ui, sans-serif;
  letter-spacing: normal;
  text-transform: none;
  transition: border-color 0.2s ease;
}

.login-card__field input:focus {
  outline: none;
  border-color: #e8a33d;
}

.login-card__submit {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  min-height: 52px;
  margin-top: 0.25rem;
  border: none;
  border-radius: 0.75rem;
  background: linear-gradient(135deg, #f7db9a, #e8a33d 55%, #b7731f);
  color: #0b0906;
  font-family: 'Cormorant Garamond', Georgia, serif;
  font-size: 1.25rem;
  font-weight: 500;
  cursor: pointer;
  transition: filter 0.2s ease, transform 0.2s ease;
}

.login-card__submit:hover {
  filter: brightness(1.08);
}

.login-card__submit:active {
  transform: scale(0.98);
}

.login-card__submit:disabled {
  opacity: 0.6;
  cursor: progress;
}

.login-card__spinner {
  width: 1rem;
  height: 1rem;
  border: 2px solid #0b0906;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.login-card__error {
  margin: 0;
  padding: 0.75rem;
  border: 1px solid rgba(217, 71, 43, 0.45);
  border-radius: 0.6rem;
  background: rgba(217, 71, 43, 0.1);
  color: #f0a08f;
  font-size: 0.85rem;
}

.login-card__hint {
  margin: 0.25rem 0 0;
  text-align: center;
  font-size: 0.75rem;
  color: #8c7a5b;
}

.login-card__hint code {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #e8a33d;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
