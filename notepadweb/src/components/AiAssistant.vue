<template>
  <Teleport to="body">
    <Transition name="ai-mask">
      <div v-if="open" class="ai-panel-mask" @click="closePanel" />
    </Transition>

    <Transition name="ai-panel">
      <aside v-if="open" class="ai-assistant" role="dialog" aria-label="AI 笔记助手">
        <header class="assistant-header">
          <div class="assistant-title">
            <span class="assistant-mark"><el-icon><MagicStick /></el-icon></span>
            <div>
              <strong>AI 笔记助手</strong>
              <span>答案只来自你的笔记</span>
            </div>
          </div>
          <div class="header-actions">
            <el-tooltip content="新对话" placement="bottom">
              <el-button
                :icon="Plus"
                text
                circle
                aria-label="新建 AI 对话"
                :disabled="isGenerating"
                @click="startNewConversation"
              />
            </el-tooltip>
            <el-tooltip content="历史会话" placement="bottom">
              <el-button
                :icon="Clock"
                text
                circle
                aria-label="查看历史会话"
                :disabled="isGenerating"
                @click="historyDialogVisible = true"
              />
            </el-tooltip>
            <el-tooltip content="索引状态" placement="bottom">
              <el-button
                :icon="DataAnalysis"
                text
                circle
                aria-label="查看 AI 索引状态"
                @click="indexDialogVisible = true"
              />
            </el-tooltip>
            <el-tooltip content="反馈统计" placement="bottom">
              <el-button
                :icon="PieChart"
                text
                circle
                aria-label="查看 AI 回答反馈统计"
                :disabled="isGenerating"
                @click="openFeedbackStatistics"
              />
            </el-tooltip>
            <el-tooltip content="RAG 回归测试" placement="bottom">
              <el-button
                :icon="TrendCharts"
                text
                circle
                aria-label="打开 RAG 回归测试"
                :disabled="isGenerating"
                @click="openEvaluation"
              />
            </el-tooltip>
            <el-tooltip content="删除当前对话" placement="bottom">
              <el-button
                :icon="Delete"
                text
                circle
                aria-label="删除当前 AI 对话"
                :disabled="messages.length === 0 || isGenerating"
                @click="clearConversation"
              />
            </el-tooltip>
            <el-button :icon="Close" text circle aria-label="关闭 AI 助手" @click="closePanel" />
          </div>
        </header>

        <main ref="messageListRef" class="message-list" aria-live="polite">
          <section v-if="messages.length === 0" class="welcome-state">
            <span class="welcome-icon"><el-icon><MagicStick /></el-icon></span>
            <h2>从笔记里找答案</h2>
            <p>我会先检索与你的问题相关的笔记，再依据命中片段回答，并标注信息来源。</p>
            <div class="suggestion-list">
              <button
                v-for="suggestion in suggestions"
                :key="suggestion"
                type="button"
                @click="submit(suggestion)"
              >
                {{ suggestion }}
              </button>
            </div>
          </section>

          <template v-for="message in messages" :key="message.id">
            <article v-if="message.role === 'user'" class="message-row message-row--user">
              <div class="user-bubble">{{ message.content }}</div>
            </article>

            <article v-else class="message-row message-row--assistant">
              <div class="assistant-avatar"><el-icon><MagicStick /></el-icon></div>
              <div class="assistant-message">
                <div
                  v-if="message.content"
                  class="answer-content"
                  :class="{ 'answer-content--streaming': message.status === 'streaming' }"
                  v-html="renderAnswer(message.content)"
                  @click="handleAnswerClick($event, message)"
                />
                <div v-else-if="message.status === 'streaming'" class="thinking-state">
                  <span />
                  <span />
                  <span />
                  <em>{{ message.progressText }}</em>
                </div>
                <p v-else-if="message.status === 'stopped'" class="message-hint">已停止生成</p>

                <div v-if="message.status === 'error'" class="message-failure">
                  <span>{{ message.error }}</span>
                  <button type="button" @click="retryMessage(message)">重新生成</button>
                </div>

                <section v-if="message.sources.length > 0" class="source-section">
                  <div class="source-heading">
                    <strong>引用笔记</strong>
                    <span>{{ message.sources.length }} 个片段</span>
                  </div>
                  <button
                    v-for="(source, index) in message.sources"
                    :key="`${message.id}-${source.noteId}-${index}`"
                    type="button"
                    class="source-card"
                    @click="openSource(source)"
                  >
                    <span class="source-index">{{ index + 1 }}</span>
                    <span class="source-body">
                      <span class="source-meta">
                        <strong>{{ source.title || '无标题笔记' }}</strong>
                        <em>{{ formatScore(source.score) }}</em>
                      </span>
                      <span class="source-content">{{ source.content }}</span>
                    </span>
                    <el-icon class="source-arrow"><ArrowRight /></el-icon>
                  </button>
                </section>

                <div v-if="message.status === 'done'" class="answer-feedback">
                  <span>这个回答有帮助吗？</span>
                  <button
                    type="button"
                    :class="{ active: message.feedback?.rating === 'helpful' }"
                    :disabled="feedbackSubmittingMessageId === message.clientMessageId"
                    :aria-pressed="message.feedback?.rating === 'helpful'"
                    @click="submitHelpfulFeedback(message)"
                  >有帮助</button>
                  <button
                    type="button"
                    :class="{ active: message.feedback?.rating === 'unhelpful' }"
                    :disabled="feedbackSubmittingMessageId === message.clientMessageId"
                    :aria-pressed="message.feedback?.rating === 'unhelpful'"
                    @click="openUnhelpfulFeedback(message)"
                  >{{ message.feedback?.rating === 'unhelpful' ? '修改原因' : '没帮助' }}</button>
                  <button
                    v-if="message.feedback?.rating === 'unhelpful'"
                    type="button"
                    class="feedback-eval-action"
                    :disabled="feedbackSubmittingMessageId === message.clientMessageId"
                    @click="openFeedbackEvalCase(message)"
                  >{{ message.feedback.evalCaseId ? '查看测试用例' : '转为测试用例' }}</button>
                  <em v-if="message.feedback">已记录</em>
                </div>

                <p v-if="message.status === 'stopped' && message.content" class="message-hint">回答已停止</p>
              </div>
            </article>
          </template>
        </main>

        <footer class="composer">
          <form class="composer-box" @submit.prevent="submit()">
            <label class="scope-control">
              <span>检索范围</span>
              <select v-model="searchScope" aria-label="选择 AI 检索范围">
                <option value="all">全部笔记</option>
                <option value="notebook" :disabled="!props.currentNotebookId">当前笔记本</option>
                <option value="current" :disabled="!props.currentNoteId">当前笔记</option>
              </select>
            </label>
            <el-input
              v-model="question"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 5 }"
              resize="none"
              maxlength="500"
              placeholder="问问你的笔记……"
              aria-label="向 AI 笔记助手提问"
              @keydown="handleQuestionKeydown"
            />
            <div class="composer-actions">
              <span>Enter 发送 · Shift + Enter 换行</span>
              <el-button
                v-if="isGenerating"
                class="stop-button"
                :icon="VideoPause"
                circle
                aria-label="停止生成"
                @click="stopGeneration"
              />
              <el-button
                v-else
                class="send-button"
                type="primary"
                :icon="Promotion"
                circle
                aria-label="发送问题"
                :disabled="!question.trim()"
                native-type="submit"
              />
            </div>
          </form>
          <p class="assistant-disclaimer">对话自动同步到账户 · 请结合引用原文核对。</p>
        </footer>
      </aside>
    </Transition>

    <el-dialog
      v-model="historyDialogVisible"
      title="历史会话"
      width="min(440px, calc(100vw - 28px))"
      append-to-body
    >
      <div v-if="savedConversations.length > 0" class="conversation-list">
        <div
          v-for="conversation in savedConversations"
          :key="conversation.id"
          :class="['conversation-item', { 'conversation-item--active': conversation.id === activeConversationId }]"
          role="button"
          tabindex="0"
          @click="openConversation(conversation.id)"
          @keydown.enter="openConversation(conversation.id)"
        >
          <div class="conversation-item__body">
            <strong>{{ conversation.title }}</strong>
            <span>{{ conversation.messages.length / 2 }} 轮 · {{ formatConversationTime(conversation.updatedAt) }}</span>
          </div>
          <el-button
            :icon="Delete"
            text
            circle
            aria-label="删除这条历史会话"
            @click.stop="deleteConversation(conversation.id)"
          />
        </div>
      </div>
      <div v-else class="conversation-empty">
        <el-icon><Clock /></el-icon>
        <strong>暂无历史会话</strong>
        <span>完成一次问答后，会话会自动保存在这里。</span>
      </div>
      <template #footer>
        <el-button type="primary" plain @click="startNewConversation">新建对话</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="indexDialogVisible"
      title="AI 索引状态"
      width="min(480px, calc(100vw - 28px))"
      append-to-body
      destroy-on-close
      @open="openIndexDialog"
    >
      <div v-loading="indexStatusLoading" class="index-manager">
        <template v-if="indexStatus">
          <div :class="['index-health', `index-health--${indexStatus.state.toLowerCase()}`]">
            <span class="index-health-dot" />
            <div>
              <strong>{{ indexStateLabel(indexStatus.state) }}</strong>
              <span>{{ indexStateDescription(indexStatus.state) }}</span>
            </div>
          </div>

          <div class="index-stats">
            <div><strong>{{ indexStatus.noteCount }}</strong><span>正常笔记</span></div>
            <div><strong>{{ indexStatus.indexedNoteCount }}</strong><span>已索引笔记</span></div>
            <div><strong>{{ indexStatus.chunkCount }}</strong><span>向量片段</span></div>
          </div>

          <dl class="index-details">
            <div><dt>缺失笔记</dt><dd>{{ indexStatus.missingNoteCount }}</dd></div>
            <div><dt>残留片段</dt><dd>{{ indexStatus.staleChunkCount }}</dd></div>
            <div><dt>向量存储</dt><dd>{{ indexStoreLabel(indexStatus) }}</dd></div>
            <div><dt>最后更新</dt><dd>{{ formatIndexTime(indexStatus.lastUpdatedAt) }}</dd></div>
          </dl>

          <section class="index-backups">
            <div class="index-backups__header">
              <div>
                <strong>索引备份</strong>
                <span>保留最近 {{ indexBackups.length }} 份，可恢复到任意一份</span>
              </div>
              <el-button
                size="small"
                :loading="indexBackupCreating"
                :disabled="indexRestoringFileName !== null"
                @click="handleCreateIndexBackup"
              >
                创建备份
              </el-button>
            </div>

            <div v-if="indexBackups.length" v-loading="indexBackupsLoading" class="index-backup-list">
              <article v-for="item in indexBackups" :key="item.fileName" class="index-backup-item">
                <div class="index-backup-item__body">
                  <div class="index-backup-item__title">
                    <strong>{{ formatIndexTime(item.createdAt) }}</strong>
                    <el-tag
                      size="small"
                      effect="plain"
                      :type="item.reason === 'pre-restore' ? 'info' : 'success'"
                    >
                      {{ indexBackupReasonLabel(item.reason) }}
                    </el-tag>
                  </div>
                  <span>{{ formatFileSize(item.fileBytes) }} · {{ item.fileName }}</span>
                </div>
                <el-button
                  text
                  type="primary"
                  :loading="indexRestoringFileName === item.fileName"
                  :disabled="indexBackupCreating
                    || (indexRestoringFileName !== null && indexRestoringFileName !== item.fileName)"
                  @click="handleRestoreIndexBackup(item)"
                >
                  恢复
                </el-button>
              </article>
            </div>
            <div v-else-if="!indexBackupsLoading" class="index-backups__empty">
              还没有备份，点击「创建备份」生成一份。
            </div>
          </section>

          <el-alert
            v-if="indexStatus.state === 'NEEDS_REBUILD' || indexStatus.state === 'ERROR'"
            title="索引与笔记数据不一致，建议执行重建。"
            type="warning"
            :closable="false"
            show-icon
          />
        </template>

        <div class="index-actions">
          <el-button :loading="indexStatusLoading" @click="loadIndexStatus">刷新状态</el-button>
          <el-button type="primary" plain :loading="indexRebuilding" @click="handleRebuildIndex">
            一键重建
          </el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog
      v-model="feedbackStatisticsDialogVisible"
      title="AI 回答反馈统计"
      width="min(760px, calc(100vw - 28px))"
      append-to-body
      destroy-on-close
    >
      <div v-loading="feedbackStatisticsLoading" class="feedback-statistics">
        <template v-if="feedbackStatistics && feedbackStatistics.totalCount > 0">
          <div class="feedback-statistics__metrics">
            <div><strong>{{ feedbackStatistics.totalCount }}</strong><span>反馈总数</span></div>
            <div>
              <strong>{{ feedbackStatistics.helpfulCount }}</strong>
              <span>有帮助 · {{ formatFeedbackRate(feedbackStatistics.helpfulRate) }}</span>
            </div>
            <div><strong>{{ feedbackStatistics.unhelpfulCount }}</strong><span>没帮助</span></div>
            <div>
              <strong>{{ feedbackStatistics.convertedCaseCount }}</strong>
              <span>已转用例 · {{ formatFeedbackRate(feedbackStatistics.conversionRate) }}</span>
            </div>
          </div>

          <div class="feedback-statistics__content">
            <section>
              <h3>没帮助原因</h3>
              <div class="feedback-reason-list">
                <div v-for="item in feedbackStatistics.reasonCounts" :key="item.reason">
                  <div>
                    <span>{{ feedbackReasonLabels[item.reason] }}</span>
                    <em>{{ item.count }} 次 · {{ formatFeedbackRate(item.rate) }}</em>
                  </div>
                  <el-progress
                    :percentage="feedbackRatePercentage(item.rate)"
                    :show-text="false"
                    :stroke-width="6"
                    color="#4d9b85"
                  />
                </div>
              </div>
            </section>

            <section>
              <h3>常见没帮助问题</h3>
              <div v-if="feedbackStatistics.commonFailureQuestions.length" class="feedback-question-list">
                <article
                  v-for="item in feedbackStatistics.commonFailureQuestions"
                  :key="item.question"
                >
                  <div>
                    <strong>{{ item.question }}</strong>
                    <em>{{ item.count }} 次</em>
                  </div>
                  <span>
                    最近反馈 {{ formatFeedbackStatisticsTime(item.lastFeedbackAt) }}
                    · 已转用例 {{ item.convertedCaseCount }} 条
                  </span>
                  <p>
                    <el-tag
                      v-if="item.convertedCaseCount > 0"
                      size="small"
                      :type="feedbackEvalStatusType(item.evalStatus)"
                      effect="plain"
                    >{{ feedbackEvalStatusLabel(item.evalStatus) }}</el-tag>
                    <el-tag
                      v-for="reason in item.reasonCounts"
                      :key="reason.reason"
                      size="small"
                      effect="plain"
                    >{{ feedbackReasonLabels[reason.reason] }} {{ reason.count }}</el-tag>
                  </p>
                  <span
                    v-if="item.evalStatus === 'failed' && item.evalFailureReason"
                    class="feedback-eval-reason"
                  >最近一次回归失败原因：{{ item.evalFailureReason }}</span>
                </article>
              </div>
              <div v-else class="feedback-statistics__empty">暂无没帮助反馈</div>
            </section>
          </div>
        </template>

        <div v-else-if="!feedbackStatisticsLoading" class="feedback-statistics__empty feedback-statistics__empty--large">
          暂无回答反馈，完成问答后可在每条回答下方进行评价。
        </div>
      </div>
      <template #footer>
        <el-button :loading="feedbackStatisticsLoading" @click="loadFeedbackStatistics">刷新统计</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="feedbackDialogVisible"
      title="哪里没有帮助？"
      width="min(420px, calc(100vw - 28px))"
      append-to-body
      destroy-on-close
    >
      <div class="feedback-form">
        <label>
          <span>主要原因</span>
          <el-select v-model="feedbackForm.reason" placeholder="请选择一个原因">
            <el-option label="引用片段不相关" value="irrelevant_sources" />
            <el-option label="回答不完整" value="incomplete" />
            <el-option label="回答与笔记不一致" value="inconsistent" />
            <el-option label="检索范围错误" value="wrong_scope" />
            <el-option label="其他" value="other" />
          </el-select>
        </label>
        <label>
          <span>补充说明（选填）</span>
          <el-input
            v-model="feedbackForm.comment"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="告诉我们具体哪里需要改进"
          />
        </label>
      </div>
      <template #footer>
        <el-button @click="feedbackDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="feedbackSubmittingMessageId === feedbackTarget?.clientMessageId"
          :disabled="!feedbackForm.reason"
          @click="submitUnhelpfulFeedback"
        >提交反馈</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="evaluationDialogVisible"
      title="RAG 检索回归测试"
      width="min(920px, calc(100vw - 28px))"
      append-to-body
      destroy-on-close
    >
      <div v-loading="evaluationLoading" class="evaluation-manager">
        <div class="evaluation-toolbar">
          <p>使用固定问题检查预期笔记是否被命中；运行过程只调用 Embedding 检索。</p>
          <div>
            <el-button @click="openEvalCaseEditor()">新增用例</el-button>
            <el-button
              type="primary"
              :loading="evaluationRunning"
              :disabled="evaluationCases.filter(item => item.enabled).length === 0"
              @click="runEvaluation"
            >运行全部启用用例</el-button>
          </div>
        </div>

        <el-tabs v-model="evaluationTab">
          <el-tab-pane label="测试用例" name="cases">
            <div v-if="evaluationCases.length" class="eval-case-list">
              <article v-for="testCase in evaluationCases" :key="testCase.id" class="eval-case-card">
                <div class="eval-case-main">
                  <div class="eval-case-title">
                    <strong>{{ testCase.name }}</strong>
                    <el-tag :type="testCase.expectAnswer ? 'success' : 'info'" size="small">
                      {{ testCase.expectAnswer ? '应命中' : '应拒答' }}
                    </el-tag>
                    <el-tag size="small" effect="plain">{{ evalScopeLabel(testCase) }}</el-tag>
                  </div>
                  <p>{{ testCase.question }}</p>
                  <span>
                    最低相关度 {{ formatPercent(testCase.minScore) }}
                      <template v-if="testCase.expectAnswer">
                        · 预期：{{ expectedNoteLabel(testCase.expectedNoteIds) }}
                        · {{ testCase.matchMode === 'all' ? '全部命中' : '任意命中' }}
                      </template>
                  </span>
                </div>
                <div class="eval-case-actions">
                  <el-switch
                    :model-value="testCase.enabled"
                    inline-prompt
                    active-text="启"
                    inactive-text="停"
                    @change="toggleEvalCase(testCase, Boolean($event))"
                  />
                  <el-button text @click="openEvalCaseEditor(testCase)">编辑</el-button>
                  <el-button text type="danger" @click="removeEvalCase(testCase)">删除</el-button>
                </div>
              </article>
            </div>
            <div v-else class="evaluation-empty">
              <strong>还没有测试用例</strong>
              <span>先添加一个“应命中”问题和一个“应拒答”问题。</span>
            </div>
          </el-tab-pane>

          <el-tab-pane label="运行报告" name="report">
            <div v-if="evaluationRuns.length" class="eval-run-selector">
              <span>历史运行</span>
              <el-select
                v-model="selectedEvaluationRunId"
                placeholder="选择报告"
                @change="loadEvaluationRun(Number($event))"
              >
                <el-option
                  v-for="run in evaluationRuns"
                  :key="run.id"
                  :label="`${formatEvalTime(run.createdAt)} · ${formatPercent(run.passRate)}`"
                  :value="run.id"
                />
              </el-select>
            </div>

            <template v-if="currentEvaluationRun">
              <div class="eval-metrics">
                <div><strong>{{ formatPercent(currentEvaluationRun.passRate) }}</strong><span>整体通过率</span></div>
                <div><strong>{{ formatPercent(currentEvaluationRun.hitRate) }}</strong><span>Top-K 命中率</span></div>
                <div><strong>{{ formatPercent(currentEvaluationRun.rejectionAccuracy) }}</strong><span>正确拒答率</span></div>
                <div><strong>{{ currentEvaluationRun.wrongReferenceCount }}</strong><span>错误引用</span></div>
                <div><strong>{{ currentEvaluationRun.averageDurationMs }} ms</strong><span>平均检索耗时</span></div>
              </div>
              <div class="eval-result-list">
                <article
                  v-for="result in currentEvaluationRun.results"
                  :key="result.caseId"
                  :class="['eval-result-card', { 'eval-result-card--failed': !result.passed }]"
                >
                  <div>
                    <strong>{{ result.passed ? '通过' : '失败' }} · {{ result.name }}</strong>
                    <span>{{ result.durationMs }} ms · 最高相关度 {{ result.topScore == null ? '无' : formatPercent(result.topScore) }}</span>
                  </div>
                  <p>{{ result.failureReason || '检索结果符合预期' }}</p>
                  <span>命中笔记：{{ result.matchedNoteIds.length ? expectedNoteLabel(result.matchedNoteIds) : '无' }}</span>
                  <span v-if="result.sourceFeedback" class="eval-result-source">
                    来自反馈：{{ feedbackReasonLabel(result.sourceFeedback.reason) }}<template
                      v-if="result.sourceFeedback.comment"
                    > · {{ result.sourceFeedback.comment }}</template>
                  </span>
                  <div v-if="!result.passed && result.retrievalTraces?.length" class="eval-retrieval-traces">
                    <article v-for="(trace, traceIndex) in result.retrievalTraces" :key="`${result.caseId}-${traceIndex}`">
                      <strong>子查询 {{ traceIndex + 1 }}：{{ trace.query }}</strong>
                      <span v-if="trace.candidates.length">
                        <template v-for="candidate in trace.candidates" :key="candidate.noteId">
                          <em :class="{ selected: candidate.selected }">
                            {{ noteTitle(candidate.noteId) }}
                            {{ candidate.score == null ? '无分数' : formatPercent(candidate.score) }}
                            {{ candidate.selected ? '已选' : '未选' }}
                          </em>
                        </template>
                      </span>
                      <span v-else>没有候选笔记</span>
                    </article>
                  </div>
                </article>
              </div>
            </template>
            <div v-else class="evaluation-empty">
              <strong>暂无运行报告</strong>
              <span>运行启用用例后，这里会显示基线指标和失败详情。</span>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>

    <el-dialog
      v-model="evalCaseDialogVisible"
      :title="editingEvalCaseId ? '编辑测试用例' : '新增测试用例'"
      width="min(520px, calc(100vw - 28px))"
      append-to-body
      destroy-on-close
      @closed="resetEvalCaseEditor"
    >
      <el-alert
        v-if="evalFeedbackSource"
        title="来自“没帮助”反馈"
        description="问题与检索范围已自动带入。请人工确认“应命中/应拒答”和预期笔记，原回答引用不会自动视为正确答案。"
        type="warning"
        :closable="false"
        show-icon
      />
      <div v-if="evalFeedbackSuggestedSources.length" class="feedback-eval-suggestions">
        <span>原回答引用候选</span>
        <div>
          <el-button
            v-for="source in evalFeedbackSuggestedSources"
            :key="source.noteId"
            size="small"
            plain
            :disabled="!evalCaseForm.expectAnswer || evalCaseForm.expectedNoteIds.includes(source.noteId)"
            @click="addFeedbackSuggestedNote(source.noteId)"
          >{{ source.title || '无标题' }} (#{{ source.noteId }}) · {{ formatScore(source.score) }}</el-button>
        </div>
      </div>
      <el-form label-position="top" class="eval-case-form">
        <el-form-item label="用例名称">
          <el-input v-model="evalCaseForm.name" maxlength="100" placeholder="例如：SQL 优化步骤" />
        </el-form-item>
        <el-form-item label="测试问题">
          <el-input
            v-model="evalCaseForm.question"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="输入一个以后可以重复执行的问题"
          />
        </el-form-item>
        <div class="eval-form-grid">
          <el-form-item label="检索范围">
            <el-select v-model="evalCaseForm.scopeType" @change="handleEvalScopeChange">
              <el-option label="全部笔记" value="all" />
              <el-option label="指定笔记本" value="notebook" />
              <el-option label="指定笔记" value="note" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="evalCaseForm.scopeType === 'notebook'" label="指定笔记本">
            <el-select v-model="evalCaseForm.scopeId" filterable placeholder="选择笔记本" @change="handleEvalScopeIdChange">
              <el-option v-for="notebook in evaluationNotebooks" :key="notebook.id" :label="notebook.name" :value="notebook.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="evalCaseForm.scopeType === 'note'" label="指定笔记">
            <el-select v-model="evalCaseForm.scopeId" filterable placeholder="选择笔记" @change="handleEvalScopeIdChange">
              <el-option v-for="note in evaluationNotes" :key="note.id" :label="`${note.title || '无标题'} (#${note.id})`" :value="note.id" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="预期结果">
          <el-radio-group v-model="evalCaseForm.expectAnswer" @change="handleEvalExpectationChange">
            <el-radio :value="true">应命中笔记</el-radio>
            <el-radio :value="false">应拒答</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="evalCaseForm.expectAnswer" label="允许命中的预期笔记">
          <el-select
            v-model="evalCaseForm.expectedNoteIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="至少选择一篇笔记"
          >
            <el-option
              v-for="note in availableExpectedNotes"
              :key="note.id"
              :label="`${note.title || '无标题'} (#${note.id})`"
              :value="note.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="evalCaseForm.expectAnswer" label="预期笔记命中要求">
          <el-radio-group v-model="evalCaseForm.matchMode">
            <el-radio value="any">命中任意一篇</el-radio>
            <el-radio value="all">命中全部笔记</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="eval-form-grid">
          <el-form-item label="最低相关度">
            <el-input-number v-model="evalCaseForm.minScore" :min="0.3" :max="1" :step="0.01" :precision="2" />
          </el-form-item>
          <el-form-item label="参与批量运行">
            <el-switch v-model="evalCaseForm.enabled" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="evalCaseDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="evalCaseSaving" @click="saveEvalCase">保存用例</el-button>
      </template>
    </el-dialog>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, Clock, Close, DataAnalysis, Delete, MagicStick, PieChart, Plus, Promotion, TrendCharts, VideoPause } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import {
  AiStreamHttpError,
  createNoteIndexBackup,
  createRagEvalCase,
  createRagEvalCaseFromFeedback,
  deleteAiConversation,
  deleteRagEvalCase,
  getAiFeedbackStatistics,
  getAiConversation,
  getAiConversations,
  getNoteIndexBackups,
  getNoteIndexStatus,
  getRagEvalCases,
  getRagEvalRun,
  getRagEvalRuns,
  rebuildNoteIndex,
  restoreNoteIndexBackup,
  runRagEvaluation,
  saveAnswerFeedback,
  saveAiConversation,
  streamNoteAnswer,
  updateRagEvalCase,
  verifyNoteIndexBackup
} from '../api/ai'
import type {
  AiAnswerFeedback,
  AiConversationDetail,
  AiFeedbackStatistics,
  AiFeedbackFailureQuestion,
  AnswerScopeType,
  ConversationMessage,
  FeedbackReason,
  NoteIndexBackupItem,
  NoteIndexRestoreResult,
  NoteIndexStatus,
  RagEvalCase,
  RagEvalCasePayload,
  RagEvalRun,
  RagEvalRunSummary,
  RagSource,
  StoredConversationMessage
} from '../api/ai'
import { useUserStore } from '../stores/user'
import { getNoteList } from '../api/note'
import type { NoteListItem } from '../api/note'
import { getNotebookList } from '../api/notebook'
import type { NotebookVO } from '../api/notebook'

