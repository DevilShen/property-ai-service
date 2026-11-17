<script setup lang="ts">
/** 共享卡片：标题栏（title/subtitle + head/extra 插槽）+ 主体 + 底栏。 */
withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    /** 主体是否带内边距（表格/列表类卡片可传 false） */
    padded?: boolean
    /** 嵌套场景去掉边框与阴影 */
    flat?: boolean
  }>(),
  { title: '', subtitle: '', padded: true, flat: false },
)
</script>

<template>
  <section class="w-card" :class="{ 'is-flat': flat }">
    <header v-if="title || subtitle || $slots.head || $slots.extra" class="w-card__head">
      <div class="w-card__titles">
        <h3 v-if="title || $slots.head" class="w-card__title">
          <slot name="head">{{ title }}</slot>
        </h3>
        <p v-if="subtitle" class="w-card__sub">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.extra" class="w-card__extra"><slot name="extra" /></div>
    </header>
    <div class="w-card__body" :class="{ 'is-padded': padded }">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="w-card__foot">
      <slot name="footer" />
    </footer>
  </section>
</template>

<style scoped>
.w-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--c-card);
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  box-shadow: var(--sh-md);
}

.w-card.is-flat {
  border-color: var(--c-line-2);
  box-shadow: none;
}

.w-card__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 12px 16px;
  border-bottom: 1px solid var(--c-line-2);
  flex: none;
}

.w-card__titles {
  min-width: 0;
}

.w-card__title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.1px;
  color: var(--c-ink);
}

.w-card__sub {
  margin-top: 2px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.w-card__extra {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
}

.w-card__body {
  flex: 1;
  min-height: 0;
}

.w-card__body.is-padded {
  padding: 14px 16px;
}

.w-card__foot {
  flex: none;
  padding: 10px 16px;
  border-top: 1px solid var(--c-line-2);
}
</style>
