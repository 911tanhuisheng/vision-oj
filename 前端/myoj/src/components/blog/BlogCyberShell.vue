<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const root = ref<HTMLElement | null>(null)
let mx = 0
let my = 0

function onMove(e: MouseEvent) {
  const el = root.value
  if (!el) return
  const r = el.getBoundingClientRect()
  mx = e.clientX - r.left
  my = e.clientY - r.top
  el.style.setProperty('--cb-spot-x', `${mx}px`)
  el.style.setProperty('--cb-spot-y', `${my}px`)
  const px = ((e.clientX / (window.innerWidth || 1)) * 2 - 1) * 14
  const py = ((e.clientY / (window.innerHeight || 1)) * 2 - 1) * 10
  el.style.setProperty('--cb-parallax-x', `${px}px`)
  el.style.setProperty('--cb-parallax-y', `${py}px`)
}

function bindMagnetic() {
  const el = root.value
  if (!el) return
  const nodes = el.querySelectorAll<HTMLElement>('.blog-magnetic')
  nodes.forEach((btn) => {
    const existing = btn as HTMLElement & { __magH?: (e: MouseEvent) => void }
    if (existing.__magH) return
    const h = (ev: MouseEvent) => {
      const r = btn.getBoundingClientRect()
      const px = (ev.clientX - r.left - r.width / 2) / (r.width / 2)
      const py = (ev.clientY - r.top - r.height / 2) / (r.height / 2)
      btn.style.setProperty('--mag-x', `${px * 5}px`)
      btn.style.setProperty('--mag-y', `${py * 4}px`)
    }
    const leave = () => {
      btn.style.setProperty('--mag-x', '0px')
      btn.style.setProperty('--mag-y', '0px')
    }
    btn.addEventListener('mousemove', h)
    btn.addEventListener('mouseleave', leave)
    ;(btn as HTMLElement & { __magH?: typeof h; __magL?: typeof leave }).__magH = h
    ;(btn as HTMLElement & { __magL?: typeof leave }).__magL = leave
  })
}

function unbindMagnetic() {
  const el = root.value
  if (!el) return
  el.querySelectorAll<HTMLElement & { __magH?: (e: MouseEvent) => void; __magL?: () => void }>('.blog-magnetic').forEach((btn) => {
    if (btn.__magH) btn.removeEventListener('mousemove', btn.__magH)
    if (btn.__magL) btn.removeEventListener('mouseleave', btn.__magL)
    delete btn.__magH
    delete btn.__magL
  })
}

let magneticDebounce: ReturnType<typeof setTimeout> | undefined
let stageObserver: MutationObserver | undefined

function scheduleMagneticRefresh() {
  if (magneticDebounce) clearTimeout(magneticDebounce)
  magneticDebounce = setTimeout(() => {
    magneticDebounce = undefined
    unbindMagnetic()
    bindMagnetic()
  }, 120)
}

onMounted(() => {
  const el = root.value
  if (!el) return
  document.body.classList.add('blog-cyber-active')
  const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
  if (!mq.matches) {
    el.addEventListener('mousemove', onMove, { passive: true })
  } else {
    el.style.setProperty('--cb-spot-x', '50%')
    el.style.setProperty('--cb-spot-y', '18%')
    el.style.setProperty('--cb-parallax-x', '0px')
    el.style.setProperty('--cb-parallax-y', '0px')
  }
  void nextTick(() => {
    bindMagnetic()
    const stage = el.querySelector('.blog-cyber__stage')
    if (stage) {
      stageObserver = new MutationObserver(() => scheduleMagneticRefresh())
      stageObserver.observe(stage, { childList: true, subtree: true })
    }
  })
})

onBeforeUnmount(() => {
  document.body.classList.remove('blog-cyber-active')
  root.value?.removeEventListener('mousemove', onMove)
  stageObserver?.disconnect()
  stageObserver = undefined
  if (magneticDebounce) clearTimeout(magneticDebounce)
  magneticDebounce = undefined
  unbindMagnetic()
})
</script>