interface AssistantMessage {
  id: number
  clientMessageId: string
  role: 'assistant'
  content: string
  sources: RagSource[]
  status: 'streaming' | 'done' | 'stopped' | 'error'
  question: string
  progressText: string
  scope: AnswerScopeSnapshot
  feedback: AiAnswerFeedback | null
  error?: string
}

interface EvalFeedbackSource {
  conversationClientId: string
  messageClientId: string
  message: AssistantMessage
}

interface UserMessage {
  id: number
  clientMessageId: string
  role: 'user'
  content: string
}

type ChatMessage = AssistantMessage | UserMessage

interface AnswerScopeSnapshot {
  type: AnswerScopeType
  id: number | null
}

interface LegacyPersistedConversation {
  version: 1
  updatedAt: string
  messages: ChatMessage[]
}

interface SavedConversation {
  id: string
  title: string
  updatedAt: string
  messages: ChatMessage[]
}

interface PersistedConversationStore {
  version: 2
  activeConversationId: string | null
  conversations: SavedConversation[]
}

const props = defineProps<{
  open: boolean
  currentNoteId?: number | null
  currentNotebookId?: number | null
}>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  'open-note': [source: RagSource]
}>()

const router = useRouter()
const userStore = useUserStore()
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true })
const suggestions = [
  '帮我总结最近笔记中的主要知识点',
  'SQL 优化可以按照什么步骤排查？',
  '我记录过哪些保证消息可靠性的方案？'
]
const feedbackReasonLabels: Record<FeedbackReason, string> = {
  irrelevant_sources: '引用不相关',
  incomplete: '回答不完整',
  inconsistent: '与笔记不一致',
  wrong_scope: '检索范围错误',
  other: '其他问题'
}
const question = ref('')
const searchScope = ref<'all' | 'notebook' | 'current'>('all')
const messages = ref<ChatMessage[]>([])
const isGenerating = ref(false)
const historyDialogVisible = ref(false)
const savedConversations = ref<SavedConversation[]>([])
const activeConversationId = ref<string | null>(null)
const indexDialogVisible = ref(false)
const indexStatusLoading = ref(false)
const indexRebuilding = ref(false)
const indexStatus = ref<NoteIndexStatus | null>(null)
const indexBackups = ref<NoteIndexBackupItem[]>([])
const indexBackupsLoading = ref(false)
const indexBackupCreating = ref(false)
const indexRestoringFileName = ref<string | null>(null)
const feedbackDialogVisible = ref(false)
const feedbackTarget = ref<AssistantMessage | null>(null)
const feedbackSubmittingMessageId = ref<string | null>(null)
const feedbackForm = reactive<{ reason: FeedbackReason | ''; comment: string }>({
  reason: '',
  comment: ''
})
const feedbackStatisticsDialogVisible = ref(false)
const feedbackStatisticsLoading = ref(false)
const feedbackStatistics = ref<AiFeedbackStatistics | null>(null)
const evaluationDialogVisible = ref(false)
const evaluationLoading = ref(false)
const evaluationRunning = ref(false)
const evaluationTab = ref<'cases' | 'report'>('cases')
const evaluationCases = ref<RagEvalCase[]>([])
const evaluationRuns = ref<RagEvalRunSummary[]>([])
const currentEvaluationRun = ref<RagEvalRun | null>(null)
const selectedEvaluationRunId = ref<number | null>(null)
const evaluationNotes = ref<NoteListItem[]>([])
const evaluationNotebooks = ref<NotebookVO[]>([])
const evalCaseDialogVisible = ref(false)
const evalCaseSaving = ref(false)
const editingEvalCaseId = ref<number | null>(null)
const evalFeedbackSource = ref<EvalFeedbackSource | null>(null)
const evalCaseForm = reactive<RagEvalCasePayload>({
  name: '',
  question: '',
  scopeType: 'all',
  scopeId: null,
  expectAnswer: true,
  expectedNoteIds: [],
  matchMode: 'any',
  minScore: 0.45,
  enabled: true
})
const availableExpectedNotes = computed(() => {
  if (evalCaseForm.scopeType === 'note') {
    return evaluationNotes.value.filter(note => note.id === evalCaseForm.scopeId)
  }
  if (evalCaseForm.scopeType === 'notebook') {
    return evaluationNotes.value.filter(note => note.notebookId === evalCaseForm.scopeId)
  }
  return evaluationNotes.value
})
const evalFeedbackSuggestedSources = computed(() => {
  const source = evalFeedbackSource.value
  if (!source) return []
  const allowedIds = new Set(availableExpectedNotes.value.map(note => note.id))
  const seen = new Set<number>()
  return source.message.sources.filter(item => {
    if (!allowedIds.has(item.noteId) || seen.has(item.noteId)) return false
    seen.add(item.noteId)
    return true
  })
})
const messageListRef = ref<HTMLElement>()
let messageSequence = 0
let activeController: AbortController | null = null
/** 当前正在逐字渲染的渲染器。组件卸载时要把它一起停掉，否则定时器会继续跑到把缓冲吐完 */
let activeRenderer: { flush: () => void } | null = null
let conversationStorageKey: string | null = null
let legacyConversationStorageKey: string | null = null
let conversationSyncMarkerKey: string | null = null
let conversationPendingKey: string | null = null
let conversationRevision = 0
let conversationSyncEpoch = 0
const serverConversationIds = new Set<string>()
const CONVERSATION_STORAGE_PREFIX = 'notepad:ai-conversations:v2:'
const LEGACY_CONVERSATION_STORAGE_PREFIX = 'notepad:ai-conversation:v1:'
const CONVERSATION_SYNC_MARKER_PREFIX = 'notepad:ai-conversations:server-synced:v1:'
const CONVERSATION_PENDING_PREFIX = 'notepad:ai-conversations:pending:v1:'
const MAX_PERSISTED_MESSAGES = 20
const MAX_SAVED_CONVERSATIONS = 30

