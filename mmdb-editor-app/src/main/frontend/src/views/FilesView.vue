<template>
  <div>
    <h2>文件管理</h2>

    <el-form inline @submit.prevent>
      <el-form-item label="服务器路径">
        <el-input v-model="openPath" placeholder="/path/to/db.mmdb" style="width: 320px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="openByPath">打开</el-button>
        <el-upload :show-file-list="false" :auto-upload="false" :on-change="uploadFile" style="margin-left: 12px">
          <el-button>上传 mmdb</el-button>
        </el-upload>
      </el-form-item>
    </el-form>

    <el-table :data="opened" border style="width: 100%">
      <el-table-column prop="path" label="路径" />
      <el-table-column label="database_type" width="160">
        <template #default="{ row }">{{ row.metadata?.database_type ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="ip_version" width="100">
        <template #default="{ row }">{{ row.metadata?.ip_version ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="node_count" width="120">
        <template #default="{ row }">{{ row.metadata?.node_count ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260">
        <template #default="{ row }">
          <el-button size="small" tag="a" :href="downloadUrl(row.id)" target="_blank">下载</el-button>
          <el-button size="small" type="primary" @click="save(row)">保存回源路径</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert v-if="message" :title="message" :type="messageType" style="margin-top: 12px" @close="message = ''" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { files } from '../api'

const openPath = ref('')
const opened = ref([])
const message = ref('')
const messageType = ref('success')

onMounted(() => {
  const cached = sessionStorage.getItem('openedFiles')
  if (cached) opened.value = JSON.parse(cached)
})

function persist() {
  sessionStorage.setItem('openedFiles', JSON.stringify(opened.value))
}

function note(msg, type = 'success') {
  message.value = msg
  messageType.value = type
}

async function openByPath() {
  try {
    const { data } = await files.open(openPath.value)
    opened.value.push(data)
    persist()
    note('已打开: ' + data.path)
  } catch (e) {
    note('打开失败: ' + (e.response?.data?.message ?? e.message), 'error')
  }
}

async function uploadFile(upload) {
  try {
    const { data } = await files.upload(upload.raw)
    opened.value.push(data)
    persist()
    note('已上传: ' + data.path)
  } catch (e) {
    note('上传失败: ' + (e.response?.data?.message ?? e.message), 'error')
  }
}

function downloadUrl(id) {
  return files.downloadUrl(id)
}

async function save(row) {
  try {
    const { data } = await files.save(row.id)
    note('已保存: ' + data.saved)
  } catch (e) {
    note('保存失败: ' + (e.response?.data?.message ?? e.message), 'error')
  }
}
</script>