<template>
  <div ref="root" class="blog-cyber">
    <div class="blog-cyber__noise" aria-hidden="true" />
    <div class="blog-cyber__grid" aria-hidden="true" />
    <div class="blog-cyber__orbs" aria-hidden="true">
      <span class="blog-cyber__orb blog-cyber__orb--a" />
      <span class="blog-cyber__orb blog-cyber__orb--b" />
      <span class="blog-cyber__orb blog-cyber__orb--v" />
    </div>
    <div class="blog-cyber__particles" aria-hidden="true">
      <span v-for="n in 56" :key="n" class="blog-cyber__dot" :style="{ '--d': `${(n * 7) % 23}s`, '--x': `${(n * 13) % 100}%`, '--y': `${(n * 17) % 100}%` }" />
    </div>
    <div class="blog-cyber__meteors" aria-hidden="true">
      <span class="blog-cyber__meteor blog-cyber__meteor--a" />
      <span class="blog-cyber__meteor blog-cyber__meteor--b" />
      <span class="blog-cyber__meteor blog-cyber__meteor--c" />
    </div>
    <div class="blog-cyber__spotlight" aria-hidden="true" />
    <div class="blog-cyber__stage">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.blog-cyber {
  --cb-cyan: #7dd3fc;
  --cb-cyan-dim: rgba(125, 211, 252, 0.55);
  --cb-violet: #c4b5fd;
  --cb-violet-dim: rgba(196, 181, 253, 0.45);
  --cb-neon-a: #4f46e5;
  --cb-neon-b: #8b5cf6;
  --cb-neon-c: #ec4899;
  --cb-ink: #0a0e27;
  --cb-ink-mid: #0b1028;
  --cb-text: #f4f8ff;
  --cb-muted: rgba(222, 234, 252, 0.9);
  --cb-subtle: rgba(175, 195, 225, 0.78);
  --cb-glass: rgba(12, 16, 38, 0.58);
  --cb-glass-edge: color-mix(in srgb, var(--cb-neon-a) 32%, rgba(125, 211, 252, 0.35));
  --cb-glow: color-mix(in srgb, var(--cb-neon-b) 22%, transparent);

  position: relative;
  isolation: isolate;
  min-height: calc(100vh - 56px);
  width: 100%;
  overflow-x: clip;
  color: var(--cb-text);
  background:
    radial-gradient(ellipse 130% 85% at 50% -28%, color-mix(in srgb, var(--cb-neon-a) 22%, transparent), transparent 56%),
    radial-gradient(ellipse 85% 60% at 100% 18%, color-mix(in srgb, var(--cb-neon-c) 14%, transparent), transparent 52%),
    radial-gradient(ellipse 70% 55% at 0% 85%, rgba(79, 70, 229, 0.1), transparent 55%),
    radial-gradient(ellipse 50% 40% at 70% 100%, rgba(236, 72, 153, 0.06), transparent 50%),
    linear-gradient(180deg, #0a0e27 0%, var(--cb-ink-mid) 44%, #060818 100%);
}

html[data-theme='light'] .blog-cyber {
  --cb-text: #0f172a;
  --cb-muted: #334155;
  --cb-subtle: #526477;
  --cb-glass: #f2fbf9;
  --cb-glass-edge: rgba(15, 118, 110, 0.22);
  --cb-glow: rgba(14, 165, 233, 0.04);
  color: var(--cb-text);
  background:
    radial-gradient(ellipse 62% 44% at 0% 3%, rgba(20, 184, 166, 0.3), transparent 72%),
    radial-gradient(ellipse 54% 43% at 100% 22%, rgba(99, 102, 241, 0.21), transparent 72%),
    radial-gradient(ellipse 65% 48% at 48% 100%, rgba(14, 165, 233, 0.17), transparent 72%),
    linear-gradient(150deg, #d8f1ed 0%, #e1eef9 50%, #dcece9 100%);
}

.blog-cyber__noise {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 0;
  opacity: 0.03;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  mix-blend-mode: overlay;
}

html[data-theme='light'] .blog-cyber__noise {
  display: none;
}

.blog-cyber__grid {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 0;
  opacity: 0.12;
  background-image:
    linear-gradient(rgba(45, 212, 255, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(45, 212, 255, 0.07) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 85% 70% at 50% 40%, #000 20%, transparent 72%);
  animation: cb-grid-breathe 14s ease-in-out infinite;
}

html[data-theme='light'] .blog-cyber__grid {
  display: none;
}

@keyframes cb-grid-breathe {
  0%,
  100% {
    opacity: 0.1;
  }
  50% {
    opacity: 0.16;
  }
}

.blog-cyber__orbs {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
}

.blog-cyber__orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  animation: cb-orb 22s ease-in-out infinite;
}

.blog-cyber__orb--a {
  width: min(55vw, 480px);
  height: min(55vw, 480px);
  top: -18%;
  right: -8%;
  background: radial-gradient(circle, rgba(45, 212, 255, 0.35), transparent 68%);
  opacity: 0.55;
}

.blog-cyber__orb--b {
  width: min(45vw, 380px);
  height: min(45vw, 380px);
  bottom: -12%;
  left: -6%;
  background: radial-gradient(circle, rgba(56, 189, 248, 0.22), transparent 70%);
  opacity: 0.5;
  animation-delay: -7s;
}

.blog-cyber__orb--v {
  width: min(38vw, 320px);
  height: min(38vw, 320px);
  top: 42%;
  left: 38%;
  background: radial-gradient(circle, rgba(192, 132, 252, 0.28), transparent 65%);
  opacity: 0.35;
  animation-delay: -12s;
}

html[data-theme='light'] .blog-cyber__orb--a {
  background: radial-gradient(circle, rgba(14, 165, 233, 0.14), transparent 70%);
  opacity: 0.38;
}

html[data-theme='light'] .blog-cyber__orb--b {
  opacity: 0.28;
}

html[data-theme='light'] .blog-cyber__orb--v {
  opacity: 0.18;
}

html[data-theme='light'] .blog-cyber__orbs {
  display: none;
}

html[data-theme='light'] .blog-cyber__particles {
  display: none;
}

@keyframes cb-orb {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(-18px, 14px) scale(1.06);
  }
}

.blog-cyber__particles {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 1;
  overflow: hidden;
  transform: translate3d(var(--cb-parallax-x, 0px), var(--cb-parallax-y, 0px), 0);
  will-change: transform;
  transition: transform 0.45s cubic-bezier(0.22, 1, 0.36, 1);
}

.blog-cyber__meteors {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 1;
  overflow: hidden;
}

.blog-cyber__meteor {
  position: absolute;
  width: min(140px, 22vw);
  height: 1px;
  border-radius: 1px;
  opacity: 0;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(255, 255, 255, 0.95),
    rgba(196, 181, 253, 0.85),
    transparent
  );
  filter: drop-shadow(0 0 6px rgba(236, 72, 153, 0.55));
  transform: rotate(-32deg);
}

.blog-cyber__meteor--a {
  top: 18%;
  left: -5%;
  animation: cb-meteor 14s ease-in-out infinite;
  animation-delay: 2s;
}

.blog-cyber__meteor--b {
  top: 42%;
  left: 10%;
  width: min(100px, 18vw);
  animation: cb-meteor 19s ease-in-out infinite;
  animation-delay: 9s;
}

.blog-cyber__meteor--c {
  top: 8%;
  left: 40%;
  width: min(120px, 20vw);
  animation: cb-meteor 17s ease-in-out infinite;
  animation-delay: 15s;
}

html[data-theme='light'] .blog-cyber__meteor {
  opacity: 0 !important;
  animation: none !important;
}

@keyframes cb-meteor {
  0%,
  88% {
    opacity: 0;
    transform: rotate(-32deg) translate3d(0, 0, 0) scaleX(0.6);
  }
  90% {
    opacity: 1;
  }
  98% {
    opacity: 0.85;
    transform: rotate(-32deg) translate3d(140vw, 48vh, 0) scaleX(1.1);
  }
  100% {
    opacity: 0;
    transform: rotate(-32deg) translate3d(150vw, 52vh, 0) scaleX(1);
  }
}

.blog-cyber__dot {
  position: absolute;
  left: var(--x, 10%);
  top: var(--y, 20%);
  width: 2px;
  height: 2px;
  border-radius: 50%;
  background: rgba(200, 230, 255, 0.55);
  box-shadow: 0 0 6px rgba(45, 212, 255, 0.35);
  animation: cb-drift calc(18s + var(--d, 0s)) linear infinite;
  opacity: 0.35;
}

html[data-theme='light'] .blog-cyber__dot {
  background: rgba(14, 116, 144, 0.28);
  box-shadow: 0 0 3px rgba(14, 165, 233, 0.12);
  opacity: 0.22;
}

@keyframes cb-drift {
  from {
    transform: translateY(0) translateX(0);
    opacity: 0.2;
  }
  50% {
    opacity: 0.55;
  }
  to {
    transform: translateY(-120vh) translateX(12px);
    opacity: 0.15;
  }
}

.blog-cyber__spotlight {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 2;
  background:
    radial-gradient(
      420px circle at calc(var(--cb-spot-x, 50%) + 80px) calc(var(--cb-spot-y, 30%) - 40px),
      rgba(236, 72, 153, 0.07),
      transparent 55%
    ),
    radial-gradient(
      620px circle at var(--cb-spot-x, 50%) var(--cb-spot-y, 30%),
      rgba(125, 211, 252, 0.12),
      transparent 58%
    );
  mix-blend-mode: screen;
  transition: opacity 0.35s ease;
}

html[data-theme='light'] .blog-cyber__spotlight {
  /* 浅色下禁用「手电」叠层：multiply + 高透明度会让整页发灰、发糊 */
  display: none;
}

.blog-cyber__stage {
  position: relative;
  z-index: 3;
  font-family:
    'Plus Jakarta Sans',
    'Noto Sans SC',
    'DM Sans',
    var(--app-font-body, system-ui),
    -apple-system,
    sans-serif;
  font-optical-sizing: auto;
  font-feature-settings:
    'kern' 1,
    'liga' 1,
    'calt' 1;
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}

html[data-theme='light'] .blog-cyber__stage {
  -webkit-font-smoothing: subpixel-antialiased;
  -moz-osx-font-smoothing: auto;
}

@media (prefers-reduced-motion: reduce) {
  .blog-cyber__orb,
  .blog-cyber__dot,
  .blog-cyber__grid,
  .blog-cyber__meteor {
    animation: none !important;
  }

  .blog-cyber__meteor {
    opacity: 0 !important;
  }

  .blog-cyber__particles {
    transform: none !important;
    transition: none !important;
  }
}

/* —— 玻璃拟态 + 霓虹边：穿透子组件 scoped —— */
.blog-cyber__stage :deep(.panel) {
  border: 1px solid var(--cb-glass-edge) !important;
  background: linear-gradient(
    152deg,
    color-mix(in srgb, var(--cb-glass) 86%, rgba(79, 70, 229, 0.12)),
    color-mix(in srgb, var(--cb-glass) 90%, rgba(236, 72, 153, 0.06))
  ) !important;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.045) inset,
    14px 22px 48px rgba(0, 0, 0, 0.42),
    -8px 10px 32px rgba(79, 70, 229, 0.08),
    0 0 72px color-mix(in srgb, var(--cb-neon-b) 18%, transparent) !important;
  backdrop-filter: blur(22px) saturate(1.45);
  -webkit-backdrop-filter: blur(22px) saturate(1.45);
}

