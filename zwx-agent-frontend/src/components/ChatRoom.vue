<template>
  <div class="chat-container">
    <!-- 聊天记录区域 -->
    <div class="chat-messages" ref="messagesContainer">
      <div v-if="!messages.length" class="empty-state">
        <div class="empty-mark">AI</div>
        <p>今天，想聊点什么？</p>
        <span>从一段关系、一次心动，或一个难以开口的问题开始。</span>
      </div>
      <div v-for="(msg, index) in messages" :key="index" class="message-wrapper">
        <!-- AI消息 -->
        <div v-if="!msg.isUser" 
             class="message ai-message" 
             :class="[msg.type]">
          <div class="avatar ai-avatar">
            <AiAvatarFallback :type="aiType" />
          </div>
          <div class="message-bubble">
            <div class="message-content">
              {{ msg.content }}
              <span v-if="connectionStatus === 'connecting' && index === messages.length - 1" class="typing-indicator">▋</span>
            </div>
            <div v-if="msg.references?.length" class="message-references">
              <span>参考资料</span>
              <span v-for="reference in msg.references" :key="reference.objectKey">{{ reference.filename }}{{ reference.section ? ` · 第${reference.section}节` : '' }}</span>
            </div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
        </div>
        
        <!-- 用户消息 -->
        <div v-else class="message user-message" :class="[msg.type]">
            <div class="message-bubble">
            <div v-if="msg.imageUrls?.length" class="message-images">
              <img v-for="url in msg.imageUrls" :key="url" :src="url" alt="用户上传的图片" />
            </div>
            <div class="message-content">{{ msg.content }}</div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
          <div class="avatar user-avatar">
            <div class="avatar-placeholder">我</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="chat-input-container">
      <div class="chat-input">
        <input ref="fileInput" class="file-input" type="file" accept="image/jpeg,image/png,image/gif" @change="selectImages" />
        <button v-if="attachmentsEnabled" class="image-button" type="button" title="添加图片" :disabled="connectionStatus === 'connecting'" @click="fileInput?.click()">+</button>
        <div class="input-stack">
          <div v-if="selectedImages.length" class="selected-images">
            <div v-for="attachment in selectedImages" :key="attachment.preview" class="selected-image">
              <img :src="attachment.preview" alt="待发送图片" />
              <button type="button" title="移除图片" @click="removeImage(attachment)">x</button>
            </div>
          </div>
          <textarea
            v-model="inputMessage"
            @keydown.enter.prevent="sendMessage"
            @paste="pasteImages"
            placeholder="说说你的困扰..."
            class="input-box"
            :disabled="connectionStatus === 'connecting'"
          ></textarea>
        </div>
        <button 
          @click="sendMessage" 
          class="send-button"
          :disabled="connectionStatus === 'connecting' || (!inputMessage.trim() && !selectedImages.length)"
        >发送</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, watch, computed } from 'vue'
import AiAvatarFallback from './AiAvatarFallback.vue'

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  },
  connectionStatus: {
    type: String,
    default: 'disconnected'
  },
  aiType: {
    type: String,
    default: 'default'  // 'love' 或 'super'
  },
  attachmentsEnabled: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['send-message'])

const inputMessage = ref('')
const messagesContainer = ref(null)
const fileInput = ref(null)
const selectedImages = ref([])

// 根据AI类型选择不同头像
const aiAvatar = computed(() => {
  return props.aiType === 'love' 
    ? '/ai-love-avatar.png'  // 恋爱大师头像
    : '/ai-super-avatar.png' // 超级智能体头像
})

// 发送消息
const sendMessage = () => {
  if (!inputMessage.value.trim() && !selectedImages.value.length) return
  
  const payload = { message: inputMessage.value.trim(), files: selectedImages.value.map(attachment => attachment.file) }
  emit('send-message', props.attachmentsEnabled ? payload : payload.message)
  inputMessage.value = ''
  clearSelectedImages()
  if (fileInput.value) fileInput.value.value = ''
}

const selectImages = (event) => {
  addImages(Array.from(event.target.files || []))
  if (fileInput.value) fileInput.value.value = ''
}

const pasteImages = (event) => {
  if (!props.attachmentsEnabled) return
  const files = Array.from(event.clipboardData?.files || []).filter(file => file.type.startsWith('image/'))
  if (!files.length) return
  event.preventDefault()
  addImages(files)
}

const addImages = (files) => {
  selectedImages.value.push(...files.map(file => ({ file, preview: URL.createObjectURL(file) })))
}

const removeImage = (attachment) => {
  URL.revokeObjectURL(attachment.preview)
  selectedImages.value = selectedImages.value.filter(item => item !== attachment)
}

const clearSelectedImages = () => {
  selectedImages.value.forEach(attachment => URL.revokeObjectURL(attachment.preview))
  selectedImages.value = []
}

// 格式化时间
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// 自动滚动到底部
const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 监听消息变化与内容变化，自动滚动
watch(() => props.messages.length, () => {
  scrollToBottom()
})

watch(() => props.messages.map(m => m.content).join(''), () => {
  scrollToBottom()
})

onMounted(() => {
  scrollToBottom()
})
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background-color: #fff;
  border: 0;
  border-radius: 0;
  overflow: hidden;
  position: relative;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 28px clamp(24px, 8vw, 160px);
  padding-bottom: 96px;
  display: flex;
  flex-direction: column;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 84px;
}

