## 其他注意事项

如果项目是python相关的，那么就该使用 uv 来进行环境管理， 并且要写一个 run.bat 来一键启动项目， 和stop.bat

如果我让你从零开始开发后端的话，那么就该使用 fastapi + sqlalchemy + pgsql(psycopg3) (本地开发测试阶段使用 sqlite)

### 版本控制 (Git & Versioning)
- **版本号规则 (Semantic Versioning)**:
  - 遵循 `Major.Minor.Patch` (主版本.次版本.修订号)。
  - **当前阶段**: 严格保持主版本号为 `0` (如 0.0.1, 0.1.2)，直到我明确指令发布 "v1.0.0"。
  - **版本递增逻辑**:
    - 小修复/Bug Fix -> 增加 Patch (如 0.0.1 -> 0.0.2)。
    - plan新增 (用户追加了很多东西,或者开发了新的功能) -> 增加 Minor (如 0.0.1 -> 0.1.0)。
      - 如果是0.1 这样的变更，那么 git commit 信息应该完整包含 新的功能和修复bug等等详细东西
- **提交信息 (Git Commit)**:
  - 每次代码交付后，请在末尾提供一个标准的 Git Commit Message(中文优先)，然后将这次改动的修改的代码提交git
  - 格式遵循 **Conventional Commits** (例如: `feat: add user login`, `fix: database connection`).
  - **变更日志**: 如果是 Minor 版本变动，请附带一段详细的 Changelog。

## 注意

开发完毕后尝试使用 adb 推送到 192.168.12.101:5555 上安装 