html[data-theme='light'] .blog-cyber__stage :deep(.panel) {
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  background: linear-gradient(155deg, #f8fffd 0%, #edf8f5 100%) !important;
  border: 1px solid rgba(15, 118, 110, 0.24) !important;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.88) inset,
    0 6px 18px rgba(15, 118, 110, 0.08),
    0 18px 38px rgba(30, 64, 99, 0.1) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel) {
  background:
    radial-gradient(circle at 92% 12%, rgba(165, 180, 252, 0.3), transparent 39%),
    linear-gradient(115deg, #0d766f 0%, #175f81 52%, #344997 100%) !important;
  border-color: rgba(13, 89, 105, 0.5) !important;
  box-shadow: 0 18px 38px rgba(18, 75, 102, 0.22) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__title--cosmic) {
  background: linear-gradient(105deg, #ffffff, #d4fbf4 50%, #e0e7ff) !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  color: transparent !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__greeting),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__lead),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__lead--cosmic),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__live) {
  color: #e8f7f5 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__eyebrow) {
  color: #a5f3e6 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__live-readers),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__live-sep),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero.panel .blog-hero__activity) {
  color: #e8f7f5 !important;
  opacity: 0.95 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-toolbar.panel) {
  background: linear-gradient(120deg, #edf9f4 0%, #e7f3f7 100%) !important;
}

/* —— 暗黑：正文区对比与继承色（压过子组件 scoped 的 --app-text） —— */
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hub),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-mine) {
  color: var(--cb-text);
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__title),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__name),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__title:not(.blog-hero__title--cosmic)),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose__title),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-mine__title),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-card__title),
html[data-theme='dark'] .blog-cyber__stage :deep(.tbl__title) {
  color: var(--cb-text) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-cards .blog-card.panel:hover .blog-card__title) {
  color: transparent !important;
  background: linear-gradient(
    100deg,
    #e0e7ff 0%,
    #c4b5fd 25%,
    #f9a8d4 55%,
    #fda4af 80%,
    #e0e7ff 100%
  ) !important;
  background-size: 220% 100% !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  animation: cb-card-title-flow 3.2s linear infinite;
}

