<template>
  <div class="legal-page">
    <header class="legal-hero panel">
      <p class="legal-hero__eyebrow">Myoj Charter</p>
      <h1>隐私政策</h1>
      <p>我们重视您的个人信息与数据安全。本政策说明平台如何收集、使用、存储与保护您的信息。</p>
      <p class="legal-meta">更新日期：2026-04-02</p>
    </header>

    <section class="legal-card panel">
      <h2>1. 我们收集的信息</h2>
      <ul>
        <li>账户信息：用户名、邮箱（如您填写）、登录凭证等。</li>
        <li>使用信息：提交记录、比赛参与记录、页面访问行为等。</li>
        <li>设备与日志信息：最近登录 IP、由 IP 估算的归属地、设备类别、操作系统、浏览器类型，以及浏览器可提供的逻辑处理器数量、粗略内存和屏幕 CSS 尺寸，用于账号管理、安全与运维。IP 归属地不是实时定位，设备参数也可能不准确。</li>
      </ul>
    </section>

    <section class="legal-card panel">
      <h2>2. 信息使用目的</h2>
      <ul>
        <li>提供登录鉴权、题目评测、社区互动等核心服务。</li>
        <li>优化产品体验与稳定性，修复故障并提升安全防护。</li>
        <li>在必要范围内进行统计分析，以改进平台功能。</li>
      </ul>
    </section>

    <section class="legal-card panel">
      <h2>3. 信息共享与披露</h2>
      <p>除法律法规要求或获得您明确授权外，我们不会向第三方出售您的个人信息。若涉及服务合作方，仅在实现必要功能的最小范围内共享。</p>
    </section>

    <section class="legal-card panel">
      <h2>4. 信息存储与保护</h2>
      <ul>
        <li>我们采用访问控制、日志审计、传输加密等措施保护数据安全。</li>
        <li>仅在实现业务目的所必需的期限内保存个人信息。</li>
        <li>若发生安全事件，我们将依法及时通知并采取补救措施。</li>
      </ul>
    </section>

    <section class="legal-card panel">
      <h2>5. 您的权利</h2>
      <p>您可依法行使查询、更正、删除、撤回授权等权利。具体可通过平台提供的联系方式提交申请，我们将在合理期限内处理。</p>
    </section>

    <section class="legal-card panel">
      <h2>6. 未成年人保护</h2>
      <p>若您为未成年人，请在监护人同意与指导下使用平台。我们将按法律要求采取相应保护措施。</p>
    </section>

    <section class="legal-card panel">
      <h2>7. 政策更新</h2>
      <p>隐私政策可能根据业务变化或法律要求进行更新。更新后将通过页面公示，继续使用即视为同意更新后的内容。</p>
    </section>
  </div>
</template>

<style scoped>
.legal-page {
  max-width: 980px;
  margin: 0 auto;
  padding: 28px 20px 44px;
  color: var(--app-text);
}

.panel {
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  box-shadow: var(--app-card-shadow);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.legal-hero,
.legal-card {
  border-radius: 26px;
}

.legal-hero {
  margin-bottom: 16px;
  padding: 28px;
  overflow: hidden;
  position: relative;
}

.legal-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at top left, rgba(255, 138, 61, 0.18), transparent 30%),
    radial-gradient(circle at bottom right, rgba(15, 140, 122, 0.16), transparent 28%);
  pointer-events: none;
}

.legal-hero__eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--app-accent);
  font-weight: 700;
}

.legal-hero h1,
.legal-card h2 {
  position: relative;
  margin: 0;
  font-family: var(--app-font-display);
}

.legal-hero h1 {
  font-size: clamp(34px, 4vw, 52px);
  line-height: 1.08;
}

.legal-hero > p {
  position: relative;
  margin: 12px 0 0;
  line-height: 1.85;
  color: var(--app-text-muted);
}

.legal-meta {
  font-size: 12px;
  color: var(--app-text-subtle) !important;
}

.legal-card {
  margin-top: 12px;
  padding: 20px 22px;
}

.legal-card h2 {
  font-size: 24px;
  line-height: 1.2;
}

.legal-card p,
.legal-card li {
  margin-top: 10px;
  line-height: 1.9;
  color: var(--app-text-muted);
}

.legal-card ul {
  margin: 0;
  padding-left: 20px;
}

@media (max-width: 640px) {
  .legal-page {
    padding: 20px 14px 40px;
  }

  .legal-hero,
  .legal-card {
    border-radius: 22px;
    padding: 20px;
  }
}
</style>
