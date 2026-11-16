import { defineStore } from 'pinia'
import http from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('mdm-token') || '',
    username: localStorage.getItem('mdm-username') || '',
    displayName: localStorage.getItem('mdm-display-name') || '',
    role: localStorage.getItem('mdm-role') || ''
  }),
  getters: {
    isLogin: (s) => !!s.token,
    canWrite: (s) => s.role === 'ADMIN' || s.role === 'ENGINEER'
  },
  actions: {
    async login(form) {
      const res = await http.post('/auth/login', form)
      const d = res.data
      this.token = d.token
      this.username = d.username
      this.displayName = d.displayName
      this.role = d.role
      localStorage.setItem('mdm-token', d.token)
      localStorage.setItem('mdm-username', d.username)
      localStorage.setItem('mdm-display-name', d.displayName)
      localStorage.setItem('mdm-role', d.role)
    },
    logout() {
      this.$reset()
      localStorage.clear()
    }
  }
})