@keyframes cb-card-title-flow {
  to {
    background-position: 220% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-cards .blog-card.panel:hover .blog-card__title),
  html[data-theme='light'] .blog-cyber__stage :deep(.blog-cards .blog-card.panel:hover .blog-card__title) {
    animation: none !important;
    background-position: 0 0 !important;
  }
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-cards .blog-card.panel:hover .blog-card__title) {
  color: transparent !important;
  background: linear-gradient(
    100deg,
    #4338ca 0%,
    #6d28d9 30%,
    #be185d 72%,
    #4338ca 100%
  ) !important;
  background-size: 220% 100% !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  animation: cb-card-title-flow 3.2s linear infinite;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__sub),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__crumb-current),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-toolbar__meta),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-mine__meta),
html[data-theme='dark'] .blog-cyber__stage :deep(.tbl__muted) {
  color: var(--cb-subtle) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__summary) {
  color: var(--cb-muted) !important;
  border-left-color: color-mix(in srgb, var(--cb-cyan) 55%, transparent) !important;
  background: color-mix(in srgb, var(--cb-cyan) 8%, rgba(6, 10, 22, 0.55)) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-card__summary) {
  color: var(--cb-muted) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-card__foot),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-card__stats) {
  color: var(--cb-subtle) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-empty__title) {
  color: var(--cb-text) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-empty__hint),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__lead:not(.blog-hero__lead--cosmic)) {
  color: var(--cb-muted) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__lead--cosmic) {
  color: color-mix(in srgb, var(--cb-muted) 100%, transparent) !important;
  opacity: 0.6 !important;
}