function renderAnswer(content: string) {
  return markdown.render(content).replace(
    /\[片段(\d+)\]/g,
    '<button type="button" class="citation-link" data-source-index="$1">[片段$1]</button>'
  )
}

function formatScore(score: number) {
  return `相关度 ${(score * 100).toFixed(1)}%`
}

function indexStateLabel(state: NoteIndexStatus['state']) {
  return {
    HEALTHY: '索引健康',
    EMPTY: '暂无索引',
    NEEDS_REBUILD: '需要重建',
    ERROR: '索引异常'
  }[state]
}

function indexStateDescription(state: NoteIndexStatus['state']) {
  return {
    HEALTHY: '全部正常笔记均已建立向量索引',
    EMPTY: '当前没有需要建立索引的笔记',
    NEEDS_REBUILD: '部分笔记缺失索引或存在残留片段',
    ERROR: '索引文件暂时无法读取'
  }[state]
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatIndexTime(value: string | null) {
  if (!value) return '暂无记录'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

// pgvector 的持久化载体是数据库而不是本地文件，直接显示字节数会变成「0 B」。
function indexStoreLabel(status: NoteIndexStatus) {
  if (status.storeType === 'pgvector') {
    return status.indexType ? `向量库（PostgreSQL · ${status.indexType}）` : '向量库（PostgreSQL）'
  }
  return status.storeFileExists ? formatFileSize(status.storeFileBytes) : '不存在'
}

async function loadIndexStatus() {
  if (indexStatusLoading.value) return
  indexStatusLoading.value = true
  try {
    indexStatus.value = await getNoteIndexStatus()
  } catch {
    indexStatus.value = null
  } finally {
    indexStatusLoading.value = false
  }
}

async function handleRebuildIndex() {
  if (indexRebuilding.value) return
  try {
    await ElMessageBox.confirm(
      '重建会重新处理你的全部正常笔记，并消耗一定的 Embedding 调用额度。确定继续吗？',
      '重建 AI 索引',
      { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  indexRebuilding.value = true
  try {
    const result = await rebuildNoteIndex()
    ElMessage.success(`索引重建完成：${result.noteCount} 篇笔记，${result.chunkCount} 个片段`)
    await loadIndexStatus()
  } catch {
    // 请求拦截器已显示具体错误。
  } finally {
    indexRebuilding.value = false
  }
}

async function openIndexDialog() {
  await Promise.all([loadIndexStatus(), loadIndexBackups()])
}

async function loadIndexBackups() {
  if (indexBackupsLoading.value) return
  indexBackupsLoading.value = true
  try {
    indexBackups.value = await getNoteIndexBackups()
  } catch {
    indexBackups.value = []
    // 请求拦截器已显示具体错误。
  } finally {
    indexBackupsLoading.value = false
  }
}

function indexBackupReasonLabel(reason: NoteIndexBackupItem['reason']) {
  return reason === 'pre-restore' ? '恢复前留档' : '手动备份'
}

async function handleCreateIndexBackup() {
  if (indexBackupCreating.value || indexRestoringFileName.value) return
  try {
    await ElMessageBox.confirm(
      '将当前向量索引完整复制一份到备份目录，不影响正在使用的索引。确定继续吗？',
      '创建索引备份',
      { type: 'info', confirmButtonText: '创建备份', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  indexBackupCreating.value = true
  try {
    const backup = await createNoteIndexBackup()
    ElMessage.success(`备份已创建：${backup.fileName}（${formatFileSize(backup.fileBytes)}）`)
    await Promise.all([loadIndexBackups(), loadIndexStatus()])
  } catch {
    // 请求拦截器已显示具体错误。
  } finally {
    indexBackupCreating.value = false
  }
}

async function handleRestoreIndexBackup(item: NoteIndexBackupItem) {
  if (indexRestoringFileName.value || indexBackupCreating.value) return

  // 先做一次零副作用的校验：坏文件直接拒绝，不必进入二次确认，
  // 也避免让人误以为「点了就会恢复」。
  try {
    const verification = await verifyNoteIndexBackup(item.fileName)
    if (!verification.valid) {
      ElMessage.error(`该备份无法恢复：${verification.issueMessage ?? '文件内容已损坏'}`)
      return
    }
  } catch {
    // 请求拦截器已显示具体错误。
    return
  }

  try {
    await ElMessageBox.confirm(
      `将用 ${formatIndexTime(item.createdAt)} 的备份整体替换当前索引。`
        + '索引由全部用户共享，恢复会把所有人的索引一起回退到该时间点；'
        + '恢复前会自动留档当前索引。确定继续吗？',
      '从备份恢复索引',
      { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  indexRestoringFileName.value = item.fileName
  try {
    const result: NoteIndexRestoreResult = await restoreNoteIndexBackup(item.fileName)
    ElMessage.success(
      `已恢复 ${result.restoredEntryCount} 个向量片段，当前状态：${indexStateLabel(result.status.state)}`
    )
  } catch {
    // 请求拦截器已显示具体错误；失败时索引要么未受影响、要么已回滚，下面统一刷新真实状态。
  } finally {
    indexRestoringFileName.value = null
    await Promise.all([loadIndexStatus(), loadIndexBackups()])
  }
}

async function openFeedbackStatistics() {
  feedbackStatisticsDialogVisible.value = true
  await loadFeedbackStatistics()
}

async function loadFeedbackStatistics() {
  if (feedbackStatisticsLoading.value) return
  feedbackStatisticsLoading.value = true
  try {
    feedbackStatistics.value = await getAiFeedbackStatistics()
  } catch {
    feedbackStatistics.value = null
  } finally {
    feedbackStatisticsLoading.value = false
  }
}

function feedbackRatePercentage(rate: number) {
  return Math.min(100, Math.max(0, Math.round(rate * 1000) / 10))
}

function formatFeedbackRate(rate: number) {
  return `${feedbackRatePercentage(rate).toFixed(1)}%`
}

function formatFeedbackStatisticsTime(value: string | null) {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

// 反馈与回归测试报告的双向追溯：来源反馈 → 回归状态，回归失败 → 原始反馈。
function feedbackReasonLabel(reason: string | null) {
  if (!reason) return '未分类'
  return feedbackReasonLabels[reason as FeedbackReason] ?? reason
}

function feedbackEvalStatusLabel(status: AiFeedbackFailureQuestion['evalStatus']) {
  if (status === 'passed') return '回归通过'
  if (status === 'failed') return '回归失败'
  return '尚未回归'
}

function feedbackEvalStatusType(
  status: AiFeedbackFailureQuestion['evalStatus']
): 'success' | 'danger' | 'info' {
  if (status === 'passed') return 'success'
  if (status === 'failed') return 'danger'
  return 'info'
}

async function openFeedbackEvalCase(message: AssistantMessage) {
  const feedback = message.feedback
  const conversationId = activeConversationId.value
  if (feedback?.rating !== 'unhelpful' || !conversationId) {
    ElMessage.error('当前反馈暂时无法转换，请刷新会话后重试')
    return
  }

  evaluationDialogVisible.value = true
  evaluationTab.value = 'cases'
  await loadEvaluationData()

  if (feedback.evalCaseId) {
    const existing = evaluationCases.value.find(item => item.id === feedback.evalCaseId)
    if (existing) {
      openEvalCaseEditor(existing)
      return
    }
    setFeedbackEvalCaseId(message, conversationId, null)
  }

  editingEvalCaseId.value = null
  evalFeedbackSource.value = {
    conversationClientId: conversationId,
    messageClientId: message.clientMessageId,
    message
  }
  Object.assign(evalCaseForm, {
    name: buildFeedbackEvalCaseName(message),
    question: message.question,
    scopeType: message.scope.type,
    scopeId: message.scope.type === 'all' ? null : message.scope.id,
    expectAnswer: true,
    expectedNoteIds: [],
    matchMode: 'any',
    minScore: 0.45,
    enabled: true
  })
  evalCaseDialogVisible.value = true
}

function buildFeedbackEvalCaseName(message: AssistantMessage) {
  const reason = message.feedback?.reason
  const reasonLabel = reason ? feedbackReasonLabels[reason] : '没帮助'
  const normalizedQuestion = message.question.trim().replace(/\s+/g, ' ')
  const questionSummary = normalizedQuestion.length > 42
    ? `${normalizedQuestion.slice(0, 42)}…`
    : normalizedQuestion
  return `反馈·${reasonLabel}·${questionSummary || '未命名问题'}`.slice(0, 100)
}

function addFeedbackSuggestedNote(noteId: number) {
  if (!evalCaseForm.expectAnswer || evalCaseForm.expectedNoteIds.includes(noteId)) return
  evalCaseForm.expectedNoteIds.push(noteId)
}

function setFeedbackEvalCaseId(
  message: AssistantMessage,
  conversationId: string,
  evalCaseId: number | null
) {
  if (message.feedback) message.feedback.evalCaseId = evalCaseId
  const conversation = savedConversations.value.find(item => item.id === conversationId)
  const storedMessage = conversation?.messages.find(
    item => item.role === 'assistant' && item.clientMessageId === message.clientMessageId
  )
  if (storedMessage?.role === 'assistant' && storedMessage.feedback) {
    storedMessage.feedback.evalCaseId = evalCaseId
  }
  saveConversationStore()
}

function clearFeedbackEvalCaseId(evalCaseId: number) {
  for (const message of messages.value) {
    if (message.role === 'assistant' && message.feedback?.evalCaseId === evalCaseId) {
      message.feedback.evalCaseId = null
    }
  }
  for (const conversation of savedConversations.value) {
    for (const message of conversation.messages) {
      if (message.role === 'assistant' && message.feedback?.evalCaseId === evalCaseId) {
        message.feedback.evalCaseId = null
      }
    }
  }
  saveConversationStore()
}

async function openEvaluation() {
  evaluationDialogVisible.value = true
  await loadEvaluationData()
}

async function loadEvaluationData() {
  if (evaluationLoading.value) return
  evaluationLoading.value = true
  try {
    const [cases, runs, notebooks, notes] = await Promise.all([
      getRagEvalCases(),
      getRagEvalRuns(),
      getNotebookList(),
      loadAllEvaluationNotes()
    ])
    evaluationCases.value = cases
    evaluationRuns.value = runs
    evaluationNotebooks.value = notebooks
    evaluationNotes.value = notes
    if (runs.length > 0) {
      await loadEvaluationRun(runs[0].id)
    } else {
      selectedEvaluationRunId.value = null
      currentEvaluationRun.value = null
    }
  } catch {
    // 请求层已提示具体错误。
  } finally {
    evaluationLoading.value = false
  }
}

async function loadAllEvaluationNotes() {
  const notes: NoteListItem[] = []
  let page = 1
  let total = 0
  do {
    const result = await getNoteList({ page, pageSize: 50 })
    notes.push(...result.records)
    total = result.total
    page += 1
  } while (notes.length < total && page <= 20)
  return notes
}

async function loadEvaluationRun(runId: number) {
  if (!runId) return
  try {
    currentEvaluationRun.value = await getRagEvalRun(runId)
    selectedEvaluationRunId.value = runId
  } catch {
    currentEvaluationRun.value = null
  }
}

async function runEvaluation() {
  if (evaluationRunning.value) return
  evaluationRunning.value = true
  try {
    const report = await runRagEvaluation()
    currentEvaluationRun.value = report
    selectedEvaluationRunId.value = report.id
    evaluationRuns.value = await getRagEvalRuns()
    evaluationTab.value = 'report'
    ElMessage.success(`回归测试完成：${report.passedCount}/${report.caseCount} 个用例通过`)
  } catch {
    // 请求层已提示具体错误。
  } finally {
    evaluationRunning.value = false
  }
}

function openEvalCaseEditor(testCase?: RagEvalCase) {
  evalFeedbackSource.value = null
  editingEvalCaseId.value = testCase?.id || null
  Object.assign(evalCaseForm, testCase
    ? {
        name: testCase.name,
        question: testCase.question,
        scopeType: testCase.scopeType,
        scopeId: testCase.scopeId,
        expectAnswer: testCase.expectAnswer,
        expectedNoteIds: [...testCase.expectedNoteIds],
        matchMode: testCase.matchMode,
        minScore: testCase.minScore,
        enabled: testCase.enabled
      }
    : {
        name: '',
        question: '',
        scopeType: 'all',
        scopeId: null,
        expectAnswer: true,
        expectedNoteIds: [],
        matchMode: 'any',
        minScore: 0.45,
        enabled: true
      })
  evalCaseDialogVisible.value = true
}

function resetEvalCaseEditor() {
  editingEvalCaseId.value = null
  evalFeedbackSource.value = null
}

function handleEvalScopeChange() {
  evalCaseForm.scopeId = null
  evalCaseForm.expectedNoteIds = []
}

function handleEvalScopeIdChange(value: number) {
  if (evalCaseForm.scopeType === 'note' && evalCaseForm.expectAnswer && value) {
    evalCaseForm.expectedNoteIds = [value]
    return
  }
  const availableIds = new Set(availableExpectedNotes.value.map(note => note.id))
  evalCaseForm.expectedNoteIds = evalCaseForm.expectedNoteIds.filter(id => availableIds.has(id))
}

function handleEvalExpectationChange(value: boolean) {
  if (!value) {
    evalCaseForm.expectedNoteIds = []
    evalCaseForm.matchMode = 'any'
  } else if (evalCaseForm.scopeType === 'note' && evalCaseForm.scopeId) {
    evalCaseForm.expectedNoteIds = [evalCaseForm.scopeId]
  }
}

async function saveEvalCase() {
  const name = evalCaseForm.name.trim()
  const questionValue = evalCaseForm.question.trim()
  if (!name || !questionValue) {
    ElMessage.warning('请填写用例名称和测试问题')
    return
  }
  if (evalCaseForm.scopeType !== 'all' && !evalCaseForm.scopeId) {
    ElMessage.warning('请选择具体的笔记本或笔记')
    return
  }
  if (evalCaseForm.expectAnswer && evalCaseForm.expectedNoteIds.length === 0) {
    ElMessage.warning('应命中用例至少需要选择一篇预期笔记')
    return
  }

  const payload: RagEvalCasePayload = {
    name,
    question: questionValue,
    scopeType: evalCaseForm.scopeType,
    scopeId: evalCaseForm.scopeType === 'all' ? null : evalCaseForm.scopeId,
    expectAnswer: evalCaseForm.expectAnswer,
    expectedNoteIds: evalCaseForm.expectAnswer ? [...evalCaseForm.expectedNoteIds] : [],
    matchMode: evalCaseForm.expectAnswer ? evalCaseForm.matchMode : 'any',
    minScore: evalCaseForm.minScore,
    enabled: evalCaseForm.enabled
  }
  evalCaseSaving.value = true
  try {
    const feedbackSource = evalFeedbackSource.value
    const saved = editingEvalCaseId.value
      ? await updateRagEvalCase(editingEvalCaseId.value, payload)
      : feedbackSource
        ? await createRagEvalCaseFromFeedback(
            feedbackSource.conversationClientId,
            feedbackSource.messageClientId,
            payload
          )
        : await createRagEvalCase(payload)
    const index = evaluationCases.value.findIndex(item => item.id === saved.id)
    if (index >= 0) evaluationCases.value[index] = saved
    else evaluationCases.value.unshift(saved)
    if (feedbackSource) {
      setFeedbackEvalCaseId(
        feedbackSource.message,
        feedbackSource.conversationClientId,
        saved.id
      )
    }
    feedbackStatistics.value = null
    evalCaseDialogVisible.value = false
    ElMessage.success(feedbackSource ? '反馈已转换为测试用例' : '测试用例已保存')
  } catch {
    // 请求层已提示具体错误。
  } finally {
    evalCaseSaving.value = false
  }
}

async function toggleEvalCase(testCase: RagEvalCase, enabled: boolean) {
  try {
    const updated = await updateRagEvalCase(testCase.id, {
      name: testCase.name,
      question: testCase.question,
      scopeType: testCase.scopeType,
      scopeId: testCase.scopeId,
      expectAnswer: testCase.expectAnswer,
      expectedNoteIds: testCase.expectedNoteIds,
      matchMode: testCase.matchMode,
      minScore: testCase.minScore,
      enabled
    })
    Object.assign(testCase, updated)
  } catch {
    // 保留原状态。
  }
}

async function removeEvalCase(testCase: RagEvalCase) {
  try {
    await ElMessageBox.confirm(
      `确定删除测试用例“${testCase.name}”吗？历史报告不会受影响。`,
      '删除测试用例',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteRagEvalCase(testCase.id)
    evaluationCases.value = evaluationCases.value.filter(item => item.id !== testCase.id)
    clearFeedbackEvalCaseId(testCase.id)
    feedbackStatistics.value = null
    ElMessage.success('测试用例已删除')
  } catch {
    // 请求层已提示具体错误。
  }
}

function evalScopeLabel(testCase: RagEvalCase) {
  if (testCase.scopeType === 'all') return '全部笔记'
  if (testCase.scopeType === 'note') {
    return `笔记：${noteTitle(testCase.scopeId)}`
  }
  const notebook = evaluationNotebooks.value.find(item => item.id === testCase.scopeId)
  return `笔记本：${notebook?.name || `#${testCase.scopeId}`}`
}

function noteTitle(noteId: number | null) {
  if (!noteId) return '未知'
  const note = evaluationNotes.value.find(item => item.id === noteId)
  return note ? `${note.title || '无标题'} (#${note.id})` : `#${noteId}`
}

function expectedNoteLabel(noteIds: number[]) {
  const labels = noteIds.slice(0, 3).map(noteTitle)
  return noteIds.length > 3 ? `${labels.join('、')} 等 ${noteIds.length} 篇` : labels.join('、')
}

function formatPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

function formatEvalTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '时间未知'
    : date.toLocaleString('zh-CN', { hour12: false })
}

function scrollToBottom() {
  void nextTick(() => {
    const list = messageListRef.value
    if (list) list.scrollTop = list.scrollHeight
  })
}

/**
 * SSE 的多个 delta 可能落在同一个网络包中。把已收到的真实增量分批交给 Vue，
 * 既保留实时性，也避免一次 DOM 更新把整段答案同时显示出来。
 */
function createProgressiveRenderer(message: AssistantMessage) {
  const characters: string[] = []
  let timer: ReturnType<typeof setTimeout> | null = null
  let finished = false
  let settled = false
  let resolveDrained!: () => void
  const drained = new Promise<void>((resolve) => {
    resolveDrained = resolve
  })

  const settleIfDrained = () => {
    if (finished && characters.length === 0 && !settled) {
      settled = true
      resolveDrained()
    }
  }

  const schedule = () => {
    if (timer !== null) return
    if (characters.length === 0) {
      settleIfDrained()
      return
    }

    timer = setTimeout(() => {
      timer = null
      const count = characters.length > 240 ? 12 : characters.length > 80 ? 6 : 2
      message.content += characters.splice(0, count).join('')
      scrollToBottom()
      schedule()
    }, 24)
  }

  return {
    append(text: string) {
      characters.push(...Array.from(text))
      schedule()
    },
    finish() {
      finished = true
      schedule()
    },
    flush() {
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      if (characters.length > 0) {
        message.content += characters.splice(0).join('')
      }
      finished = true
      settleIfDrained()
      scrollToBottom()
    },
    drained
  }
}

async function submit(suggestedQuestion?: string) {
  const value = (suggestedQuestion ?? question.value).trim()
  if (!value || isGenerating.value) return

  const answerScope: AnswerScopeSnapshot = searchScope.value === 'current' && props.currentNoteId
    ? { type: 'note', id: props.currentNoteId }
    : searchScope.value === 'notebook' && props.currentNotebookId
      ? { type: 'notebook', id: props.currentNotebookId }
      : { type: 'all', id: null }
  const history = buildConversationHistory()
  conversationRevision += 1
  question.value = ''
  const userMessage: UserMessage = {
    id: ++messageSequence,
    clientMessageId: createMessageId(),
    role: 'user',
    content: value
  }
  // 后续 delta 会持续修改此对象，必须直接持有 Vue Proxy；
  // 若先创建普通对象再放入响应式数组，修改原对象不会触发逐帧渲染。
  const assistantMessage = reactive<AssistantMessage>({
    id: ++messageSequence,
    clientMessageId: createMessageId(),
    role: 'assistant',
    content: '',
    sources: [],
    status: 'streaming',
    question: value,
    progressText: '正在检索相关笔记',
    scope: answerScope,
    feedback: null
  })
  messages.value.push(userMessage, assistantMessage)
  isGenerating.value = true
  const controller = new AbortController()
  activeController = controller
  const renderer = createProgressiveRenderer(assistantMessage)
  activeRenderer = renderer
  let pendingSources: RagSource[] = []
  scrollToBottom()

  try {
    const scope = answerScope.type === 'note' && answerScope.id
      ? { noteId: answerScope.id }
      : answerScope.type === 'notebook' && answerScope.id
        ? { notebookId: answerScope.id }
        : {}
    await streamNoteAnswer(value, history, scope, {
      onStatus(phase) {
        assistantMessage.progressText = phase === 'generating'
          ? '正在根据笔记生成回答'
          : '正在检索相关笔记'
      },
      onSources(sources) {
        pendingSources = sources
      },
      onDelta(text) {
        renderer.append(text)
      },
      onDone() {
        renderer.finish()
      }
    }, controller.signal)
    await renderer.drained
    assistantMessage.sources = pendingSources
    assistantMessage.status = 'done'
    persistConversation()
  } catch (error) {
    renderer.flush()
    if (controller.signal.aborted) {
      assistantMessage.status = 'stopped'
    } else {
      let message = error instanceof Error ? error.message : 'AI 回答生成失败'
      if (error instanceof AiStreamHttpError && error.errorId) {
        message += `（错误编号：${error.errorId}）`
      }
      assistantMessage.status = 'error'
      assistantMessage.error = message
      ElMessage.error(message)
      if (error instanceof AiStreamHttpError && error.status === 401) {
        await router.push('/login')
      }
    }
  } finally {
    if (activeController === controller) activeController = null
    if (activeRenderer === renderer) activeRenderer = null
    isGenerating.value = false
    scrollToBottom()
  }
}

/**
 * 只携带最近三轮已完成对话。失败或被停止的回答不进入上下文，
 * 防止不完整内容影响下一次追问。
 */
function buildConversationHistory(): ConversationMessage[] {
  const history: ConversationMessage[] = []
  for (let index = 0; index < messages.value.length - 1; index += 1) {
    const userMessage = messages.value[index]
    const assistantMessage = messages.value[index + 1]
    if (userMessage?.role !== 'user'
      || assistantMessage?.role !== 'assistant'
      || assistantMessage.status !== 'done') {
      continue
    }
    history.push(
      { role: 'user', content: userMessage.content },
      { role: 'assistant', content: assistantMessage.content }
    )
    index += 1
  }
  return history.slice(-6)
}

function handleQuestionKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void submit()
}

function stopGeneration() {
  activeController?.abort()
  // 流已经 onDone 但缓冲还没吐完时卸载组件，渲染器的 setTimeout 链会继续按
  // 每 24ms 一批跑到吐完（长回答能有几十秒）。flush 会清掉定时器并一次性排空，
  // 让它在卸载瞬间结束。
  activeRenderer?.flush()
}

async function clearConversation() {
  if (messages.value.length === 0) return
  try {
    await ElMessageBox.confirm(
      '删除后将无法从历史会话中恢复。确定继续吗？',
      '删除当前对话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  const deletingId = activeConversationId.value
  if (deletingId && !await deleteRemoteConversationIfNeeded(deletingId)) return
  conversationRevision += 1
  if (deletingId) {
    savedConversations.value = savedConversations.value.filter(
      conversation => conversation.id !== deletingId
    )
  }
  messages.value = []
  messageSequence = 0
  activeConversationId.value = null
  question.value = ''
  saveConversationStore()
}

function startNewConversation() {
  if (isGenerating.value) return
  conversationRevision += 1
  messages.value = []
  messageSequence = 0
  activeConversationId.value = null
  question.value = ''
  historyDialogVisible.value = false
  saveConversationStore()
}

function openConversation(conversationId: string) {
  if (isGenerating.value) return
  const conversation = savedConversations.value.find(item => item.id === conversationId)
  if (!conversation) return
  conversationRevision += 1
  activeConversationId.value = conversation.id
  messages.value = sanitizeMessages(conversation.messages)
  question.value = ''
  historyDialogVisible.value = false
  saveConversationStore()
  scrollToBottom()
}

async function deleteConversation(conversationId: string) {
  const conversation = savedConversations.value.find(item => item.id === conversationId)
  if (!conversation) return
  try {
    await ElMessageBox.confirm(
      `确定删除会话“${conversation.title}”吗？`,
      '删除历史会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  if (!await deleteRemoteConversationIfNeeded(conversationId)) return
  conversationRevision += 1
  savedConversations.value = savedConversations.value.filter(item => item.id !== conversationId)
  if (activeConversationId.value === conversationId) {
    messages.value = []
    messageSequence = 0
    activeConversationId.value = null
    question.value = ''
  }
  saveConversationStore()
}

function formatConversationTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

function retryMessage(message: AssistantMessage) {
  if (isGenerating.value) return
  const index = messages.value.findIndex(item => item.id === message.id)
  if (index >= 0) {
    const previous = messages.value[index - 1]
    const deleteFrom = previous?.role === 'user' ? index - 1 : index
    messages.value.splice(deleteFrom, index - deleteFrom + 1)
  }
  void submit(message.question)
}

function closePanel() {
  emit('update:open', false)
}

function openSource(source: RagSource) {
  emit('open-note', source)
  if (window.innerWidth <= 760) closePanel()
}

function handleAnswerClick(event: MouseEvent, message: AssistantMessage) {
  const element = (event.target as HTMLElement).closest<HTMLElement>('[data-source-index]')
  if (!element) return
  const index = Number(element.dataset.sourceIndex) - 1
  const source = message.sources[index]
  if (source) openSource(source)
}

async function submitHelpfulFeedback(message: AssistantMessage) {
  if (message.feedback?.rating === 'helpful') return
  await submitFeedback(message, { rating: 'helpful' })
}

function openUnhelpfulFeedback(message: AssistantMessage) {
  feedbackTarget.value = message
  feedbackForm.reason = message.feedback?.rating === 'unhelpful'
    ? message.feedback.reason || ''
    : ''
  feedbackForm.comment = message.feedback?.rating === 'unhelpful'
    ? message.feedback.comment || ''
    : ''
  feedbackDialogVisible.value = true
}

async function submitUnhelpfulFeedback() {
  const target = feedbackTarget.value
  if (!target || !feedbackForm.reason) return
  const saved = await submitFeedback(target, {
    rating: 'unhelpful',
    reason: feedbackForm.reason,
    comment: feedbackForm.comment.trim() || null
  })
  if (saved) feedbackDialogVisible.value = false
}

async function submitFeedback(
  message: AssistantMessage,
  payload: Parameters<typeof saveAnswerFeedback>[2]
) {
  if (feedbackSubmittingMessageId.value) return false
  const conversationId = activeConversationId.value
  const conversation = savedConversations.value.find(item => item.id === conversationId)
  if (!conversationId || !conversation) {
    ElMessage.error('当前回答尚未完成同步，请稍后重试')
    return false
  }

  feedbackSubmittingMessageId.value = message.clientMessageId
  try {
    if (!await syncConversationToServer(conversation)) return false
    const feedback = await saveAnswerFeedback(
      conversationId,
      message.clientMessageId,
      payload
    )
    message.feedback = feedback
    const storedMessage = conversation.messages.find(
      item => item.role === 'assistant'
        && item.clientMessageId === message.clientMessageId
    )
    if (storedMessage?.role === 'assistant') storedMessage.feedback = feedback
    feedbackStatistics.value = null
    saveConversationStore()
    ElMessage.success('反馈已记录')
    return true
  } catch {
    return false
  } finally {
    feedbackSubmittingMessageId.value = null
  }
}

function persistConversation() {
  if (!conversationStorageKey) return
  const completed = collectCompletedMessages(messages.value)
  if (completed.length === 0) return

  const now = new Date().toISOString()
  const conversationId = activeConversationId.value || createConversationId()
  const existing = savedConversations.value.find(item => item.id === conversationId)
  const conversation: SavedConversation = {
    id: conversationId,
    title: existing?.title || createConversationTitle(completed),
    updatedAt: now,
    messages: completed.slice(-MAX_PERSISTED_MESSAGES)
  }
  activeConversationId.value = conversationId
  savedConversations.value = [
    conversation,
    ...savedConversations.value.filter(item => item.id !== conversationId)
  ].slice(0, MAX_SAVED_CONVERSATIONS)
  saveConversationStore()
  queueConversationSync(conversation)
}

function queueConversationSync(conversation: SavedConversation) {
  markConversationPending(conversation.id, true)
  void syncConversationToServer(conversation)
}

async function syncConversationToServer(conversation: SavedConversation): Promise<boolean> {
  try {
    const detail = await saveAiConversation(conversation.id, {
      title: conversation.title,
      messages: toStoredMessages(conversation.messages)
    })
    serverConversationIds.add(conversation.id)
    markConversationPending(conversation.id, false)
    if (conversationSyncMarkerKey) localStorage.setItem(conversationSyncMarkerKey, '1')

    const current = savedConversations.value.find(item => item.id === conversation.id)
    if (current && timeValue(current.updatedAt) <= timeValue(detail.updatedAt)) {
      current.updatedAt = detail.updatedAt
      saveConversationStore()
    }
    return true
  } catch {
    // 请求层已经提示错误；pending 标记会让下次刷新继续同步。
    return false
  }
}

async function deleteRemoteConversationIfNeeded(conversationId: string) {
  if (!serverConversationIds.has(conversationId)) {
    markConversationPending(conversationId, false)
    return true
  }
  try {
    await deleteAiConversation(conversationId)
    serverConversationIds.delete(conversationId)
    markConversationPending(conversationId, false)
    return true
  } catch {
    return false
  }
}

function toStoredMessages(conversationMessages: ChatMessage[]): StoredConversationMessage[] {
  return collectCompletedMessages(conversationMessages).map(message => {
    if (message.role === 'user') {
      return {
        role: 'user',
        content: message.content,
        question: null,
        sources: [],
        clientMessageId: message.clientMessageId,
        scopeType: null,
        scopeId: null,
        feedback: null
      }
    }
    return {
      role: 'assistant',
      content: message.content,
      question: message.question || null,
      sources: message.sources,
      clientMessageId: message.clientMessageId,
      scopeType: message.scope.type,
      scopeId: message.scope.id,
      feedback: message.feedback
    }
  })
}

async function synchronizeConversationsWithServer(epoch: number, revisionAtStart: number) {
  const localSnapshot = [...savedConversations.value]
  const pendingIds = readPendingConversationIds()
  const firstServerSync = !conversationSyncMarkerKey
    || localStorage.getItem(conversationSyncMarkerKey) !== '1'
  if (firstServerSync) {
    localSnapshot.forEach(conversation => pendingIds.add(conversation.id))
    writePendingConversationIds(pendingIds)
  }

  for (const conversation of localSnapshot) {
    if (!pendingIds.has(conversation.id)) continue
    try {
      const saved = await saveAiConversation(conversation.id, {
        title: conversation.title,
        messages: toStoredMessages(conversation.messages)
      })
      conversation.updatedAt = saved.updatedAt
      pendingIds.delete(conversation.id)
      serverConversationIds.add(conversation.id)
      writePendingConversationIds(pendingIds)
    } catch {
      // 保留 pending，后续刷新继续尝试；其他本地会话仍继续迁移。
    }
  }

  try {
    const summaries = await getAiConversations()
    const details = await Promise.all(
      summaries.map(summary => getAiConversation(summary.clientId))
    )
    if (epoch !== conversationSyncEpoch) return

    serverConversationIds.clear()
    const remoteConversations = details
      .map(savedConversationFromServer)
      .filter((conversation): conversation is SavedConversation => conversation !== null)
    remoteConversations.forEach(conversation => serverConversationIds.add(conversation.id))

    const remainingPending = readPendingConversationIds()
    const merged = new Map(remoteConversations.map(conversation => [conversation.id, conversation]))
    for (const localConversation of localSnapshot) {
      if (remainingPending.has(localConversation.id)) {
        merged.set(localConversation.id, localConversation)
      }
    }

    if (conversationSyncMarkerKey) localStorage.setItem(conversationSyncMarkerKey, '1')
    if (revisionAtStart !== conversationRevision) return

    savedConversations.value = [...merged.values()]
      .sort((left, right) => timeValue(right.updatedAt) - timeValue(left.updatedAt))
      .slice(0, MAX_SAVED_CONVERSATIONS)
    if (activeConversationId.value) {
      const active = savedConversations.value.find(
        conversation => conversation.id === activeConversationId.value
      )
      if (active) messages.value = sanitizeMessages(active.messages)
      else {
        activeConversationId.value = null
        messages.value = []
      }
    }
    saveConversationStore()
    scrollToBottom()
  } catch {
    // 服务端暂时不可用时继续使用已经恢复的本地记录。
  }
}

function savedConversationFromServer(detail: AiConversationDetail): SavedConversation | null {
  const rawMessages = detail.messages.map(message => message.role === 'user'
    ? {
        role: 'user',
        content: message.content,
        clientMessageId: message.clientMessageId
      }
    : {
        role: 'assistant',
        content: message.content,
        sources: message.sources,
        status: 'done',
        question: message.question || '',
        progressText: '',
        clientMessageId: message.clientMessageId,
        scope: {
          type: message.scopeType || 'all',
          id: message.scopeId
        },
        feedback: message.feedback
      })
  return sanitizeSavedConversation({
    id: detail.clientId,
    title: detail.title,
    updatedAt: detail.updatedAt,
    messages: rawMessages
  })
}

function timeValue(value: string) {
  const timestamp = new Date(value).getTime()
  return Number.isNaN(timestamp) ? 0 : timestamp
}

function readPendingConversationIds() {
  if (!conversationPendingKey) return new Set<string>()
  try {
    const value = JSON.parse(localStorage.getItem(conversationPendingKey) || '[]')
    return new Set<string>(Array.isArray(value)
      ? value.filter(item => typeof item === 'string')
      : [])
  } catch {
    return new Set<string>()
  }
}

function writePendingConversationIds(ids: Set<string>) {
  if (!conversationPendingKey) return
  if (ids.size === 0) localStorage.removeItem(conversationPendingKey)
  else localStorage.setItem(conversationPendingKey, JSON.stringify([...ids]))
}

function markConversationPending(conversationId: string, pending: boolean) {
  const ids = readPendingConversationIds()
  if (pending) ids.add(conversationId)
  else ids.delete(conversationId)
  writePendingConversationIds(ids)
}

function collectCompletedMessages(sourceMessages: ChatMessage[]) {
  const completed: ChatMessage[] = []
  for (let index = 0; index < sourceMessages.length - 1; index += 1) {
    const userMessage = sourceMessages[index]
    const assistantMessage = sourceMessages[index + 1]
    if (userMessage?.role !== 'user'
      || assistantMessage?.role !== 'assistant'
      || assistantMessage.status !== 'done') {
      continue
    }
    completed.push(userMessage, assistantMessage)
    index += 1
  }
  return completed
}

function createConversationId() {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function createMessageId() {
  return createConversationId()
}

function createConversationTitle(conversationMessages: ChatMessage[]) {
  const firstQuestion = conversationMessages.find(message => message.role === 'user')?.content
    .replace(/\s+/g, ' ')
    .trim() || '新对话'
  const characters = Array.from(firstQuestion)
  return characters.length > 24 ? `${characters.slice(0, 24).join('')}…` : firstQuestion
}

function saveConversationStore() {
  if (!conversationStorageKey) return
  const store: PersistedConversationStore = {
    version: 2,
    activeConversationId: activeConversationId.value,
    conversations: savedConversations.value.slice(0, MAX_SAVED_CONVERSATIONS)
  }
  try {
    localStorage.setItem(conversationStorageKey, JSON.stringify(store))
  } catch {
    // 本地存储空间不足不影响当前对话。
  }
}

function restoreConversationStore() {
  if (!conversationStorageKey) return
  const raw = localStorage.getItem(conversationStorageKey)
  if (raw) {
    try {
      const persisted = JSON.parse(raw) as Partial<PersistedConversationStore>
      if (persisted.version !== 2 || !Array.isArray(persisted.conversations)) {
        localStorage.removeItem(conversationStorageKey)
      } else {
        savedConversations.value = persisted.conversations
          .map(sanitizeSavedConversation)
          .filter((conversation): conversation is SavedConversation => conversation !== null)
          .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
          .slice(0, MAX_SAVED_CONVERSATIONS)

        if (persisted.activeConversationId === null) {
          activeConversationId.value = null
        } else {
          const activeExists = savedConversations.value.some(
            conversation => conversation.id === persisted.activeConversationId
          )
          activeConversationId.value = activeExists
            ? persisted.activeConversationId || null
            : savedConversations.value[0]?.id || null
        }
        const active = savedConversations.value.find(
          conversation => conversation.id === activeConversationId.value
        )
        messages.value = active ? sanitizeMessages(active.messages) : []
        scrollToBottom()
        return
      }
    } catch {
      localStorage.removeItem(conversationStorageKey)
    }
  }
  migrateLegacyConversation()
}

function migrateLegacyConversation() {
  if (!legacyConversationStorageKey) return
  const raw = localStorage.getItem(legacyConversationStorageKey)
  if (!raw) return
  try {
    const legacy = JSON.parse(raw) as Partial<LegacyPersistedConversation>
    if (legacy.version !== 1 || !Array.isArray(legacy.messages)) return
    const restored = sanitizeMessages(legacy.messages)
    if (restored.length === 0) return
    const conversation: SavedConversation = {
      id: createConversationId(),
      title: createConversationTitle(restored),
      updatedAt: typeof legacy.updatedAt === 'string' ? legacy.updatedAt : new Date().toISOString(),
      messages: restored
    }
    savedConversations.value = [conversation]
    activeConversationId.value = conversation.id
    messages.value = sanitizeMessages(conversation.messages)
    saveConversationStore()
  } catch {
    // 旧格式损坏时直接丢弃，不影响 AI 助手打开。
  } finally {
    localStorage.removeItem(legacyConversationStorageKey)
  }
}

function sanitizeSavedConversation(value: unknown): SavedConversation | null {
  if (!value || typeof value !== 'object') return null
  const conversation = value as Partial<SavedConversation>
  if (typeof conversation.id !== 'string'
    || !/^[A-Za-z0-9-]{1,64}$/.test(conversation.id)
    || !Array.isArray(conversation.messages)) {
    return null
  }
  const sanitizedMessages = sanitizeMessages(conversation.messages)
  if (sanitizedMessages.length === 0) return null
  return {
    id: conversation.id,
    title: typeof conversation.title === 'string' && conversation.title.trim()
      ? conversation.title.slice(0, 100)
      : createConversationTitle(sanitizedMessages),
    updatedAt: typeof conversation.updatedAt === 'string'
      ? conversation.updatedAt
      : new Date().toISOString(),
    messages: sanitizedMessages
  }
}

function sanitizeMessages(sourceMessages: unknown[]) {
  const restored: ChatMessage[] = []
  for (let index = 0; index < sourceMessages.length - 1; index += 1) {
    const userMessage = sanitizeUserMessage(sourceMessages[index])
    const assistantMessage = sanitizeAssistantMessage(sourceMessages[index + 1])
    if (!userMessage || !assistantMessage) continue
    restored.push(userMessage, assistantMessage)
    index += 1
  }
  return restored.slice(-MAX_PERSISTED_MESSAGES)
}

function sanitizeUserMessage(value: unknown): UserMessage | null {
  if (!value || typeof value !== 'object') return null
  const message = value as Partial<UserMessage>
  if (message.role !== 'user' || typeof message.content !== 'string' || !message.content.trim()) {
    return null
  }
  return {
    id: ++messageSequence,
    clientMessageId: sanitizeMessageId(message.clientMessageId),
    role: 'user',
    content: message.content.slice(0, 500)
  }
}

function sanitizeAssistantMessage(value: unknown): AssistantMessage | null {
  if (!value || typeof value !== 'object') return null
  const message = value as Partial<AssistantMessage>
  if (message.role !== 'assistant' || message.status !== 'done'
    || typeof message.content !== 'string' || !message.content.trim()) {
    return null
  }
  const sources = Array.isArray(message.sources)
    ? message.sources.map(sanitizeSource).filter((source): source is RagSource => source !== null)
    : []
  return {
    id: ++messageSequence,
    clientMessageId: sanitizeMessageId(message.clientMessageId),
    role: 'assistant',
    content: message.content.slice(0, 20_000),
    sources,
    status: 'done',
    question: typeof message.question === 'string' ? message.question.slice(0, 500) : '',
    progressText: '',
    scope: sanitizeAnswerScope(message.scope),
    feedback: sanitizeFeedback(message.feedback)
  }
}

function sanitizeMessageId(value: unknown) {
  return typeof value === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(value)
    ? value
    : createMessageId()
}

function sanitizeAnswerScope(value: unknown): AnswerScopeSnapshot {
  if (!value || typeof value !== 'object') return { type: 'all', id: null }
  const scope = value as Partial<AnswerScopeSnapshot>
  if (scope.type === 'all') return { type: 'all', id: null }
  if ((scope.type === 'notebook' || scope.type === 'note')
    && typeof scope.id === 'number'
    && Number.isInteger(scope.id)
    && scope.id > 0) {
    return { type: scope.type, id: scope.id }
  }
  return { type: 'all', id: null }
}

function sanitizeFeedback(value: unknown): AiAnswerFeedback | null {
  if (!value || typeof value !== 'object') return null
  const feedback = value as Partial<AiAnswerFeedback>
  if (feedback.rating !== 'helpful' && feedback.rating !== 'unhelpful') return null
  const validReasons: FeedbackReason[] = [
    'irrelevant_sources',
    'incomplete',
    'inconsistent',
    'wrong_scope',
    'other'
  ]
  const reason = feedback.reason && validReasons.includes(feedback.reason)
    ? feedback.reason
    : null
  if (feedback.rating === 'unhelpful' && !reason) return null
  return {
    rating: feedback.rating,
    reason: feedback.rating === 'helpful' ? null : reason,
    comment: feedback.rating === 'helpful'
      ? null
      : typeof feedback.comment === 'string'
        ? feedback.comment.slice(0, 500)
        : null,
    evalCaseId: typeof feedback.evalCaseId === 'number'
      && Number.isInteger(feedback.evalCaseId)
      && feedback.evalCaseId > 0
      ? feedback.evalCaseId
      : null,
    updatedAt: typeof feedback.updatedAt === 'string'
      ? feedback.updatedAt
      : new Date().toISOString()
  }
}

function sanitizeSource(value: unknown): RagSource | null {
  if (!value || typeof value !== 'object') return null
  const source = value as Partial<RagSource>
  if (typeof source.noteId !== 'number'
    || typeof source.title !== 'string'
    || typeof source.content !== 'string'
    || typeof source.score !== 'number'
    || !Number.isFinite(source.score)) {
    return null
  }
  return {
    noteId: source.noteId,
    title: source.title.slice(0, 500),
    content: source.content.slice(0, 10_000),
    score: source.score
  }
}

watch(
  () => props.currentNoteId,
  (noteId) => {
    if (!noteId && searchScope.value === 'current') searchScope.value = 'all'
  }
)

watch(
  () => props.currentNotebookId,
  (notebookId) => {
    if (!notebookId && searchScope.value === 'notebook') searchScope.value = 'all'
  }
)

watch(
  () => userStore.userInfo?.id,
  (userId) => {
    const epoch = ++conversationSyncEpoch
    stopGeneration()
    messages.value = []
    savedConversations.value = []
    activeConversationId.value = null
    messageSequence = 0
    conversationRevision = 0
    serverConversationIds.clear()
    conversationStorageKey = userId ? `${CONVERSATION_STORAGE_PREFIX}${userId}` : null
    legacyConversationStorageKey = userId ? `${LEGACY_CONVERSATION_STORAGE_PREFIX}${userId}` : null
    conversationSyncMarkerKey = userId ? `${CONVERSATION_SYNC_MARKER_PREFIX}${userId}` : null
    conversationPendingKey = userId ? `${CONVERSATION_PENDING_PREFIX}${userId}` : null
    restoreConversationStore()
    if (userId) void synchronizeConversationsWithServer(epoch, conversationRevision)
  },
  { immediate: true }
)

onBeforeUnmount(stopGeneration)
</script>

<style scoped>
.ai-assistant {
  position: fixed;
  inset: 0 0 0 auto;
  z-index: 3001;
  width: 430px;
  max-width: 100vw;
  display: flex;
  flex-direction: column;
  background: #fbfcfb;
  border-left: 1px solid #dfe6e1;
  box-shadow: -16px 0 42px rgba(30, 48, 38, 0.12);
}
.assistant-header {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  padding: 12px 18px 10px 20px;
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid #e5eae6;
  backdrop-filter: blur(14px);
}
.assistant-title,
.assistant-title > div,
.header-actions,
.source-meta {
  display: flex;
  align-items: center;
}
.assistant-title { min-width: 0; gap: 11px; }
.assistant-title > div {
  align-items: flex-start;
  flex-direction: column;
  gap: 2px;
}
.assistant-title strong {
  color: #253029;
  font-size: 15px;
  font-weight: 650;
  white-space: nowrap;
}
.assistant-title span:not(.assistant-mark) {
  color: #8a948d;
  font-size: 11px;
  white-space: nowrap;
}
.assistant-mark,
.welcome-icon {
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(145deg, #247b68, #3c9984);
  box-shadow: 0 7px 18px rgba(36, 123, 104, 0.2);
}
.assistant-mark {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 12px;
}
.header-actions { justify-content: flex-end; flex-wrap: wrap; gap: 2px; }
.header-actions :deep(.el-button + .el-button) { margin-left: 0; }
.message-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 20px 32px;
  scroll-behavior: smooth;
}
.welcome-state {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  text-align: center;
  padding: 28px 6px;
}
.welcome-icon {
  width: 52px;
  height: 52px;
  border-radius: 17px;
  font-size: 22px;
}
.welcome-state h2 {
  margin: 18px 0 8px;
  color: #28322c;
  font-family: Georgia, "Noto Serif SC", "Songti SC", serif;
  font-size: 23px;
  font-weight: 500;
}
.welcome-state > p {
  max-width: 330px;
  margin: 0;
  color: #7c867f;
  font-size: 13px;
  line-height: 1.75;
}
.suggestion-list {
  width: 100%;
  display: grid;
  gap: 9px;
  margin-top: 28px;
}
.suggestion-list button {
  width: 100%;
  padding: 12px 14px;
  border: 1px solid #e2e8e3;
  border-radius: 12px;
  color: #4b5750;
  background: #fff;
  text-align: left;
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease, transform 0.18s ease;
}
.suggestion-list button:hover {
  border-color: #a9cbc1;
  background: #f3f8f6;
  transform: translateY(-1px);
}
.message-row {
  display: flex;
  margin-bottom: 24px;
}
.message-row--user { justify-content: flex-end; }
.user-bubble {
  max-width: 86%;
  padding: 11px 14px;
  border-radius: 16px 16px 4px 16px;
  color: #fff;
  background: #247b68;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.message-row--assistant {
  align-items: flex-start;
  gap: 10px;
}
.assistant-avatar {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  display: grid;
  place-items: center;
  border: 1px solid #cfe0da;
  border-radius: 9px;
  color: #247b68;
  background: #eaf3f0;
  font-size: 14px;
}
.assistant-message {
  min-width: 0;
  flex: 1;
  color: #354039;
  font-size: 14px;
  line-height: 1.78;
}
.answer-content {
  overflow-wrap: anywhere;
}
.answer-content--streaming::after {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 3px;
  vertical-align: -0.12em;
  border-radius: 1px;
  background: #2d8974;
  content: '';
  animation: stream-caret 0.8s steps(1) infinite;
}
.answer-content :deep(p) { margin: 0 0 0.85em; }
.answer-content :deep(p:last-child) { margin-bottom: 0; }
.answer-content :deep(ol),
.answer-content :deep(ul) {
  margin: 0.5em 0 0.9em;
  padding-left: 1.5em;
}
.answer-content :deep(li) { margin: 0.3em 0; }
.answer-content :deep(h1),
.answer-content :deep(h2),
.answer-content :deep(h3) {
  margin: 1em 0 0.5em;
  color: #263029;
  font-size: 15px;
}
.answer-content :deep(code) {
  padding: 2px 5px;
  border-radius: 5px;
  color: #276e5d;
  background: #edf4f1;
  font-size: 0.9em;
}
.answer-content :deep(pre) {
  overflow-x: auto;
  padding: 12px;
  border-radius: 10px;
  background: #222a25;
  color: #ecf2ee;
}
.answer-content :deep(pre code) { padding: 0; background: transparent; color: inherit; }
.answer-content :deep(.citation-link) {
  appearance: none;
  border: 0;
  padding: 0 2px;
  color: #247b68;
  background: transparent;
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}
.answer-content :deep(.citation-link:hover) { text-decoration: underline; }
@keyframes stream-caret {
  50% { opacity: 0; }
}
.thinking-state {
  min-height: 30px;
  display: flex;
  align-items: center;
  gap: 4px;
  color: #879188;
}
.thinking-state span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #4a9684;
  animation: thinking 1.1s infinite ease-in-out;
}
.thinking-state span:nth-child(2) { animation-delay: 0.16s; }
.thinking-state span:nth-child(3) { animation-delay: 0.32s; }
.thinking-state em { margin-left: 5px; font-size: 12px; font-style: normal; }
.message-hint {
  margin: 8px 0 0;
  font-size: 12px;
}
.message-hint { color: #929b95; }
.message-failure {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  padding: 9px 10px;
  border-radius: 9px;
  color: #b44d4d;
  background: #fdf0f0;
  font-size: 12px;
}
.message-failure button {
  flex-shrink: 0;
  padding: 0;
  border: 0;
  color: #247b68;
  background: transparent;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
}
.source-section {
  display: grid;
  gap: 8px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #e7ebe8;
}
.source-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}
.source-heading strong { color: #4a554e; font-size: 12px; font-weight: 650; }
.source-heading span { color: #9aa29c; font-size: 11px; }
.source-card {
  width: 100%;
  display: grid;
  grid-template-columns: 23px minmax(0, 1fr) 16px;
  align-items: start;
  gap: 9px;
  padding: 10px;
  border: 1px solid #e2e8e4;
  border-radius: 11px;
  color: inherit;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.source-card:hover { border-color: #9ec5ba; background: #f6faf8; }
.source-index {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  color: #247b68;
  background: #e8f2ef;
  font-size: 11px;
  font-weight: 700;
}
.source-body { min-width: 0; display: block; }
.source-meta { justify-content: space-between; gap: 8px; }
.source-meta strong {
  min-width: 0;
  overflow: hidden;
  color: #354139;
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-meta em {
  flex-shrink: 0;
  color: #6e8d82;
  font-size: 10px;
  font-style: normal;
}
.source-content {
  display: -webkit-box;
  margin-top: 4px;
  overflow: hidden;
  color: #89928c;
  font-size: 11px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.source-arrow { align-self: center; color: #aab1ac; }
.answer-feedback {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
  color: #929b95;
  font-size: 10px;
}
.answer-feedback button {
  padding: 4px 8px;
  border: 1px solid #dde5e0;
  border-radius: 999px;
  color: #66736b;
  background: #fff;
  font: inherit;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;
}
.answer-feedback button:hover,
.answer-feedback button.active {
  border-color: #8fbcaf;
  color: #247b68;
  background: #edf6f3;
}
.answer-feedback button:disabled { cursor: wait; opacity: 0.62; }
.answer-feedback em {
  color: #77a093;
  font-style: normal;
}
.feedback-form {
  display: grid;
  gap: 18px;
}
.feedback-form label {
  display: grid;
  gap: 7px;
}
.feedback-form label > span {
  color: #4c5951;
  font-size: 13px;
  font-weight: 600;
}
.feedback-form :deep(.el-select) { width: 100%; }
.feedback-statistics { min-height: 260px; }
.feedback-statistics__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
  margin-bottom: 18px;
}
.feedback-statistics__metrics > div {
  display: grid;
  gap: 4px;
  padding: 13px 10px;
  border-radius: 11px;
  background: #f2f7f5;
  text-align: center;
}
.feedback-statistics__metrics strong { color: #247b68; font-size: 21px; }
.feedback-statistics__metrics span { color: #7f8c84; font-size: 11px; }
.feedback-statistics__content {
  display: grid;
  grid-template-columns: minmax(220px, 0.8fr) minmax(0, 1.2fr);
  gap: 18px;
}
.feedback-statistics__content section { min-width: 0; }
.feedback-statistics__content h3 {
  margin: 0 0 10px;
  color: #3c4941;
  font-size: 14px;
}
.feedback-reason-list { display: grid; gap: 12px; }
.feedback-reason-list > div > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 5px;
  font-size: 11px;
}
.feedback-reason-list span { color: #59655e; }
.feedback-reason-list em { color: #8a948e; font-style: normal; }
.feedback-question-list {
  max-height: min(46vh, 410px);
  display: grid;
  gap: 8px;
  overflow-y: auto;
}
.feedback-question-list article {
  padding: 11px 12px;
  border: 1px solid #e0e7e2;
  border-radius: 10px;
  background: #fff;
}
.feedback-question-list article > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.feedback-question-list strong {
  overflow: hidden;
  color: #3d4942;
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.feedback-question-list em {
  flex: 0 0 auto;
  color: #b36b59;
  font-size: 11px;
  font-style: normal;
}
.feedback-question-list article > span {
  display: block;
  margin-top: 5px;
  color: #89938d;
  font-size: 10px;
}
.feedback-question-list p { display: flex; flex-wrap: wrap; gap: 5px; margin: 8px 0 0; }
.feedback-question-list .feedback-eval-reason {
  margin-top: 5px;
  color: #b06a63;
  font-size: 11px;
  line-height: 1.5;
}
.feedback-statistics__empty {
  display: grid;
  min-height: 90px;
  place-items: center;
  color: #929b95;
  font-size: 12px;
  text-align: center;
}
.feedback-statistics__empty--large { min-height: 250px; }
.composer {
  flex: 0 0 auto;
  padding: 12px 16px 14px;
  border-top: 1px solid #e4e9e5;
  background: rgba(251, 252, 251, 0.97);
}
.composer-box {
  padding: 10px 10px 8px 12px;
  border: 1px solid #dce4de;
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 7px 22px rgba(35, 55, 43, 0.07);
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}
.scope-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  padding-bottom: 7px;
  border-bottom: 1px solid #eef1ef;
}
.scope-control > span {
  color: #8b958e;
  font-size: 10px;
}
.scope-control select {
  max-width: 120px;
  padding: 2px 20px 2px 6px;
  border: 0;
  outline: 0;
  color: #3f7566;
  background: transparent;
  font: inherit;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}
.scope-control select:focus-visible {
  border-radius: 5px;
  box-shadow: 0 0 0 2px rgba(36, 123, 104, 0.18);
}
.composer-box:focus-within {
  border-color: #8dbbae;
  box-shadow: 0 8px 26px rgba(36, 123, 104, 0.1);
}
.composer-box :deep(.el-textarea__inner) {
  padding: 0;
  border: 0;
  box-shadow: none;
  color: #354039;
  background: transparent;
  font-size: 14px;
  line-height: 1.6;
}
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 7px;
}
.composer-actions > span { color: #a2aaa4; font-size: 10px; }
.send-button,
.stop-button { width: 32px; height: 32px; }
.stop-button { color: #536059; border-color: #d9dfdb; }
.assistant-disclaimer {
  margin: 7px 0 0;
  color: #a1a8a3;
  font-size: 10px;
  text-align: center;
}
.evaluation-manager { min-height: 420px; }
.evaluation-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 8px;
}
.evaluation-toolbar p {
  margin: 0;
  color: #7d8881;
  font-size: 12px;
  line-height: 1.55;
}
.evaluation-toolbar > div { display: flex; flex-shrink: 0; gap: 8px; }
.eval-case-list,
.eval-result-list {
  max-height: min(54vh, 520px);
  display: grid;
  gap: 9px;
  overflow-y: auto;
  padding: 2px 4px 2px 2px;
}
.eval-case-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 13px 14px;
  border: 1px solid #e0e7e2;
  border-radius: 12px;
  background: #fff;
}
.eval-case-main { min-width: 0; flex: 1; }
.eval-case-title { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
.eval-case-title strong { color: #354139; font-size: 13px; }
.eval-case-main p {
  margin: 7px 0 5px;
  overflow: hidden;
  color: #566259;
  font-size: 12px;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.eval-case-main > span,
.eval-result-card span { color: #8b958e; font-size: 10px; }
.eval-result-card .eval-result-source {
  display: block;
  margin-top: 4px;
  color: #7d8a82;
  font-size: 10px;
  line-height: 1.5;
}
.eval-case-actions { display: flex; align-items: center; flex-shrink: 0; gap: 3px; }
.evaluation-empty {
  min-height: 180px;
  display: grid;
  place-content: center;
  gap: 7px;
  color: #8b958e;
  text-align: center;
}
.evaluation-empty strong { color: #58635c; font-size: 14px; }
.evaluation-empty span { font-size: 12px; }
.eval-retrieval-traces {
  display: grid;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #dfe6e1;
}
.eval-retrieval-traces article { display: grid; gap: 5px; }
.eval-retrieval-traces article > strong { color: #59655e; font-size: 12px; }
.eval-retrieval-traces article > span { display: flex; flex-wrap: wrap; gap: 5px; }
.eval-retrieval-traces em {
  padding: 3px 7px;
  border: 1px solid #e2e8e4;
  border-radius: 999px;
  color: #7c8780;
  font-size: 11px;
  font-style: normal;
}
.eval-retrieval-traces em.selected {
  border-color: #9fcbbf;
  background: #edf7f3;
  color: #287e6b;
}
.eval-run-selector {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 12px;
}
.eval-run-selector > span { color: #7d8881; font-size: 11px; }
.eval-run-selector :deep(.el-select) { width: 250px; }
.eval-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}
.eval-metrics > div {
  display: grid;
  gap: 4px;
  padding: 11px 9px;
  border-radius: 10px;
  background: #f3f7f5;
  text-align: center;
}
.eval-metrics strong { color: #247b68; font-size: 17px; }
.eval-metrics span { color: #87918b; font-size: 10px; }
.eval-result-card {
  padding: 12px 13px;
  border: 1px solid #dce8e2;
  border-left: 3px solid #57a28d;
  border-radius: 10px;
  background: #fff;
}
.eval-result-card--failed { border-color: #f0d8d6; border-left-color: #d56d66; }
.eval-result-card > div { display: flex; justify-content: space-between; gap: 10px; }
.eval-result-card strong { color: #3c4941; font-size: 12px; }
.eval-result-card p { margin: 6px 0 4px; color: #69746d; font-size: 11px; }
.feedback-eval-suggestions {
  display: grid;
  gap: 8px;
  margin: 12px 0 4px;
  padding: 10px;
  border: 1px solid #e5e1ca;
  border-radius: 9px;
  background: #fffdf4;
}
.feedback-eval-suggestions > span {
  color: #746d4f;
  font-size: 12px;
  font-weight: 600;
}
.feedback-eval-suggestions > div { display: flex; flex-wrap: wrap; gap: 6px; }
.eval-case-form :deep(.el-select),
.eval-case-form :deep(.el-input-number) { width: 100%; }
.eval-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.index-manager { min-height: 210px; }
.conversation-list {
  max-height: min(58vh, 520px);
  display: grid;
  gap: 8px;
  overflow-y: auto;
  padding: 2px;
}
.conversation-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 8px 11px 13px;
  border: 1px solid #e1e7e3;
  border-radius: 11px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.conversation-item:hover,
.conversation-item--active {
  border-color: #9fc7bb;
  background: #f4f9f7;
}
.conversation-item:focus-visible {
  outline: 2px solid rgba(36, 123, 104, 0.3);
  outline-offset: 1px;
}
.conversation-item__body {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.conversation-item__body strong {
  overflow: hidden;
  color: #354139;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conversation-item__body span {
  color: #909a93;
  font-size: 11px;
}
.conversation-empty {
  min-height: 190px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  color: #929b95;
  text-align: center;
}
.conversation-empty .el-icon { color: #6a9f90; font-size: 30px; }
.conversation-empty strong { color: #4d5a52; font-size: 14px; }
.conversation-empty span { font-size: 12px; }
.index-health {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid #d9e9e2;
  border-radius: 13px;
  background: #f5faf8;
}
.index-health-dot {
  width: 11px;
  height: 11px;
  flex: 0 0 11px;
  border-radius: 50%;
  background: #35a478;
  box-shadow: 0 0 0 5px rgba(53, 164, 120, 0.12);
}
.index-health > div { display: flex; flex-direction: column; gap: 2px; }
.index-health strong { color: #2f5f50; font-size: 14px; }
.index-health span:not(.index-health-dot) { color: #75837b; font-size: 12px; }
.index-health--needs_rebuild,
.index-health--error { border-color: #eddfc7; background: #fdf9f1; }
.index-health--needs_rebuild .index-health-dot,
.index-health--error .index-health-dot {
  background: #d99a35;
  box-shadow: 0 0 0 5px rgba(217, 154, 53, 0.13);
}
.index-health--empty { border-color: #e3e7e4; background: #f8f9f8; }
.index-health--empty .index-health-dot {
  background: #8a948e;
  box-shadow: 0 0 0 5px rgba(138, 148, 142, 0.12);
}
.index-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 14px 0;
}
.index-stats > div {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 2px;
  padding: 10px 6px;
  border-radius: 10px;
  background: #f6f8f6;
}
.index-stats strong { color: #344139; font-size: 19px; }
.index-stats span { color: #8b948e; font-size: 11px; }
.index-details { margin: 0 0 14px; }
.index-details > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 7px 2px;
  border-bottom: 1px solid #eef1ef;
}
.index-details dt { color: #8a938d; font-size: 12px; }
.index-details dd { margin: 0; color: #4a554e; font-size: 12px; text-align: right; }
.index-backups { margin: 0 0 4px; }
.index-backups__header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.index-backups__header > div { display: flex; flex-direction: column; gap: 2px; }
.index-backups__header strong { color: #354139; font-size: 13px; }
.index-backups__header span { color: #8b948e; font-size: 11px; }
.index-backup-list {
  display: grid;
  gap: 6px;
  max-height: 190px;
  overflow-y: auto;
  padding: 2px;
}
.index-backup-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid #eef1ef;
  border-radius: 10px;
  background: #fbfcfb;
}
.index-backup-item__body { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.index-backup-item__title { display: flex; align-items: center; gap: 6px; }
.index-backup-item__body strong { color: #4a554e; font-size: 12px; }
.index-backup-item__body > span {
  overflow: hidden;
  color: #909a93;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.index-backups__empty {
  padding: 16px 8px;
  color: #929b95;
  font-size: 12px;
  text-align: center;
}
.index-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
.ai-panel-enter-active,
.ai-panel-leave-active { transition: transform 0.24s ease, opacity 0.2s ease; }
.ai-panel-enter-from,
.ai-panel-leave-to { transform: translateX(100%); opacity: 0; }
.ai-panel-mask-enter-active,
.ai-panel-mask-leave-active { transition: opacity 0.2s ease; }
.ai-panel-mask-enter-from,
.ai-panel-mask-leave-to { opacity: 0; }
.ai-panel-mask { display: none; }
@keyframes thinking {
  0%, 65%, 100% { transform: translateY(0); opacity: 0.35; }
  35% { transform: translateY(-3px); opacity: 1; }
}

@media (max-width: 760px) {
  .ai-panel-mask {
    position: fixed;
    inset: 0;
    z-index: 3000;
    display: block;
    background: rgba(27, 37, 31, 0.24);
    backdrop-filter: blur(2px);
  }
  .ai-assistant { width: min(430px, calc(100vw - 30px)); }
  .evaluation-toolbar,
  .eval-case-card { align-items: stretch; flex-direction: column; }
  .evaluation-toolbar > div,
  .eval-case-actions { justify-content: flex-end; }
  .eval-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .eval-form-grid { grid-template-columns: 1fr; gap: 0; }
  .feedback-statistics__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .feedback-statistics__content { grid-template-columns: 1fr; }
}

@media (max-width: 480px) {
  .ai-assistant { width: 100vw; }
  .assistant-header { padding-inline: 15px 10px; }
  .assistant-title strong { font-size: 13px; }
  .header-actions { gap: 0; }
  .message-list { padding: 20px 14px 26px; }
  .composer { padding: 10px 10px 12px; }
}
</style>
