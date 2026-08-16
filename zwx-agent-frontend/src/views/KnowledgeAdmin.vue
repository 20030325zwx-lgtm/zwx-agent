<template>
  <main class="knowledge-admin">
    <header class="admin-header">
      <div>
        <span class="eyebrow">情感分析大师</span>
        <h1>知识库管理</h1>
      </div>
      <span class="read-only">只读视图 · 实际 pgvector 数据</span>
    </header>

    <p v-if="error" class="error-state">{{ error }}</p>
    <section v-else class="knowledge-workspace" :style="{ '--chunk-panel-width': `${chunkPanelWidth}px` }">
      <aside class="document-panel">
        <div class="panel-heading"><span>文档</span><small>{{ documents.length }} 个</small></div>
        <label class="document-search">
          <span aria-hidden="true">⌕</span>
          <input v-model.trim="documentQuery" type="search" placeholder="搜索文档名称" />
        </label>
        <div class="document-list">
          <div v-if="loadingDocuments" class="panel-state">正在读取向量库...</div>
          <button v-for="document in filteredDocuments" :key="document.objectKey" type="button" class="document-row"
            :class="{ active: document.objectKey === selectedObjectKey }" @click="selectDocument(document.objectKey)">
            <strong>{{ document.filename }}</strong>
            <span>{{ document.chunkCount }} 个切片 · {{ document.sectionCount }} 节</span>
            <em :class="document.chunkCount ? 'indexed' : 'pending'">{{ document.chunkCount ? '已索引' : '未索引' }}</em>
          </button>
          <p v-if="!loadingDocuments && !filteredDocuments.length" class="panel-state">没有匹配的文档。</p>
        </div>
      </aside>

      <aside class="chunk-panel">
        <div class="panel-heading"><span>实际切片</span><small>{{ detail?.chunks.length || 0 }} 个</small></div>
        <div class="chunk-list">
          <div v-if="loadingDetail" class="panel-state">正在读取切片...</div>
          <button v-for="chunk in detail?.chunks" :key="chunk.id" type="button" class="chunk-row"
            :class="{ active: chunk.id === selectedChunkId }" @click="selectedChunkId = chunk.id">
            <span>切片 {{ chunk.chunkIndex }}<small v-if="chunk.section"> · 第 {{ chunk.section }} 节</small></span>
            <p>{{ excerpt(chunk.content) }}</p>
          </button>
          <p v-if="detail && !detail.chunks.length" class="panel-state">该文档尚未写入向量库。</p>
        </div>
      </aside>

      <div class="chunk-resizer" role="separator" aria-orientation="vertical" aria-label="调整切片列宽度" @pointerdown="startResize"></div>

      <section class="preview-panel">
        <div class="preview-heading">
          <div><span class="eyebrow">原始文档预览</span><h2>{{ detail?.filename || '选择一个文档' }}</h2></div>
          <code v-if="detail">{{ detail.objectKey }}</code>
        </div>
        <div class="preview-body">
          <pre v-if="detail?.sourceContent" class="source-preview">{{ detail.sourceContent }}</pre>
          <div v-else-if="detail" class="unavailable-preview">该文档已在向量库中，但原始文件不在当前项目内，暂不能预览。</div>
          <div v-else class="unavailable-preview">选择左侧文档以查看其原文和切片。</div>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useHead } from '@vueuse/head'
import { getLoveKnowledgeDocument, listLoveKnowledgeDocuments } from '../api'

useHead({ title: '知识库管理 - ZWX Agent' })

const documents = ref([])
const detail = ref(null)
const selectedObjectKey = ref('')
const selectedChunkId = ref('')
const loadingDocuments = ref(true)
const loadingDetail = ref(false)
const error = ref('')
const documentQuery = ref('')
const chunkPanelWidth = ref(380)
let removeResizeListeners = () => {}

const filteredDocuments = computed(() => {
  const keyword = documentQuery.value.toLocaleLowerCase('zh-CN')
  return documents.value.filter(document => document.filename.toLocaleLowerCase('zh-CN').includes(keyword))
})

const excerpt = content => content.replace(/\s+/g, ' ').slice(0, 88)
const selectDocument = async objectKey => {
  selectedObjectKey.value = objectKey
  selectedChunkId.value = ''
  loadingDetail.value = true
  try {
    detail.value = await getLoveKnowledgeDocument(objectKey)
  } catch (requestError) {
    error.value = '无法读取该文档的切片数据。'
    console.error(requestError)
  } finally {
    loadingDetail.value = false
  }
}

const startResize = event => {
  if (event.button !== 0) return
  removeResizeListeners()
  const startX = event.clientX
  const startWidth = chunkPanelWidth.value
  const resize = moveEvent => {
    chunkPanelWidth.value = Math.max(260, Math.min(680, startWidth + moveEvent.clientX - startX))
  }
  const stopResize = () => {
    window.removeEventListener('pointermove', resize)
    window.removeEventListener('pointerup', stopResize)
    removeResizeListeners = () => {}
  }
  window.addEventListener('pointermove', resize)
  window.addEventListener('pointerup', stopResize)
  removeResizeListeners = stopResize
}