.blog-cyber__stage :deep(.blog-card.panel) {
  transition:
    transform 0.35s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.35s ease,
    border-color 0.25s ease;
}

.blog-cyber__stage :deep(.blog-card.panel:hover) {
  transform: translateY(-8px) !important;
  border-color: color-mix(in srgb, var(--cb-neon-c) 45%, rgba(125, 211, 252, 0.5)) !important;
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--cb-neon-b) 28%, transparent) inset,
    18px 32px 56px rgba(0, 0, 0, 0.48),
    -10px 14px 40px rgba(79, 70, 229, 0.12),
    0 0 56px color-mix(in srgb, var(--cb-neon-a) 22%, transparent),
    0 0 96px color-mix(in srgb, var(--cb-neon-c) 14%, transparent) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-card.panel) {
  border-color: rgba(15, 118, 110, 0.24) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-card.panel:hover) {
  transform: translateY(-4px) !important;
  border-color: rgba(99, 102, 241, 0.38) !important;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 1) inset,
    0 2px 6px rgba(15, 23, 42, 0.05),
    0 12px 28px rgba(15, 23, 42, 0.09) !important;
}

.blog-cyber__stage :deep(.blog-hero__title--cosmic) {
  font-family:
    'Syne',
    'Plus Jakarta Sans',
    'Noto Sans SC',
    'DM Sans',
    ui-sans-serif,
    system-ui,
    sans-serif !important;
  font-weight: 800 !important;
  letter-spacing: -0.03em !important;
  line-height: 1.12 !important;
  background: linear-gradient(
    105deg,
    #a5b4fc 0%,
    #818cf8 18%,
    #c084fc 52%,
    #f472b6 88%,
    #fda4af 100%
  ) !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  color: transparent !important;
  filter: drop-shadow(0 0 28px color-mix(in srgb, var(--cb-neon-b) 35%, transparent))
    drop-shadow(0 0 48px color-mix(in srgb, var(--cb-neon-c) 22%, transparent));
  text-shadow: none !important;
  animation: none !important;
}

.blog-cyber__stage :deep(.blog-hero__title:not(.blog-hero__title--cosmic)),
.blog-cyber__stage :deep(.blog-read__title),
.blog-cyber__stage :deep(.blog-compose__title),
.blog-cyber__stage :deep(.blog-mine__title) {
  font-family:
    'Plus Jakarta Sans',
    'Noto Sans SC',
    'DM Sans',
    ui-sans-serif,
    system-ui,
    sans-serif;
  font-weight: 800;
  letter-spacing: -0.028em;
  text-wrap: balance;
  text-shadow:
    0 1px 0 rgba(0, 0, 0, 0.45),
    0 0 48px rgba(45, 212, 255, 0.14);
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__title--cosmic) {
  animation: cb-cosmic-title 8s ease-in-out infinite !important;
}

@keyframes cb-cosmic-title {
  0%,
  100% {
    filter: drop-shadow(0 0 22px color-mix(in srgb, var(--cb-neon-b) 30%, transparent))
      drop-shadow(0 0 40px color-mix(in srgb, var(--cb-neon-c) 16%, transparent));
  }
  50% {
    filter: drop-shadow(0 0 32px color-mix(in srgb, var(--cb-neon-a) 28%, transparent))
      drop-shadow(0 0 56px color-mix(in srgb, var(--cb-neon-c) 24%, transparent));
  }
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__title:not(.blog-hero__title--cosmic)),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__title),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose__title),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-mine__title) {
  animation: cb-title-glow 10s ease-in-out infinite;
}

@keyframes cb-title-glow {
  0%,
  100% {
    text-shadow:
      0 1px 0 rgba(0, 0, 0, 0.45),
      0 0 40px rgba(45, 212, 255, 0.12);
  }
  50% {
    text-shadow:
      0 1px 0 rgba(0, 0, 0, 0.45),
      0 0 56px rgba(94, 231, 255, 0.2),
      0 0 80px rgba(167, 139, 250, 0.1);
  }
}

