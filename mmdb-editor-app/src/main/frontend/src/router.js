import { createRouter, createWebHistory } from 'vue-router'
import FilesView from './views/FilesView.vue'
import LookupView from './views/LookupView.vue'
import BrowseView from './views/BrowseView.vue'
import ConvertView from './views/ConvertView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/files' },
    { path: '/files', component: FilesView },
    { path: '/lookup', component: LookupView },
    { path: '/browse', component: BrowseView },
    { path: '/convert', component: ConvertView }
  ]
})
