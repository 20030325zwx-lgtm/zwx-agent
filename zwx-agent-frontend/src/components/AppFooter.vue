<template>
  <footer class="app-footer">
    <div class="footer-content">
      <div class="footer-section">
        <div class="footer-logo">
          <h3>鱼皮AI超级智能体应用平台</h3>
        </div>
        <div class="footer-links">
          <a href="#">《用户协议》</a>
          <a href="#">《隐私政策》</a>
        </div>
      </div>

      <div class="footer-section">
        <h4>友情链接</h4>
        <div class="footer-links">
          <a href="https://www.mianshiya.com" target="_blank">面试鸭</a>
          <a href="#" target="_blank">编程导航</a>
          <a href="#" target="_blank">代码小抄</a>
        </div>
      </div>

      <div class="footer-section">
        <h4>联系我们</h4>
        <div class="footer-links">
          <a href="#">商务合作</a>
          <a href="#">站长：鱼皮</a>
        </div>
      </div>
    </div>

    <div class="footer-bottom">
      <p>© {{ currentYear }} 鱼皮AI超级智能体应用平台 - 让AI为你服务</p>
    </div>
  </footer>
</template>

<script setup>
import { computed } from 'vue'

// 计算当前年份
const currentYear = computed(() => new Date().getFullYear())
</script>

<style scoped>
.app-footer {
  background-color: var(--sk-bg);
  padding: 40px 0 20px;
  color: var(--sk-label-2);
  border-top: 1px solid var(--sk-separator);
  width: 100%;
  margin-top: auto;
  position: relative;
  z-index: 2;
}

.footer-content {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  padding: 0 20px;
}

.footer-section {
  flex: 1;
  min-width: 200px;
  margin-bottom: 30px;
  padding: 0 15px;
}

.footer-logo h3 {
  font-size: 1.15rem;
  font-weight: 700;
  letter-spacing: -0.01em;
  margin-bottom: 15px;
  color: var(--sk-label);
}

.footer-section h4 {
  font-size: 0.95rem;
  font-weight: 650;
  margin-bottom: 15px;
  color: var(--sk-label);
}

.footer-links {
  display: flex;
  flex-direction: column;
}

.footer-links a {
  margin-bottom: 10px;
  color: var(--sk-label-2);
  text-decoration: none;
  transition: color 0.2s;
}

.footer-links a:hover {
  color: var(--sk-blue);
}

.footer-bottom {
  text-align: center;
  padding-top: 20px;
  margin-top: 20px;
  border-top: 1px solid var(--sk-separator);
  color: var(--sk-label-3);
  font-size: 0.85rem;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .footer-content {
    flex-direction: column;
  }

  .footer-section {
    width: 100%;
    margin-bottom: 20px;
    padding: 0;
  }
}
</style>
