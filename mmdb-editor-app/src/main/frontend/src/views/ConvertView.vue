<template>
  <div>
    <h2>awdb → mmdb 转换</h2>

    <el-upload drag :auto-upload="false" :limit="1" :on-change="startConvert" :on-exceed="() => {}">
      <el-icon size="48"><upload-filled /></el-icon>
      <div class="el-upload__text">拖拽 awdb 文件到此处，或 <em>点击选择</em></div>
    </el-upload>

    <div v-if="job" style="margin-top: 16px; max-width: 560px">
      <el-progress :percentage="progressPct" :status="job.status === 'error' ? 'exception' : undefined" />
      <p>状态: {{ job.status }}</p>
      <el-alert v-if="job.error" :title="job.error" type="error" />
      <div v-if="job.status === 'done'">
        <el-button type="primary" tag="a" :href="downloadUrl" target="_blank">下载 mmdb</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onUnmounted } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import { convert } from '../api'

const job = ref(null)
let timer = null

const progressPct = computed(() => {
  if (!job.value) return 0
  return job.value.status === 'done' ? 100 : job.value.status === 'error' ? 100 : 50
})

const downloadUrl = computed(() => `/api/files/${job.value?.mmdbId}/download`)

async function startConvert(upload) {
  stopPoll()
  job.value = null
  const { data } = await convert.start(upload.raw)
  job.value = data
  poll()
}

function poll() {
  timer = setInterval(async () => {
    if (!job.value) return stopPoll()
    const { data } = await convert.status(job.value.id)
    job.value = data
    if (data.status === 'done' || data.status === 'error') stopPoll()
  }, 300)
}

function stopPoll() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onUnmounted(stopPoll)
</script>
