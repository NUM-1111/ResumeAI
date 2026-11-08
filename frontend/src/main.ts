import { createApp, defineComponent, h, onMounted, ref } from 'vue';
import './style.css';

interface EnvironmentResult {
  application: string; database: string; migrationVersion: string; marker: string; scope: string;
}

createApp(defineComponent({
  setup() {
    const result = ref<EnvironmentResult | null>(null);
    const error = ref('');
    const loading = ref(false);
    async function check() {
      loading.value = true; error.value = ''; result.value = null;
      try {
        const response = await fetch('/api/v1/environment', { credentials: 'same-origin', cache: 'no-store' });
        if (!response.ok) throw new Error(response.status === 401 ? '会话已失效，请重新登录。' : `检查失败（HTTP ${response.status}），未确认数据库可用。`);
        result.value = await response.json() as EnvironmentResult;
      } catch (cause) { error.value = cause instanceof Error ? cause.message : '检查失败，请重试。'; }
      finally { loading.value = false; }
    }
    async function logout() {
      try {
        const response = await fetch('/api/v1/csrf', { credentials: 'same-origin', cache: 'no-store' });
        if (!response.ok) throw new Error('无法取得退出凭据。');
        const csrf: { token: string; headerName: string } = await response.json();
        const exit = await fetch('/logout', { method: 'POST', credentials: 'same-origin', headers: { [csrf.headerName]: csrf.token } });
        if (!exit.ok) throw new Error('退出失败，请重试。');
        window.location.assign('/login?logout');
      } catch (cause) { error.value = cause instanceof Error ? cause.message : '退出失败。'; }
    }
    onMounted(check);
    return () => h('main', [
      h('p', { class: 'eyebrow' }, 'AgentCV · ENV-02'),
      h('h1', '最小工程运行验证'),
      h('p', '此页面仅验证 Vue 构建、Boot 同源托管、会话及数据库迁移，不提供简历业务。'),
      loading.value ? h('p', { role: 'status' }, '正在检查数据库与迁移状态…') : null,
      error.value ? h('p', { role: 'alert' }, error.value) : null,
      result.value ? h('section', { 'aria-label': '服务端实际检查结果' }, [
        h('h2', '服务端检查成功'),
        h('p', `数据库：${result.value.database}`),
        h('p', `Flyway 迁移版本：${result.value.migrationVersion}`),
        h('p', result.value.marker),
      ]) : null,
      h('button', { onClick: check, disabled: loading.value }, '重新检查'),
      h('button', { onClick: logout }, '退出登录'),
    ]);
  },
})).mount('#app');