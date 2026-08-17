const registry = {
  love: {
    key: 'love',
    name: '情感分析大师',
    category: '情感关系',
    path: '/love-master',
    icon: '♡',
    iconClass: 'emotion-icon',
    description: '基于对话、图片与知识库，梳理关系信号并给出建议',
    capabilities: { attachments: true, conversations: true, executionTrace: false, privateKnowledge: true },
    chat: {
      emptyTitle: '想从哪段关系开始分析？',
      emptyDescription: '可以描述经历、粘贴聊天记录，或上传截图。',
      inputPlaceholder: '描述你的困扰，或上传聊天截图...',
      quickPrompts: ['帮我分析这段关系是否健康', '聊天总是冷场，该怎么改善？', '他/她的这句话是什么意思？']
    }
  },
  travel: {
    key: 'travel',
    name: '旅游规划专家',
    category: '旅行规划',
    path: '/travel-planner',
    icon: '⌖',
    iconClass: 'travel-icon',
    description: '基于偏好、私有资料与联网搜索，生成可执行的旅行方案',
    capabilities: { attachments: false, conversations: true, executionTrace: true, privateKnowledge: true },
    chat: {
      emptyTitle: '从一段旅行开始规划。',
      emptyDescription: '告诉我出发地、目的地、日期、预算和偏好，我会结合你的资料与联网信息规划行程。',
      inputPlaceholder: '例如：上海出发，国庆去云南 5 天游，预算 6000 元...',
      quickPrompts: ['帮我规划一个周末短途旅行', '按预算做一份三日行程', '帮我比较两个旅行目的地']
    }
  },
  super: {
    key: 'super',
    name: '超级智能体',
    category: '效率协作',
    path: '/super-agent',
    icon: '✦',
    iconClass: 'super-icon',
    description: '面向多步骤任务的通用 AI 协作助手',
    capabilities: { attachments: false, conversations: false, executionTrace: false, privateKnowledge: false },
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
export const getAgent = key => AGENTS[key] || AGENTS.love
