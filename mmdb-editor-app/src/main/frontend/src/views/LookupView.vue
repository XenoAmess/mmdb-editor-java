<template>
  <div>
    <h2>IP 查询</h2>
    <el-form inline @submit.prevent>
      <el-form-item label="文件">
        <el-select v-model="fileId" placeholder="选择文件" style="width: 320px">
          <el-option v-for="f in openedFiles" :key="f.id" :label="f.path" :value="f.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="IP">
        <el-input v-model="ip" placeholder="202.96.128.86 或 ::1" style="width: 240px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="doLookup">查询</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="error" :title="error" type="error" @close="error = ''" />
    <pre v-if="record" class="record-json">{{ record }}</pre>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { files } from '../api'

const openedFiles = ref([])
const fileId = ref('')
const ip = ref('')
const record = ref('')
const error = ref('')

onMounted(() => {
  const cached = sessionStorage.getItem('openedFiles')
  if (cached) openedFiles.value = JSON.parse(cached)
})

async function doLookup() {
  error.value = ''
  record.value = ''
  try {
    const { data } = await files.lookup(fileId.value, ip.value)
    record.value = JSON.stringify(data, null, 2)
  } catch (e) {
    error.value = e.response?.status === 404 ? '未命中: ' + ip.value : '查询失败: ' + (e.response?.data?.message ?? e.message)
  }
}
</script>

<style scoped>
.record-json {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  max-width: 720px;
  overflow: auto;
}
</style>