.empty-state { align-self: center; display: flex; min-height: 100%; flex-direction: column; align-items: center; justify-content: center; color: #756c6f; text-align: center; }
.empty-mark { display: grid; width: 42px; height: 42px; margin-bottom: 16px; place-items: center; border: 1px solid #f0bdca; border-radius: 12px; background: #fff4f6; color: #bc3f62; font-size: 13px; font-weight: 700; }
.empty-state p { margin: 0 0 8px; color: #3b3436; font-size: 20px; font-weight: 650; }
.empty-state span { font-size: 13px; }

.message-wrapper {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  width: 100%;
}

.message {
  display: flex;
  align-items: flex-start;
  max-width: min(78%, 940px);
  margin-bottom: 8px;
}

.user-message {
  margin-left: auto; /* 用户消息靠右 */
  flex-direction: row; /* 正常顺序，先气泡后头像 */
}

.ai-message {
  margin-right: auto; /* AI消息靠左 */
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-avatar {
  margin-left: 8px; /* 用户头像在右侧，左边距 */
}

.ai-avatar {
  margin-right: 8px; /* AI头像在左侧，右边距 */
}
.message-references { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; color: #8a5b68; font-size: 12px; }
.message-references span:not(:first-child) { padding: 3px 6px; border: 1px solid #ebd4da; border-radius: 4px; background: #fff8f9; }

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #c94e6c;
  color: white;
  font-weight: bold;
}

.message-bubble {
  padding: 11px 14px;
  border-radius: 12px;
  position: relative;
  word-wrap: break-word;
  min-width: 100px; /* 最小宽度 */
}

.user-message .message-bubble {
  background-color: #c94e6c;
  color: white;
  border-bottom-right-radius: 4px;
  text-align: left;
}

.ai-message .message-bubble {
  border: 1px solid #ece8e9;
  background-color: #faf9f9;
  color: #3b3436;
  border-bottom-left-radius: 4px;
  text-align: left;
}

.message-content {
  font-size: 15px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.message-time {
  font-size: 12px;
  opacity: 0.7;
  margin-top: 4px;
  text-align: right;
}

.chat-input-container {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background-color: white;
  border-top: 1px solid #eee9ea;
  z-index: 100;
  height: 84px;
  box-shadow: 0 -4px 18px rgba(52, 37, 42, .04);
}

.file-input { display: none; }
.image-button { flex: 0 0 32px; width: 32px; height: 32px; border: 1px solid #dfd7d9; background: #fff; color: #a72d4f; font-size: 20px; line-height: 1; }
.image-button:disabled { color: #bcb4b6; }
.input-stack { position: relative; min-width: 0; flex: 1; }
.selected-images { position: absolute; right: 0; bottom: calc(100% + 8px); display: flex; gap: 6px; max-width: min(420px, 100%); padding: 5px; border: 1px solid #eee5e7; background: #fff; box-shadow: 0 4px 14px rgba(52, 37, 42, .1); overflow-x: auto; }
.selected-image { position: relative; width: 42px; height: 42px; flex: 0 0 42px; }
.selected-image img { display: block; width: 100%; height: 100%; border-radius: 4px; object-fit: cover; }
.selected-image button { position: absolute; top: -4px; right: -4px; display: grid; width: 16px; height: 16px; place-items: center; border: 1px solid #fff; border-radius: 50%; background: #5b5255; color: #fff; font-size: 11px; line-height: 1; }
.message-images { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.message-images img { display: block; max-width: min(300px, 100%); max-height: 260px; object-fit: cover; border-radius: 6px; }

.chat-input {
  display: flex;
  padding: 16px clamp(20px, 8vw, 160px);
  height: 100%;
  box-sizing: border-box;
  align-items: center;
}

.input-box {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #e4dedf;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 15px;
  resize: none;
  min-height: 20px;
  max-height: 44px;
  outline: none;
  transition: border-color 0.3s;
  overflow-y: auto;
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE & Edge */
}

/* 隐藏Webkit浏览器的滚动条 */
.input-box::-webkit-scrollbar {
  display: none;
}

.input-box:focus {
  border-color: #c94e6c;
  box-shadow: 0 0 0 3px rgba(201, 78, 108, .1);
}

.send-button {
  margin-left: 12px;
  background-color: #c94e6c;
  color: white;
  border: none;
  border-radius: 7px;
  padding: 0 18px;
  font-size: 14px;
  cursor: pointer;
  transition: background-color 0.3s;
  height: 38px;
  align-self: center;
}

.send-button:hover:not(:disabled) {
  background-color: #aa3c58;
}

.typing-indicator {
  display: inline-block;
  animation: blink 0.7s infinite;
  margin-left: 2px;
}

@keyframes blink {
  0% { opacity: 0; }
  50% { opacity: 1; }
  100% { opacity: 0; }
}

.input-box:disabled, .send-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .message {
    max-width: 95%;
  }
  
  .message-content {
    font-size: 15px;
  }
  
  .chat-input {
    padding: 12px;
  }
  
  .input-box {
    padding: 8px 12px;
  }
  
  .send-button {
    padding: 0 15px;
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .avatar {
    width: 32px;
    height: 32px;
  }
  
  .message-bubble {
    padding: 10px;
  }
  
  .message-content {
    font-size: 14px;
  }
  
  .chat-input-container {
    height: 72px;
  }
  
  .chat-messages {
    bottom: 72px;
  }

  .empty-state p { font-size: 18px; }
  .empty-state span { max-width: 250px; line-height: 1.6; }
}

/* 新增：不同类型消息的样式 */
.ai-answer {
  animation: fadeIn 0.3s ease-in-out;
}

.ai-final {
  /* 最终回答，可以有不同的样式，例如边框高亮等 */
}

.ai-error {
  opacity: 0.7;
}

.user-question {
  /* 用户提问的特殊样式 */
}

/* 连续消息气泡样式 */
.ai-message + .ai-message {
  margin-top: 4px;
}

.ai-message + .ai-message .avatar {
  visibility: hidden;
}

.ai-message + .ai-message .message-bubble {
  border-top-left-radius: 10px;
}
</style>
