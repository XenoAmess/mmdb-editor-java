<template>
  <div>
    <h2>浏览 / 编辑</h2>
    <el-form inline @submit.prevent>
      <el-form-item label="文件">
        <el-select v-model="fileId" placeholder="选择文件" style="width: 320px" @change="load">
          <el-option v-for="f in openedFiles" :key="f.id" :label="f.path" :value="f.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="items" border style="width: 100%" v-loading="loading">
      <el-table-column prop="prefix" label="前缀" width="220" />
      <el-table-column label="记录">
        <template #default="{ row }">
          <span class="record-cell">{{ JSON.stringify(row.record) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="openEditor(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      style="margin-top: 12px"
      layout="prev, pager, next"
      :page-size="pageSize"
      :current-page="page"
      @current-change="onPage" />

    <el-dialog v-model="editorVisible" :title="'编辑 ' + editingPrefix" width="640px">
      <el-input v-model="editorJson" type="textarea" :rows="14" />
      <el-alert v-if="editorError" :title="editorError" type="error" style="margin-top: 8px" @close="editorError = ''" />
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { files } from '../api'

const openedFiles = ref([])
const fileId = ref('')
const items = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = 20
const editorVisible = ref(false)
const editingPrefix = ref('')
const editorJson = ref('')
const editorError = ref('')

onMounted(() => {
  const cached = sessionStorage.getItem('openedFiles')
  if (cached) openedFiles.value = JSON.parse(cached)
})

async function load() {
  if (!fileId.value) return
  loading.value = true
  try {
    const { data } = await files.records(fileId.value, (page.value - 1) * pageSize, pageSize)
    items.value = data.items
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  page.value = p
  load()
}

function openEditor(row) {
  editingPrefix.value = row.prefix
  editorJson.value = JSON.stringify(row.record, null, 2)
  editorError.value = ''
  editorVisible.value = true
}

async function saveEdit() {
  let parsed
  try {
    parsed = JSON.parse(editorJson.value)
  } catch (e) {
    editorError.value = 'JSON 非法: ' + e.message
    return
  }
  try {
    await files.upsert(fileId.value, editingPrefix.value, parsed)
    editorVisible.value = false
    load()
  } catch (e) {
    editorError.value = '保存失败: ' + (e.response?.data?.message ?? e.message)
  }
}

async function remove(row) {
  await ElMessageBox.confirm(`删除 ${row.prefix}？空间将向上合并。`, '确认', { type: 'warning' })
  await files.remove(fileId.value, row.prefix)
  load()
}
</script>

<style scoped>
.record-cell {
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