@media (prefers-reduced-motion: reduce) {
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__title--cosmic),
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__title:not(.blog-hero__title--cosmic)),
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__title),
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose__title),
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-mine__title) {
    animation: none !important;
  }
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__title--cosmic) {
  filter: none !important;
  animation: none !important;
  text-shadow: none !important;
  background: linear-gradient(
    102deg,
    #1e1b4b 0%,
    #4c1d95 36%,
    #831843 72%,
    #312e81 100%
  ) !important;
  background-size: 160% 100% !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  color: transparent !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__title:not(.blog-hero__title--cosmic)),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-read__title),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose__title),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-mine__title) {
  text-shadow: none;
}

.blog-cyber__stage :deep(.blog-hero__greeting) {
  color: var(--cb-muted) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__greeting) {
  color: #475569 !important;
  opacity: 1 !important;
  font-weight: 600 !important;
}

.blog-cyber__stage :deep(.blog-hero__eyebrow),
.blog-cyber__stage :deep(.blog-compose__eyebrow),
.blog-cyber__stage :deep(.blog-mine__eyebrow) {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: var(--cb-cyan) !important;
  letter-spacing: 0.26em;
  text-shadow: 0 0 20px rgba(94, 231, 255, 0.4);
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__eyebrow),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose__eyebrow),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-mine__eyebrow) {
  animation: cb-eyebrow-shimmer 6s ease-in-out infinite;
}

@keyframes cb-eyebrow-shimmer {
  0%,
  100% {
    opacity: 1;
    filter: brightness(1);
  }
  50% {
    opacity: 0.92;
    filter: brightness(1.12);
  }
}

@media (prefers-reduced-motion: reduce) {
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-hero__eyebrow),
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose__eyebrow),
  html[data-theme='dark'] .blog-cyber__stage :deep(.blog-mine__eyebrow) {
    animation: none !important;
  }
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__eyebrow),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose__eyebrow),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-mine__eyebrow) {
  color: #0e7490 !important;
  text-shadow: none;
}

