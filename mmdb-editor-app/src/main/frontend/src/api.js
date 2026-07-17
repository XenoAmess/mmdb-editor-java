import axios from 'axios'

export const api = axios.create({ baseURL: '/api' })

export const files = {
  list: null, // 服务端暂无列表接口，由前端本地记录已打开的文件
  open: (path) => api.post('/files/open', { path }),
  upload: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/files/upload', fd)
  },
  downloadUrl: (id) => `/api/files/${id}/download`,
  save: (id) => api.post(`/files/${id}/save`),
  lookup: (id, ip) => api.get(`/files/${id}/lookup`, { params: { ip } }),
  records: (id, offset, limit) => api.get(`/files/${id}/records`, { params: { offset, limit } }),
  upsert: (id, prefix, record) => api.put(`/files/${id}/records/${prefix}`, record),
  remove: (id, prefix) => api.delete(`/files/${id}/records/${prefix}`)
}

export const convert = {
  start: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/convert', fd)
  },
  status: (jobId) => api.get(`/convert/${jobId}`)
}
