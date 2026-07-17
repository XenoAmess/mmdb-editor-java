<template>
  <el-container style="height: 100vh">
    <el-aside width="200px">
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/files">文件</el-menu-item>
        <el-menu-item index="/lookup">查询</el-menu-item>
        <el-menu-item index="/browse">浏览 / 编辑</el-menu-item>
        <el-menu-item index="/convert">转换 awdb</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
</script>