.blog-cyber__stage :deep(.blog-hero__title-accent) {
  background: linear-gradient(105deg, var(--cb-cyan), var(--cb-violet), #f472b6) !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  color: transparent !important;
  filter: drop-shadow(0 0 20px rgba(192, 132, 252, 0.35));
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__title-accent) {
  filter: none;
}

.blog-cyber__stage :deep(.blog-hero__lead),
.blog-cyber__stage :deep(.blog-read__sub),
.blog-cyber__stage :deep(.blog-compose__hint),
.blog-cyber__stage :deep(.blog-mine__lead) {
  color: var(--cb-muted) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__lead),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-read__sub),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose__hint),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-mine__lead) {
  color: #334155 !important;
  font-weight: 500 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__lead--cosmic) {
  color: #334155 !important;
  opacity: 1 !important;
  font-weight: 500 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__live) {
  color: #475569 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__live-readers),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-hero__activity) {
  color: #64748b !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-toolbar__meta),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-toolbar__sync) {
  color: #64748b !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-card__summary) {
  color: #334155 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-card__foot),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-card__stats),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-card__time),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-card__author) {
  color: #64748b !important;
  opacity: 1 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-empty__hint) {
  color: #475569 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-read__summary) {
  color: #334155 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-card__title) {
  color: #0f172a !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.arco-input::placeholder),
html[data-theme='light'] .blog-cyber__stage :deep(.arco-textarea::placeholder) {
  color: #94a3b8 !important;
  opacity: 1 !important;
}

.blog-cyber__stage :deep(.blog-read__content .blog-md) {
  font-size: clamp(17px, 0.28vw + 16.2px, 18.5px);
  line-height: 1.95;
  letter-spacing: 0.015em;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-read__content .blog-md) {
  color: color-mix(in srgb, var(--cb-text) 96%, var(--cb-muted) 4%) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-read__content .blog-md) {
  color: #334155 !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose .arco-input),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose .arco-textarea),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-toolbar-host .arco-input) {
  color: #0f172a !important;
}

.blog-cyber__stage :deep(.chip-btn--primary),
.blog-cyber__stage :deep(.pill--primary) {
  background: linear-gradient(135deg, #0891b2 0%, #2563eb 42%, #7c3aed 100%) !important;
  box-shadow:
    0 0 24px rgba(45, 212, 255, 0.35),
    0 12px 28px rgba(37, 99, 235, 0.35) !important;
  border: 1px solid rgba(192, 132, 252, 0.35) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.chip-btn--primary),
html[data-theme='light'] .blog-cyber__stage :deep(.pill--primary) {
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.06),
    0 4px 14px rgba(79, 70, 229, 0.2) !important;
  border: 1px solid rgba(67, 56, 202, 0.32) !important;
}

.blog-cyber__stage :deep(.chip-btn--primary:hover),
.blog-cyber__stage :deep(.pill--primary:hover) {
  box-shadow:
    0 0 36px rgba(45, 212, 255, 0.45),
    0 16px 36px rgba(124, 58, 237, 0.35) !important;
  filter: brightness(1.08);
}

html[data-theme='light'] .blog-cyber__stage :deep(.chip-btn--primary:hover),
html[data-theme='light'] .blog-cyber__stage :deep(.pill--primary:hover) {
  filter: none;
  box-shadow:
    0 1px 3px rgba(15, 23, 42, 0.08),
    0 6px 18px rgba(79, 70, 229, 0.22) !important;
}

.blog-cyber__stage :deep(.chip-btn--neon) {
  position: relative;
  overflow: hidden;
  isolation: isolate;
}

.blog-cyber__stage :deep(.chip-btn--neon::after) {
  content: '';
  position: absolute;
  inset: -40%;
  background: conic-gradient(
    from 200deg,
    transparent 0deg,
    rgba(255, 255, 255, 0.14) 42deg,
    rgba(236, 72, 153, 0.2) 90deg,
    transparent 140deg
  );
  opacity: 0;
  z-index: -1;
  pointer-events: none;
  transform: scale(0.6);
  transition: opacity 0.35s ease, transform 0.35s ease;
}

.blog-cyber__stage :deep(.chip-btn--neon:hover::after) {
  opacity: 1;
  transform: scale(1);
  animation: cb-neon-pulse-ring 1.6s ease-out infinite;
}

@keyframes cb-neon-pulse-ring {
  0% {
    transform: scale(0.75) rotate(0deg);
    opacity: 0.55;
  }
  70% {
    opacity: 0.2;
  }
  100% {
    transform: scale(1.35) rotate(18deg);
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .blog-cyber__stage :deep(.chip-btn--neon:hover::after) {
    animation: none !important;
    opacity: 0.35 !important;
    transform: scale(1) !important;
  }
}

.blog-cyber__stage :deep(.chip-btn--ghost),
.blog-cyber__stage :deep(.pill--ghost) {
  border-color: rgba(45, 212, 255, 0.35) !important;
  color: var(--cb-cyan) !important;
  background: rgba(15, 23, 42, 0.35) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.chip-btn--ghost),
html[data-theme='light'] .blog-cyber__stage :deep(.pill--ghost) {
  background: #ffffff !important;
  border: 1px solid rgba(15, 23, 42, 0.14) !important;
  color: #0369a1 !important;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);
}

.blog-cyber__stage :deep(.blog-magnetic) {
  transition:
    transform 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.25s ease;
  transform: translate(var(--mag-x, 0px), var(--mag-y, 0px));
}

.blog-cyber__stage :deep(.chip-btn.blog-magnetic:hover),
.blog-cyber__stage :deep(.pill.blog-magnetic:hover),
.blog-cyber__stage :deep(.like-btn.blog-magnetic:hover:not(:disabled)),
.blog-cyber__stage :deep(.ghost-link.blog-magnetic:hover),
.blog-cyber__stage :deep(.blog-read__crumb-link.blog-magnetic:hover),
.blog-cyber__stage :deep(.blog-read__back.blog-magnetic:hover),
.blog-cyber__stage :deep(.tab.blog-magnetic:hover) {
  transform: translate(var(--mag-x, 0px), var(--mag-y, 0px)) translateY(-1px);
}

.blog-cyber__stage :deep(.blog-magnetic.arco-btn:hover:not(:disabled)) {
  transform: translate(var(--mag-x, 0px), var(--mag-y, 0px));
}

.blog-cyber__stage :deep(.like-btn) {
  border-color: rgba(45, 212, 255, 0.35) !important;
  background: rgba(15, 23, 42, 0.4) !important;
}

.blog-cyber__stage :deep(.like-btn:hover:not(:disabled)) {
  box-shadow: 0 0 28px rgba(45, 212, 255, 0.25) !important;
}

.blog-cyber__stage :deep(.blog-tag),
.blog-cyber__stage :deep(.blog-read__tag) {
  border: 1px solid rgba(192, 132, 252, 0.35);
  background: rgba(192, 132, 252, 0.12) !important;
  color: #e9d5ff !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-tag),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-read__tag) {
  color: #5b21b6 !important;
  background: rgba(245, 243, 255, 0.98) !important;
  border: 1px solid rgba(91, 33, 182, 0.28) !important;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 1) inset;
}

.blog-cyber__stage :deep(.arco-input-wrapper),
.blog-cyber__stage :deep(.arco-textarea-wrapper) {
  border-radius: 12px !important;
  background: rgba(15, 23, 42, 0.45) !important;
  border: 1px solid rgba(45, 212, 255, 0.15) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.arco-input-wrapper),
html[data-theme='light'] .blog-cyber__stage :deep(.arco-textarea-wrapper) {
  background: #ffffff !important;
  border: 1px solid rgba(15, 23, 42, 0.14) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.blog-cyber__stage :deep(.blog-toolbar-host .arco-input-wrapper) {
  transition:
    transform 0.3s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.3s ease,
    border-color 0.22s ease;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-toolbar-host .arco-input-wrapper:focus-within),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-toolbar-host .arco-input-wrapper.arco-input-focus) {
  transform: scale(1.02);
  border-color: color-mix(in srgb, #ec4899 40%, rgba(129, 140, 248, 0.65)) !important;
  box-shadow:
    0 0 0 1px rgba(129, 140, 248, 0.22),
    0 0 40px rgba(79, 70, 229, 0.32) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-toolbar-host .arco-input-wrapper:focus-within),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-toolbar-host .arco-input-wrapper.arco-input-focus) {
  transform: scale(1.012);
  border-color: rgba(14, 116, 144, 0.38) !important;
  box-shadow:
    0 0 0 3px rgba(14, 165, 233, 0.12),
    0 4px 14px rgba(15, 23, 42, 0.06) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-card.panel.blog-card--dim:not(:hover)) {
  opacity: 0.64;
  filter: saturate(0.86);
}

/* 写文章 / 编辑页：表单控件可读性 */
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-input-wrapper),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-textarea-wrapper) {
  background: color-mix(in srgb, rgba(15, 23, 42, 0.88) 90%, rgba(94, 231, 255, 0.06)) !important;
  border-color: rgba(94, 231, 255, 0.26) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-input-wrapper:hover),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-textarea-wrapper:hover) {
  border-color: rgba(94, 231, 255, 0.34) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-input-wrapper:focus-within),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-input-wrapper.arco-input-focus),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-textarea-wrapper:focus-within),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-textarea-wrapper.arco-textarea-focus) {
  border-color: rgba(94, 231, 255, 0.48) !important;
  box-shadow:
    0 0 0 1px rgba(94, 231, 255, 0.1),
    0 0 28px rgba(45, 212, 255, 0.14) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-input),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-textarea) {
  color: var(--cb-text) !important;
  caret-color: var(--cb-cyan);
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-input::placeholder),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .arco-textarea::placeholder) {
  color: var(--cb-subtle) !important;
  opacity: 1 !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .label) {
  color: color-mix(in srgb, var(--cb-muted) 90%, var(--cb-cyan) 10%) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .radio-line),