onMounted(async () => {
  try {
    documents.value = await listLoveKnowledgeDocuments()
    if (documents.value.length) await selectDocument(documents.value[0].objectKey)
  } catch (requestError) {
    error.value = '无法连接知识库管理接口，请确认后端和 pgvector 服务正在运行。'
    console.error(requestError)
  } finally {
    loadingDocuments.value = false
  }
})

onBeforeUnmount(() => removeResizeListeners())
</script>

<style scoped>
.knowledge-admin { height: 100vh; overflow: hidden; background: #f7f7f7; color: #202020; }.admin-header { display: flex; height: 92px; box-sizing: border-box; align-items: center; justify-content: space-between; padding: 18px 32px; border-bottom: 1px solid #e5e5e5; background: #fff; }.eyebrow { color: #999; font-size: 12px; }.admin-header h1 { margin: 4px 0 0; font-size: 22px; font-weight: 650; }.read-only { border: 1px solid #e5e5e5; border-radius: 6px; padding: 6px 9px; color: #777; font-size: 12px; }.knowledge-workspace { display: grid; height: calc(100vh - 92px); min-height: 0; grid-template-columns: 278px var(--chunk-panel-width) 10px minmax(0, 1fr); }.document-panel, .chunk-panel, .preview-panel { min-height: 0; }.document-panel, .chunk-panel { display: flex; flex-direction: column; border-right: 1px solid #e4e4e4; background: #fff; }.document-panel { padding: 13px 10px 0; }.chunk-panel { padding: 13px 10px 0; background: #fbfbfb; }.panel-heading { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; padding: 4px 8px 12px; color: #4b4b4b; font-size: 13px; font-weight: 650; }.panel-heading small { color: #999; font-size: 12px; font-weight: 400; }.document-search { display: flex; height: 34px; flex: 0 0 auto; align-items: center; gap: 7px; margin: 0 4px 10px; border: 1px solid #e3e3e3; border-radius: 6px; padding: 0 9px; color: #9a9a9a; }.document-search span { font-size: 17px; line-height: 1; transform: rotate(-20deg); }.document-search input { min-width: 0; width: 100%; border: 0; outline: 0; background: transparent; color: #333; font: inherit; font-size: 12px; }.document-list, .chunk-list { min-height: 0; overflow-y: auto; padding-bottom: 14px; }.document-row, .chunk-row { display: block; width: 100%; border: 0; border-radius: 7px; background: transparent; color: inherit; text-align: left; }.document-row { position: relative; padding: 11px 10px; }.document-row:hover, .document-row.active, .chunk-row:hover, .chunk-row.active { background: #f0f0f0; }.document-row strong { display: block; overflow: hidden; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.document-row span { display: block; margin-top: 5px; color: #929292; font-size: 11px; }.document-row em { display: inline-block; margin-top: 7px; border-radius: 4px; padding: 2px 5px; font-size: 10px; font-style: normal; }.indexed { background: #e8f4ec; color: #43865a; }.pending { background: #f4eeee; color: #a35757; }.chunk-row { padding: 10px; }.chunk-row > span { color: #555; font-size: 12px; font-weight: 600; }.chunk-row small { color: #999; font-weight: 400; }.chunk-row p { margin: 6px 0 0; color: #888; font-size: 12px; line-height: 1.5; }.panel-state { padding: 18px 10px; color: #999; font-size: 13px; }.chunk-resizer { position: relative; z-index: 2; width: 10px; margin-left: -5px; cursor: col-resize; touch-action: none; }.chunk-resizer::after { position: absolute; top: 0; bottom: 0; left: 4px; width: 2px; background: transparent; content: ''; }.chunk-resizer:hover::after { background: #d65070; }.preview-panel { display: flex; min-width: 0; flex-direction: column; padding: 28px 34px 0; background: #fff; }.preview-heading { display: flex; min-height: 54px; flex: 0 0 auto; align-items: flex-start; justify-content: space-between; gap: 20px; border-bottom: 1px solid #ececec; padding-bottom: 17px; }.preview-heading h2 { margin: 4px 0 0; font-size: 18px; font-weight: 650; }.preview-heading code { max-width: 48%; overflow: hidden; color: #999; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.preview-body { min-height: 0; flex: 1; overflow: auto; }.source-preview { margin: 21px 0 28px; white-space: pre-wrap; color: #303030; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; line-height: 1.75; }.unavailable-preview { display: grid; height: 100%; place-items: center; color: #999; font-size: 14px; text-align: center; }.error-state { margin: 32px; color: #a34e4e; } @media (max-width: 900px) { .knowledge-admin { overflow-x: auto; }.knowledge-workspace { min-width: 890px; grid-template-columns: 240px var(--chunk-panel-width) 10px minmax(360px, 1fr); }.preview-panel { padding: 24px 24px 0; } }
</style>
