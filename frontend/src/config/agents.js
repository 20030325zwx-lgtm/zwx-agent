// kind 区分能力形态：qa = 单轮对话问答（专注对话/检索/生成）；agent = 真正的自主智能体（自主规划、调用工具、多步执行）
const registry = {
  super: {
    key: 'super',
    name: '超级智能体',
    kind: 'agent',
    category: '效率协作',
    path: '/super-agent',
    icon: '✦',
    iconClass: 'super-icon',
    description: '统一入口：可规划任务、调用工具、联网检索、处理图片并多步执行',
    capabilities: { attachments: true, conversations: false, executionTrace: false, privateKnowledge: true },
    chat: {
      emptyTitle: '从一个目标开始。',
      emptyDescription: '描述目标、约束或卡点，我会帮你理清下一步。',
      inputPlaceholder: '描述你的目标、问题或需要协作的任务...',
      quickPrompts: ['帮我把这个目标拆成执行计划', '帮我梳理一个复杂问题', '给我一个高效的工作方案']
    }
  }
}

export const AGENTS = Object.freeze(registry)
export const AGENT_LIST = Object.freeze(Object.values(registry))
export const KNOWLEDGE_AGENTS = Object.freeze(AGENT_LIST.filter(agent => agent.capabilities.privateKnowledge))
export const getAgent = key => AGENTS[key] || AGENTS.super