html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .radio-line span) {
  color: var(--cb-muted) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .radio-line input[type='radio']) {
  accent-color: var(--cb-cyan);
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose__loading) {
  color: var(--cb-muted) !important;
}

html[data-theme='dark'] .blog-cyber__stage :deep(.blog-compose .status-field) {
  border-color: rgba(94, 231, 255, 0.26) !important;
  background: color-mix(in srgb, rgba(8, 12, 26, 0.72) 88%, rgba(94, 231, 255, 0.09)) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose .arco-input),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose .arco-textarea) {
  color: var(--cb-text) !important;
}

html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose .arco-input::placeholder),
html[data-theme='light'] .blog-cyber__stage :deep(.blog-compose .arco-textarea::placeholder) {
  color: color-mix(in srgb, var(--cb-muted) 78%, transparent) !important;
  opacity: 1 !important;
}

.blog-cyber__stage :deep(.arco-btn-primary) {
  background: linear-gradient(135deg, #0891b2, #2563eb) !important;
  border: none !important;
  box-shadow: 0 0 24px rgba(45, 212, 255, 0.25);
}

.blog-cyber__stage :deep(.tbl th) {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--cb-cyan-dim) !important;
}

.blog-cyber__stage :deep(.tbl) {
  border-color: rgba(45, 212, 255, 0.12) !important;
}
</style>

<style>
html[data-theme='dark'] body.blog-cyber-active {
  scrollbar-color: rgba(129, 140, 248, 0.72) rgba(10, 14, 39, 0.92);
  scrollbar-width: thin;
}

html[data-theme='dark'] body.blog-cyber-active::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

html[data-theme='dark'] body.blog-cyber-active::-webkit-scrollbar-track {
  background: rgba(10, 14, 39, 0.55);
}

html[data-theme='dark'] body.blog-cyber-active::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, #6366f1, #a855f7 52%, #ec4899);
  border-radius: 999px;
  border: 2px solid rgba(10, 14, 39, 0.4);
}

html[data-theme='light'] body.blog-cyber-active {
  scrollbar-color: rgba(14, 116, 144, 0.5) rgba(241, 245, 249, 0.95);
  scrollbar-width: thin;
}

html[data-theme='light'] body.blog-cyber-active::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

html[data-theme='light'] body.blog-cyber-active::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, #0ea5e9, #6366f1);
  border-radius: 999px;
  border: 2px solid rgba(255, 255, 255, 0.65);
}
</style>
