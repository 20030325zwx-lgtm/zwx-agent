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
    <section v-else class="knowledge-workspace">
      <aside class="document-panel">
        <div class="panel-heading"><span>文档</span><small>{{ documents.length }} 个</small></div>
        <div v-if="loadingDocuments" class="panel-state">正在读取向量库...</div>
        <button v-for="document in documents" :key="document.objectKey" type="button" class="document-row"
          :class="{ active: document.objectKey === selectedObjectKey }" @click="selectDocument(document.objectKey)">
          <strong>{{ document.filename }}</strong>
          <span>{{ document.chunkCount }} 个切片 · {{ document.sectionCount }} 节</span>
          <em :class="document.chunkCount ? 'indexed' : 'pending'">{{ document.chunkCount ? '已索引' : '未索引' }}</em>
        </button>
      </aside>

      <aside class="chunk-panel">
        <div class="panel-heading"><span>实际切片</span><small>{{ detail?.chunks.length || 0 }} 个</small></div>
        <div v-if="loadingDetail" class="panel-state">正在读取切片...</div>
        <button v-for="chunk in detail?.chunks" :key="chunk.id" type="button" class="chunk-row"
          :class="{ active: chunk.id === selectedChunkId }" @click="selectedChunkId = chunk.id">
          <span>切片 {{ chunk.chunkIndex }}<small v-if="chunk.section"> · 第 {{ chunk.section }} 节</small></span>
          <p>{{ excerpt(chunk.content) }}</p>
        </button>
        <p v-if="detail && !detail.chunks.length" class="panel-state">该文档尚未写入向量库。</p>
      </aside>

      <section class="preview-panel">
        <div class="preview-heading">
          <div><span class="eyebrow">原始文档预览</span><h2>{{ detail?.filename || '选择一个文档' }}</h2></div>
          <code v-if="detail">{{ detail.objectKey }}</code>
        </div>
        <pre v-if="detail?.sourceContent" class="source-preview">{{ detail.sourceContent }}</pre>
        <div v-else-if="detail" class="unavailable-preview">该文档已在向量库中，但原始文件不在当前项目内，暂不能预览。</div>
        <div v-else class="unavailable-preview">选择左侧文档以查看其原文和切片。</div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
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
</script>

<style scoped>
.knowledge-admin { min-height: 100vh; background: #f7f7f7; color: #202020; }
.admin-header { display: flex; min-height: 92px; align-items: center; justify-content: space-between; padding: 18px 32px; border-bottom: 1px solid #e5e5e5; background: #fff; }.eyebrow { color: #999; font-size: 12px; }.admin-header h1 { margin: 4px 0 0; font-size: 22px; font-weight: 650; }.read-only { border: 1px solid #e5e5e5; border-radius: 6px; padding: 6px 9px; color: #777; font-size: 12px; }.knowledge-workspace { display: grid; min-height: calc(100vh - 92px); grid-template-columns: 278px 330px minmax(0, 1fr); }.document-panel, .chunk-panel { overflow-y: auto; border-right: 1px solid #e4e4e4; background: #fff; }.document-panel { padding: 13px 10px; }.chunk-panel { padding: 13px 10px; background: #fbfbfb; }.panel-heading { display: flex; align-items: center; justify-content: space-between; padding: 4px 8px 12px; color: #4b4b4b; font-size: 13px; font-weight: 650; }.panel-heading small { color: #999; font-size: 12px; font-weight: 400; }.document-row, .chunk-row { display: block; width: 100%; border: 0; border-radius: 7px; background: transparent; color: inherit; text-align: left; }.document-row { position: relative; padding: 11px 10px; }.document-row:hover, .document-row.active, .chunk-row:hover, .chunk-row.active { background: #f0f0f0; }.document-row strong { display: block; overflow: hidden; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.document-row span { display: block; margin-top: 5px; color: #929292; font-size: 11px; }.document-row em { display: inline-block; margin-top: 7px; border-radius: 4px; padding: 2px 5px; font-size: 10px; font-style: normal; }.indexed { background: #e8f4ec; color: #43865a; }.pending { background: #f4eeee; color: #a35757; }.chunk-row { padding: 10px; }.chunk-row > span { color: #555; font-size: 12px; font-weight: 600; }.chunk-row small { color: #999; font-weight: 400; }.chunk-row p { display: -webkit-box; margin: 6px 0 0; overflow: hidden; color: #888; font-size: 12px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }.panel-state { padding: 18px 10px; color: #999; font-size: 13px; }.preview-panel { min-width: 0; overflow: hidden; padding: 28px 34px; background: #fff; }.preview-heading { display: flex; min-height: 54px; align-items: flex-start; justify-content: space-between; gap: 20px; border-bottom: 1px solid #ececec; padding-bottom: 17px; }.preview-heading h2 { margin: 4px 0 0; font-size: 18px; font-weight: 650; }.preview-heading code { max-width: 48%; overflow: hidden; color: #999; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.source-preview { height: calc(100vh - 204px); margin: 21px 0 0; overflow: auto; white-space: pre-wrap; color: #303030; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; line-height: 1.75; }.unavailable-preview { display: grid; height: calc(100vh - 204px); place-items: center; color: #999; font-size: 14px; text-align: center; }.error-state { margin: 32px; color: #a34e4e; } @media (max-width: 900px) { .knowledge-workspace { grid-template-columns: 240px 270px minmax(360px, 1fr); min-width: 870px; }.knowledge-admin { overflow-x: auto; }.preview-panel { padding: 24px; } }
</style>
