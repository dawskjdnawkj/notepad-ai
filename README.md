# 云记事本（CloudNotepad）

一个可部署上线的多用户云记事本：笔记分类、标签、全文搜索、回收站、日历视图、定时提醒。

## 技术栈

- 后端：Spring Boot 3.3 + MyBatis-Plus + MySQL 8 + JWT（jjwt）+ Knife4j
- 前端：Vue 3 + Vite + TypeScript + Pinia + Element Plus

## 设计文档

- [01-需求分析](./docs/01-需求分析.md)
- [02-数据库设计](./docs/02-数据库设计.md)
- [03-接口设计](./docs/03-接口设计.md)

## 当前功能

- Vue 3 前端：可折叠笔记库、富文本编辑、自动保存、本地草稿恢复、标签筛选、分页搜索、日历、通知和回收站。
- Spring Boot 后端：多用户数据隔离、JWT 鉴权、图片管理、提醒任务和 30 天回收站清理。

## 本地运行

1. 使用 MySQL 8 执行 [sql/init.sql](./sql/init.sql)；已有数据库按顺序执行 [sql/upgrade](./sql/upgrade) 中尚未应用的脚本。
2. 确认 `backend/src/main/resources/application.yml` 中的本地数据库、邮件和上传目录配置可用。
3. 启动后端：`cd backend && mvn spring-boot:run`，默认端口为 `8080`，接口文档为 `http://localhost:8080/doc.html`。
4. 启动前端：`cd notepadweb && npm install && npm run dev`，默认地址为 `http://localhost:5173`。

## 构建

- 后端：`cd backend && mvn package`
- 前端：`cd notepadweb && npm run build`

上传图片通过登录态保护；前端访问 `/api` 和 `/uploads` 时由 Vite 或生产环境反向代理转发到后端。
